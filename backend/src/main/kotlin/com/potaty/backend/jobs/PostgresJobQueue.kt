/*
 * Copyright (c) 2026, Potaty
 *
 * Postgres-backed JobQueue. The claim uses FOR UPDATE SKIP LOCKED so multiple workers can
 * poll concurrently without blocking each other or double-processing a job (plan 11).
 *
 * Implemented with plain JDBC (parameterized PreparedStatements) against the Hikari
 * DataSource rather than the Exposed DSL, because SKIP LOCKED claim semantics are clearest
 * as a single CTE statement and rely on real JDBC bind parameters (no string interpolation
 * of caller-influenced values).
 */

package com.potaty.backend.jobs

import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PostgresJobQueue(private val dataSource: DataSource) : JobQueue {

    override suspend fun claim(workerId: String, limit: Int, leaseSeconds: Long): List<ClaimedJob> =
        withConnection { conn ->
            val leaseOwner = "$workerId:${UUID.randomUUID()}"
            val sql = """
                with exhausted as (
                    update jobs
                       set status = 'failed',
                           error = jsonb_build_object(
                               'kind', 'retries_exhausted',
                               'reason', 'job lease expired after the maximum number of attempts'
                           ),
                           locked_by = null,
                           locked_until = null,
                           completed_at = now(),
                           updated_at = now()
                     where attempts >= max_attempts
                       and (
                            status = 'queued'
                            or (
                                status = 'running'
                                and (locked_until is null or locked_until <= now())
                            )
                       )
                    returning id
                ), claimed as (
                    select id
                    from jobs
                    where (
                            (status = 'queued' and run_after <= now())
                            or status = 'running'
                          )
                      and (locked_until is null or locked_until <= now())
                      and attempts < max_attempts
                    order by priority asc, run_after asc
                    limit ?
                    for update skip locked
                )
                update jobs j
                   set status = 'running',
                       locked_by = ?,
                       locked_until = now() + (? * interval '1 second'),
                       attempts = j.attempts + 1,
                       updated_at = now()
                  from claimed
                 where j.id = claimed.id
                returning j.id, j.workspace_id, j.project_id, j.job_type,
                          j.attempts, j.max_attempts, j.input, j.created_by;
            """.trimIndent()

            val results = mutableListOf<ClaimedJob>()
            conn.prepareStatement(sql).use { ps ->
                ps.setInt(1, limit)
                ps.setString(2, leaseOwner)
                ps.setLong(3, leaseSeconds)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        results += ClaimedJob(
                            id = rs.getObject("id") as UUID,
                            workspaceId = rs.getObject("workspace_id") as UUID,
                            projectId = rs.getObject("project_id") as? UUID,
                            jobType = rs.getString("job_type"),
                            attempts = rs.getInt("attempts"),
                            maxAttempts = rs.getInt("max_attempts"),
                            inputJson = rs.getString("input"),
                            leaseOwner = leaseOwner,
                            createdBy = rs.getObject("created_by") as? UUID
                        )
                    }
                }
            }
            results
        }

    override suspend fun complete(
        jobId: UUID,
        workspaceId: UUID,
        leaseOwner: String,
        outputJson: String
    ): Boolean =
        update(
            """
            update jobs
               set status = 'succeeded', output = ?::jsonb, locked_by = null,
                   locked_until = null, completed_at = now(), updated_at = now()
             where id = ? and workspace_id = ?
               and status = 'running' and locked_by = ? and locked_until > now();
            """.trimIndent()
        ) { ps ->
            ps.setString(1, outputJson)
            ps.setObject(2, jobId)
            ps.setObject(3, workspaceId)
            ps.setString(4, leaseOwner)
        }

    override suspend fun reschedule(
        jobId: UUID,
        workspaceId: UUID,
        leaseOwner: String,
        delaySeconds: Long,
        reason: String
    ): Boolean =
        update(
            """
            update jobs
               set status = 'queued', locked_by = null, locked_until = null,
                   run_after = now() + (? * interval '1 second'),
                   error = jsonb_build_object('lastRetryReason', ?::text),
                   updated_at = now()
             where id = ? and workspace_id = ?
               and status = 'running' and locked_by = ? and locked_until > now();
            """.trimIndent()
        ) { ps ->
            ps.setLong(1, delaySeconds)
            ps.setString(2, reason)
            ps.setObject(3, jobId)
            ps.setObject(4, workspaceId)
            ps.setString(5, leaseOwner)
        }

    override suspend fun fail(
        jobId: UUID,
        workspaceId: UUID,
        leaseOwner: String,
        errorJson: String
    ): Boolean =
        update(
            """
            update jobs
               set status = 'failed', error = ?::jsonb, locked_by = null,
                   locked_until = null, completed_at = now(), updated_at = now()
             where id = ? and workspace_id = ?
               and status = 'running' and locked_by = ? and locked_until > now();
            """.trimIndent()
        ) { ps ->
            ps.setString(1, errorJson)
            ps.setObject(2, jobId)
            ps.setObject(3, workspaceId)
            ps.setString(4, leaseOwner)
        }

    override suspend fun needsInput(
        jobId: UUID,
        workspaceId: UUID,
        leaseOwner: String,
        reason: String
    ): Boolean =
        update(
            """
            update jobs
               set status = 'needs_input', locked_by = null, locked_until = null,
                   error = jsonb_build_object('needsInput', ?::text), completed_at = now(),
                   updated_at = now()
             where id = ? and workspace_id = ?
               and status = 'running' and locked_by = ? and locked_until > now();
            """.trimIndent()
        ) { ps ->
            ps.setString(1, reason)
            ps.setObject(2, jobId)
            ps.setObject(3, workspaceId)
            ps.setString(4, leaseOwner)
        }

    override suspend fun requestCancellation(
        jobId: UUID,
        workspaceId: UUID
    ): JobCancellationResult? = withConnection { conn ->
        val cancelSql = """
            update jobs
               set status = 'cancelled', locked_by = null, locked_until = null,
                   completed_at = now(), updated_at = now()
             where id = ? and workspace_id = ?
               and status in ('queued', 'running')
            returning status;
        """.trimIndent()
        conn.prepareStatement(cancelSql).use { ps ->
            ps.setObject(1, jobId)
            ps.setObject(2, workspaceId)
            ps.executeQuery().use { rs ->
                if (rs.next()) {
                    return@withConnection JobCancellationResult(
                        JobStatus.CANCELLED,
                        cancelled = true
                    )
                }
            }
        }

        val statusSql = "select status from jobs where id = ? and workspace_id = ?;"
        conn.prepareStatement(statusSql).use { ps ->
            ps.setObject(1, jobId)
            ps.setObject(2, workspaceId)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return@withConnection null
                JobCancellationResult(
                    status = JobStatus.fromWire(rs.getString("status")),
                    cancelled = false
                )
            }
        }
    }

    override suspend fun cancelOwned(
        jobId: UUID,
        workspaceId: UUID,
        leaseOwner: String
    ): Boolean =
        update(
            """
            update jobs
               set status = 'cancelled', locked_by = null, locked_until = null,
                   completed_at = now(), updated_at = now()
             where id = ? and workspace_id = ?
               and status = 'running' and locked_by = ? and locked_until > now();
            """.trimIndent()
        ) { ps ->
            ps.setObject(1, jobId)
            ps.setObject(2, workspaceId)
            ps.setString(3, leaseOwner)
        }

    override suspend fun renewLease(
        jobId: UUID,
        workspaceId: UUID,
        leaseOwner: String,
        leaseSeconds: Long
    ): Boolean =
        update(
            """
            update jobs
               set locked_until = now() + (? * interval '1 second'), updated_at = now()
             where id = ? and workspace_id = ?
               and status = 'running' and locked_by = ? and locked_until > now();
            """.trimIndent()
        ) { ps ->
            ps.setLong(1, leaseSeconds)
            ps.setObject(2, jobId)
            ps.setObject(3, workspaceId)
            ps.setString(4, leaseOwner)
        }

    private suspend fun update(sql: String, bind: (java.sql.PreparedStatement) -> Unit): Boolean =
        withConnection { conn ->
            conn.prepareStatement(sql).use { ps ->
                bind(ps)
                ps.executeUpdate() == 1
            }
        }

    private suspend fun <T> withConnection(block: (Connection) -> T): T = withContext(
        Dispatchers.IO
    ) {
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                val result = block(conn)
                conn.commit()
                result
            } catch (t: Throwable) {
                conn.rollback()
                throw t
            }
        }
    }
}
