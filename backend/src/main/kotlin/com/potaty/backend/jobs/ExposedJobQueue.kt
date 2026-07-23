/*
 * Copyright (c) 2026, Potaty
 *
 * Dialect-agnostic JobQueue built on the Exposed DSL (works on H2 and Postgres). Used for the
 * single-node / dev / test deployment. Claiming is guarded by an in-process [Mutex] so the two
 * in-process workers never double-claim a row without relying on Postgres-only
 * `FOR UPDATE SKIP LOCKED` (that path is [PostgresJobQueue], for multi-process production).
 *
 * Mutations are tenant-scoped (id + workspace_id) exactly like the Postgres queue; only the
 * claim polls across workspaces (shared worker pool), carrying workspaceId on every claimed job.
 */

package com.potaty.backend.jobs

import com.potaty.backend.persistence.JobsTable
import com.potaty.backend.persistence.TransactionContext
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update

class ExposedJobQueue(private val txc: TransactionContext) : JobQueue {

    private val claimMutex = Mutex()

    override suspend fun claim(workerId: String, limit: Int, leaseSeconds: Long): List<ClaimedJob> =
        claimMutex.withLock {
            txc.tx {
                val now = Instant.now()
                val lockUntil = now.plusSeconds(leaseSeconds)
                val leaseOwner = "$workerId:${UUID.randomUUID()}"
                failExhaustedJobs(now)
                val candidates =
                    JobsTable.select {
                        (
                            (
                                (JobsTable.status eq JobStatus.QUEUED.wire) and
                                    (JobsTable.runAfter lessEq now)
                                ) or
                                (JobsTable.status eq JobStatus.RUNNING.wire)
                            ) and
                            (
                                JobsTable.lockedUntil.isNull() or
                                    (JobsTable.lockedUntil lessEq now)
                                ) and
                            (JobsTable.attempts less JobsTable.maxAttempts)
                    }
                        .orderBy(
                            JobsTable.priority to SortOrder.ASC,
                            JobsTable.runAfter to SortOrder.ASC
                        )
                        .limit(limit)
                        .map { row ->
                            ClaimedJob(
                                id = row[JobsTable.id],
                                workspaceId = row[JobsTable.workspaceId],
                                projectId = row[JobsTable.projectId],
                                jobType = row[JobsTable.jobType],
                                attempts = row[JobsTable.attempts],
                                maxAttempts = row[JobsTable.maxAttempts],
                                inputJson = row[JobsTable.input],
                                leaseOwner = leaseOwner,
                                createdBy = row[JobsTable.createdBy]
                            )
                        }

                candidates.mapNotNull { c ->
                    val updated =
                        JobsTable.update({
                            (JobsTable.id eq c.id) and
                                (
                                    (
                                        (JobsTable.status eq JobStatus.QUEUED.wire) and
                                            (JobsTable.runAfter lessEq now)
                                        ) or
                                        (JobsTable.status eq JobStatus.RUNNING.wire)
                                    ) and
                                (
                                    JobsTable.lockedUntil.isNull() or
                                        (JobsTable.lockedUntil lessEq now)
                                    ) and
                                (JobsTable.attempts less JobsTable.maxAttempts)
                        }) {
                            it[status] = JobStatus.RUNNING.wire
                            it[lockedBy] = leaseOwner
                            it[lockedUntil] = lockUntil
                            it[attempts] = c.attempts + 1
                            it[updatedAt] = now
                        }
                    // Reflect the incremented attempt count to the worker (drives retry budget).
                    c.copy(attempts = c.attempts + 1).takeIf { updated == 1 }
                }
            }
        }

    override suspend fun complete(
        jobId: UUID,
        workspaceId: UUID,
        leaseOwner: String,
        outputJson: String
    ): Boolean = txc.tx {
        val now = Instant.now()
        JobsTable.update({ activeLease(jobId, workspaceId, leaseOwner, now) }) {
            it[status] = JobStatus.SUCCEEDED.wire
            it[output] = outputJson
            it[lockedBy] = null
            it[lockedUntil] = null
            it[completedAt] = now
            it[updatedAt] = now
        } == 1
    }

    override suspend fun reschedule(
        jobId: UUID,
        workspaceId: UUID,
        leaseOwner: String,
        delaySeconds: Long,
        reason: String
    ): Boolean = txc.tx {
        val now = Instant.now()
        JobsTable.update({ activeLease(jobId, workspaceId, leaseOwner, now) }) {
            it[status] = JobStatus.QUEUED.wire
            it[lockedBy] = null
            it[lockedUntil] = null
            it[runAfter] = now.plusSeconds(delaySeconds)
            it[error] = errorObject("lastRetryReason" to reason).toString()
            it[updatedAt] = now
        } == 1
    }

    override suspend fun fail(
        jobId: UUID,
        workspaceId: UUID,
        leaseOwner: String,
        errorJson: String
    ): Boolean = txc.tx {
        val now = Instant.now()
        JobsTable.update({ activeLease(jobId, workspaceId, leaseOwner, now) }) {
            it[status] = JobStatus.FAILED.wire
            it[error] = errorJson
            it[lockedBy] = null
            it[lockedUntil] = null
            it[completedAt] = now
            it[updatedAt] = now
        } == 1
    }

    override suspend fun needsInput(
        jobId: UUID,
        workspaceId: UUID,
        leaseOwner: String,
        reason: String
    ): Boolean = txc.tx {
        val now = Instant.now()
        JobsTable.update({ activeLease(jobId, workspaceId, leaseOwner, now) }) {
            it[status] = JobStatus.NEEDS_INPUT.wire
            it[lockedBy] = null
            it[lockedUntil] = null
            it[error] = errorObject("needsInput" to reason).toString()
            it[completedAt] = now
            it[updatedAt] = now
        } == 1
    }

    override suspend fun requestCancellation(
        jobId: UUID,
        workspaceId: UUID
    ): JobCancellationResult? = txc.tx {
        val now = Instant.now()
        val changed =
            JobsTable.update({
                (JobsTable.id eq jobId) and
                    (JobsTable.workspaceId eq workspaceId) and
                    (
                        (JobsTable.status eq JobStatus.QUEUED.wire) or
                            (JobsTable.status eq JobStatus.RUNNING.wire)
                        )
            }) {
                it[status] = JobStatus.CANCELLED.wire
                it[lockedBy] = null
                it[lockedUntil] = null
                it[completedAt] = now
                it[updatedAt] = now
            } == 1

        val status =
            JobsTable.select { (JobsTable.id eq jobId) and (JobsTable.workspaceId eq workspaceId) }
                .limit(1)
                .map { JobStatus.fromWire(it[JobsTable.status]) }
                .singleOrNull() ?: return@tx null
        JobCancellationResult(status = status, cancelled = changed)
    }

    override suspend fun cancelOwned(
        jobId: UUID,
        workspaceId: UUID,
        leaseOwner: String
    ): Boolean = txc.tx {
        val now = Instant.now()
        JobsTable.update({ activeLease(jobId, workspaceId, leaseOwner, now) }) {
            it[status] = JobStatus.CANCELLED.wire
            it[lockedBy] = null
            it[lockedUntil] = null
            it[completedAt] = now
            it[updatedAt] = now
        } == 1
    }

    override suspend fun renewLease(
        jobId: UUID,
        workspaceId: UUID,
        leaseOwner: String,
        leaseSeconds: Long
    ): Boolean = txc.tx {
        val now = Instant.now()
        JobsTable.update({ activeLease(jobId, workspaceId, leaseOwner, now) }) {
            it[lockedUntil] = now.plusSeconds(leaseSeconds)
            it[updatedAt] = now
        } == 1
    }

    private fun activeLease(
        jobId: UUID,
        workspaceId: UUID,
        leaseOwner: String,
        now: Instant
    ) =
        (JobsTable.id eq jobId) and
            (JobsTable.workspaceId eq workspaceId) and
            (JobsTable.status eq JobStatus.RUNNING.wire) and
            (JobsTable.lockedBy eq leaseOwner) and
            (JobsTable.lockedUntil greater now)

    private fun failExhaustedJobs(now: Instant) {
        val leaseExpired = JobsTable.lockedUntil.isNull() or (JobsTable.lockedUntil lessEq now)
        JobsTable.update({
            (JobsTable.attempts greaterEq JobsTable.maxAttempts) and
                (
                    (JobsTable.status eq JobStatus.QUEUED.wire) or
                        ((JobsTable.status eq JobStatus.RUNNING.wire) and leaseExpired)
                    )
        }) {
            it[status] = JobStatus.FAILED.wire
            it[error] =
                errorObject(
                    "kind" to "retries_exhausted",
                    "reason" to "job lease expired after the maximum number of attempts"
                )
                    .toString()
            it[lockedBy] = null
            it[lockedUntil] = null
            it[completedAt] = now
            it[updatedAt] = now
        }
    }

    private fun errorObject(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject {
        pairs.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
    }
}
