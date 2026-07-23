/*
 * Copyright (c) 2026, Potaty
 *
 * Job queue abstraction. The Postgres implementation uses SELECT ... FOR UPDATE SKIP LOCKED
 * for safe concurrent claiming across worker processes (plan 11).
 */

package com.potaty.backend.jobs

import java.util.UUID

enum class JobStatus(val wire: String) {
    QUEUED("queued"),
    RUNNING("running"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    NEEDS_INPUT("needs_input"),
    CANCELLED("cancelled");

    companion object {
        fun fromWire(value: String): JobStatus =
            values().firstOrNull { it.wire == value } ?: error("Unknown job status: $value")
    }
}

/** Statuses after which no provider spend may continue without an explicit new request. */
val JobStatus.isTerminal: Boolean
    get() =
        when (this) {
            JobStatus.SUCCEEDED,
            JobStatus.FAILED,
            JobStatus.NEEDS_INPUT,
            JobStatus.CANCELLED -> true
            JobStatus.QUEUED,
            JobStatus.RUNNING -> false
        }

/** A claimed unit of work returned to a worker. */
data class ClaimedJob(
    val id: UUID,
    val workspaceId: UUID,
    val projectId: UUID?,
    val jobType: String,
    val attempts: Int,
    val maxAttempts: Int,
    val inputJson: String,
    /** Unique fencing token for this claim attempt; required by every state transition. */
    val leaseOwner: String,
    /** Authenticated actor that enqueued the job; carried through to generated artifacts. */
    val createdBy: UUID? = null
)

data class JobCancellationResult(
    val status: JobStatus,
    /** True only when this request changed QUEUED/RUNNING to CANCELLED. */
    val cancelled: Boolean
)

interface JobQueue {

    /**
     * Atomically claims up to [limit] runnable jobs: due QUEUED rows or RUNNING rows whose lease
     * expired. Each claim gets a unique fencing token derived from [workerId].
     */
    suspend fun claim(workerId: String, limit: Int, leaseSeconds: Long): List<ClaimedJob>

    /**
     * Marks a claimed job successful only while [leaseOwner] still owns an unexpired RUNNING lease.
     * Returns false when a stale worker has lost the lease.
     */
    suspend fun complete(
        jobId: UUID,
        workspaceId: UUID,
        leaseOwner: String,
        outputJson: String
    ): Boolean

    /** Releases the current owned lease and reschedules the job for a future retry. */
    suspend fun reschedule(
        jobId: UUID,
        workspaceId: UUID,
        leaseOwner: String,
        delaySeconds: Long,
        reason: String
    ): Boolean

    /** Marks the currently owned job permanently failed. */
    suspend fun fail(jobId: UUID, workspaceId: UUID, leaseOwner: String, errorJson: String): Boolean

    /** Marks the currently owned job as waiting for user input. */
    suspend fun needsInput(
        jobId: UUID,
        workspaceId: UUID,
        leaseOwner: String,
        reason: String
    ): Boolean

    /**
     * Authoritative tenant cancellation used by the authenticated API. QUEUED/RUNNING jobs become
     * CANCELLED; terminal or waiting jobs are returned unchanged. Null means tenant-scoped not
     * found.
     */
    suspend fun requestCancellation(jobId: UUID, workspaceId: UUID): JobCancellationResult?

    /** Cancels only the currently owned, unexpired RUNNING lease. */
    suspend fun cancelOwned(jobId: UUID, workspaceId: UUID, leaseOwner: String): Boolean

    /** Extends only the currently owned, unexpired RUNNING lease. */
    suspend fun renewLease(
        jobId: UUID,
        workspaceId: UUID,
        leaseOwner: String,
        leaseSeconds: Long
    ): Boolean
}
