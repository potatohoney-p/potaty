/*
 * Copyright (c) 2026, Potaty
 *
 * Tenant-scoped persistence for jobs and job events. The atomic claim (FOR UPDATE SKIP
 * LOCKED) lives in PostgresJobQueue; this repository owns the non-claim CRUD. All queries
 * filter by workspaceId except the cross-tenant poll claim, which is restricted to the
 * worker pool and still records workspaceId on every event for audit.
 */

package com.potaty.backend.persistence.repositories

import com.potaty.backend.jobs.JobStatus
import com.potaty.backend.jobs.isTerminal
import com.potaty.backend.persistence.IdentityRepository
import com.potaty.backend.persistence.JobEventsTable
import com.potaty.backend.persistence.JobsTable
import com.potaty.backend.persistence.TransactionContext
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update

data class JobRecord(
    val id: UUID,
    val workspaceId: UUID,
    val projectId: UUID?,
    val jobType: String,
    val status: String,
    val idempotencyKey: String,
    val attempts: Int,
    val maxAttempts: Int,
    val inputJson: String,
    val outputJson: String? = null,
    val errorJson: String? = null,
    val createdBy: UUID? = null
)

data class JobEventRecord(
    val id: UUID,
    val eventType: String,
    val stage: String?,
    val message: String?,
    val createdAt: Instant
)

class IdempotencyConflictException(message: String) : RuntimeException(message)

class JobRepository(
    private val txc: TransactionContext,
    private val identities: IdentityRepository? = null
) {

    /**
     * Inserts a job, or returns the existing one if (workspace_id, idempotency_key) collides (plan
     * 11.3). Caller decides whether to force regeneration.
     */
    suspend fun enqueue(
        workspaceId: UUID,
        projectId: UUID?,
        jobType: String,
        idempotencyKey: String,
        inputJson: String,
        priority: Int = 100,
        maxAttempts: Int = 3,
        createdBy: UUID?
    ): JobRecord {
        if (projectId != null) identities?.requireProject(workspaceId, projectId)
        identities?.requireMember(workspaceId, createdBy)
        try {
            return txc.tx {
                val existing = findByIdempotencyKeyInternal(workspaceId, idempotencyKey)
                if (existing != null) {
                    return@tx validateReplay(existing, projectId, jobType, inputJson)
                }

                val newId = UUID.randomUUID()
                val now = Instant.now()
                JobsTable.insert {
                    it[id] = newId
                    it[JobsTable.workspaceId] = workspaceId
                    it[JobsTable.projectId] = projectId
                    it[JobsTable.jobType] = jobType
                    it[status] = JobStatus.QUEUED.wire
                    it[JobsTable.idempotencyKey] = idempotencyKey
                    it[JobsTable.priority] = priority
                    it[attempts] = 0
                    it[JobsTable.maxAttempts] = maxAttempts
                    it[input] = inputJson
                    it[runAfter] = now
                    it[JobsTable.createdBy] = createdBy
                    it[createdAt] = now
                    it[updatedAt] = now
                }
                JobRecord(
                    id = newId,
                    workspaceId = workspaceId,
                    projectId = projectId,
                    jobType = jobType,
                    status = JobStatus.QUEUED.wire,
                    idempotencyKey = idempotencyKey,
                    attempts = 0,
                    maxAttempts = maxAttempts,
                    inputJson = inputJson,
                    createdBy = createdBy
                )
            }
        } catch (cause: IdempotencyConflictException) {
            throw cause
        } catch (cause: Throwable) {
            if (cause is CancellationException) throw cause
            // Two callers can pass the initial lookup together. The unique constraint is the
            // authority; after a losing insert rolls back, replay the lookup in a fresh
            // transaction and return only if the request is byte-for-byte equivalent.
            val concurrentWinner = txc.tx {
                findByIdempotencyKeyInternal(workspaceId, idempotencyKey)
            }
            if (concurrentWinner != null) {
                return validateReplay(concurrentWinner, projectId, jobType, inputJson)
            }
            throw cause
        }
    }

    private fun validateReplay(
        existing: JobRecord,
        projectId: UUID?,
        jobType: String,
        inputJson: String
    ): JobRecord {
        if (
            existing.projectId != projectId ||
            existing.jobType != jobType ||
            existing.inputJson != inputJson
        ) {
            throw IdempotencyConflictException(
                "Idempotency-Key was already used for a different request"
            )
        }
        return existing
    }

    suspend fun findById(workspaceId: UUID, jobId: UUID): JobRecord? = txc.tx {
        JobsTable.select { (JobsTable.id eq jobId) and (JobsTable.workspaceId eq workspaceId) }
            .limit(1)
            .map(::toRecord)
            .singleOrNull()
    }

    suspend fun markStatus(
        workspaceId: UUID,
        jobId: UUID,
        status: JobStatus,
        outputJson: String? = null,
        errorJson: String? = null
    ) {
        identities?.requireJob(workspaceId, jobId)
        return txc.tx {
            JobsTable.update({
                (JobsTable.id eq jobId) and (JobsTable.workspaceId eq workspaceId)
            }) {
                it[JobsTable.status] = status.wire
                if (outputJson != null) it[output] = outputJson
                if (errorJson != null) it[error] = errorJson
                it[updatedAt] = Instant.now()
                if (status.isTerminal) {
                    it[completedAt] = Instant.now()
                }
            }
        }
    }

    suspend fun recordEvent(
        workspaceId: UUID,
        jobId: UUID,
        eventType: String,
        stage: String?,
        message: String?,
        payloadJson: String = "{}"
    ) {
        identities?.requireJob(workspaceId, jobId)
        return txc.tx {
            JobEventsTable.insert {
                it[id] = UUID.randomUUID()
                it[JobEventsTable.workspaceId] = workspaceId
                it[JobEventsTable.jobId] = jobId
                it[JobEventsTable.eventType] = eventType
                it[JobEventsTable.stage] = stage
                it[JobEventsTable.message] = message
                it[payload] = payloadJson
                it[createdAt] = Instant.now()
            }
        }
    }

    /** Returns a bounded, deterministic event timeline for one tenant-owned job. */
    suspend fun listEvents(
        workspaceId: UUID,
        jobId: UUID,
        limit: Int = 100
    ): List<JobEventRecord> = txc.tx {
        JobEventsTable.select {
            (JobEventsTable.workspaceId eq workspaceId) and (JobEventsTable.jobId eq jobId)
        }
            .orderBy(
                JobEventsTable.createdAt to SortOrder.ASC,
                JobEventsTable.id to SortOrder.ASC
            )
            .limit(limit.coerceIn(1, 200))
            .map { row ->
                JobEventRecord(
                    id = row[JobEventsTable.id],
                    eventType = row[JobEventsTable.eventType],
                    stage = row[JobEventsTable.stage],
                    message = row[JobEventsTable.message],
                    createdAt = row[JobEventsTable.createdAt]
                )
            }
    }

    private fun findByIdempotencyKeyInternal(
        workspaceId: UUID,
        idempotencyKey: String
    ): JobRecord? =
        JobsTable.select {
            (JobsTable.workspaceId eq workspaceId) and
                (JobsTable.idempotencyKey eq idempotencyKey)
        }
            .limit(1)
            .map(::toRecord)
            .singleOrNull()

    private fun toRecord(row: org.jetbrains.exposed.sql.ResultRow) =
        JobRecord(
            id = row[JobsTable.id],
            workspaceId = row[JobsTable.workspaceId],
            projectId = row[JobsTable.projectId],
            jobType = row[JobsTable.jobType],
            status = row[JobsTable.status],
            idempotencyKey = row[JobsTable.idempotencyKey],
            attempts = row[JobsTable.attempts],
            maxAttempts = row[JobsTable.maxAttempts],
            inputJson = row[JobsTable.input],
            outputJson = row[JobsTable.output],
            errorJson = row[JobsTable.error],
            createdBy = row[JobsTable.createdBy]
        )
}
