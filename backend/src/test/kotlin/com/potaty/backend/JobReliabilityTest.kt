/*
 * Copyright (c) 2026, Potaty
 */

package com.potaty.backend

import com.potaty.backend.jobs.ClaimedJob
import com.potaty.backend.jobs.JobQueue
import com.potaty.backend.jobs.JobStatus
import com.potaty.backend.jobs.JobWorkerPool
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class JobReliabilityTest {

    private sealed interface TerminalTransition {
        data class Rescheduled(val reason: String) : TerminalTransition

        data class Failed(val errorJson: String) : TerminalTransition

        object Completed : TerminalTransition

        object NeedsInput : TerminalTransition
    }

    private class RecordingQueue(private val job: ClaimedJob) : JobQueue {
        private val offered = AtomicBoolean(false)
        val terminal = CompletableDeferred<TerminalTransition>()

        override suspend fun claim(
            workerId: String,
            limit: Int,
            leaseSeconds: Long
        ): List<ClaimedJob> = if (offered.compareAndSet(false, true)) listOf(job) else emptyList()

        override suspend fun complete(
            jobId: UUID,
            workspaceId: UUID,
            leaseOwner: String,
            outputJson: String
        ): Boolean = terminal.complete(TerminalTransition.Completed)

        override suspend fun reschedule(
            jobId: UUID,
            workspaceId: UUID,
            leaseOwner: String,
            delaySeconds: Long,
            reason: String
        ): Boolean = terminal.complete(TerminalTransition.Rescheduled(reason))

        override suspend fun fail(
            jobId: UUID,
            workspaceId: UUID,
            leaseOwner: String,
            errorJson: String
        ): Boolean = terminal.complete(TerminalTransition.Failed(errorJson))

        override suspend fun needsInput(
            jobId: UUID,
            workspaceId: UUID,
            leaseOwner: String,
            reason: String
        ): Boolean = terminal.complete(TerminalTransition.NeedsInput)

        override suspend fun requestCancellation(jobId: UUID, workspaceId: UUID) = null

        override suspend fun cancelOwned(
            jobId: UUID,
            workspaceId: UUID,
            leaseOwner: String
        ): Boolean = false

        override suspend fun renewLease(
            jobId: UUID,
            workspaceId: UUID,
            leaseOwner: String,
            leaseSeconds: Long
        ): Boolean = true
    }

    @Test
    fun unexpectedPipelineExceptionIsRescheduledWhenAttemptsRemain() = runBlocking {
        val graph = AppGraph.create(testConfig())
        val queue = RecordingQueue(claimedJob(attempts = 1, maxAttempts = 3))
        val pool =
            JobWorkerPool(
                queue = queue,
                jobs = graph.jobs,
                pipelineFactory = { throw IllegalStateException("secret-retry-payload") },
                workerCount = 1,
                pollIntervalMs = 10,
                leaseSeconds = 60
            )
        try {
            pool.start()
            val terminal = withTimeout(2_000) { queue.terminal.await() }
            assertTrue(terminal is TerminalTransition.Rescheduled)
            assertTrue(terminal.reason.contains("IllegalStateException"))
            assertFalse(terminal.reason.contains("secret-retry-payload"))
        } finally {
            pool.stop()
            graph.stop()
        }
    }

    @Test
    fun unexpectedPipelineExceptionFailsWhenAttemptsAreExhausted() = runBlocking {
        val graph = AppGraph.create(testConfig())
        val queue = RecordingQueue(claimedJob(attempts = 1, maxAttempts = 1))
        val pool =
            JobWorkerPool(
                queue = queue,
                jobs = graph.jobs,
                pipelineFactory = { throw IllegalStateException("secret-terminal-payload") },
                workerCount = 1,
                pollIntervalMs = 10,
                leaseSeconds = 60
            )
        try {
            pool.start()
            val terminal = withTimeout(2_000) { queue.terminal.await() }
            assertTrue(terminal is TerminalTransition.Failed)
            assertTrue(terminal.errorJson.contains("unexpected_exception"))
            assertTrue(terminal.errorJson.contains("IllegalStateException"))
            assertFalse(terminal.errorJson.contains("secret-terminal-payload"))
        } finally {
            pool.stop()
            graph.stop()
        }
    }

    @Test
    fun expiredRunningLeaseIsReclaimedAndStaleOwnerIsFenced() = runBlocking {
        val graph = AppGraph.create(testConfig())
        try {
            val workspaceId = UUID.fromString(graph.config.auth.devWorkspaceId)
            val job =
                graph.jobs.enqueue(
                    workspaceId = workspaceId,
                    projectId = UUID.fromString(graph.config.auth.devProjectId),
                    jobType = "lease-test",
                    idempotencyKey = "lease-" + UUID.randomUUID(),
                    inputJson = "{}",
                    maxAttempts = 3,
                    createdBy = UUID.fromString(graph.config.auth.devUserId)
                )

            val first = awaitClaim(graph.jobQueue, "worker-a", leaseSeconds = 0)
            val reclaimed = awaitClaim(graph.jobQueue, "worker-b", leaseSeconds = 60)

            assertEquals(job.id, reclaimed.id)
            assertEquals(2, reclaimed.attempts)
            assertFalse(
                graph.jobQueue.complete(
                    job.id,
                    workspaceId,
                    first.leaseOwner,
                    """{"worker":"stale"}"""
                ),
                "the previous lease owner must not complete after a reclaim"
            )
            assertTrue(
                graph.jobQueue.complete(
                    job.id,
                    workspaceId,
                    reclaimed.leaseOwner,
                    """{"worker":"current"}"""
                )
            )
            val stored = graph.jobs.findById(workspaceId, job.id)
            assertEquals(JobStatus.SUCCEEDED.wire, stored?.status)
            assertEquals("""{"worker":"current"}""", stored?.outputJson)
        } finally {
            graph.stop()
        }
    }

    /** Queue polling may straddle the database timestamp precision boundary; wait without racing. */
    private suspend fun awaitClaim(
        queue: JobQueue,
        workerId: String,
        leaseSeconds: Long
    ): ClaimedJob =
        withTimeout(2_000) {
            var claimed: ClaimedJob? = null
            while (claimed == null) {
                claimed =
                    queue.claim(workerId, limit = 1, leaseSeconds = leaseSeconds).singleOrNull()
                if (claimed == null) delay(1)
            }
            requireNotNull(claimed)
        }

    private fun claimedJob(attempts: Int, maxAttempts: Int) =
        ClaimedJob(
            id = UUID.randomUUID(),
            workspaceId = UUID.randomUUID(),
            projectId = UUID.randomUUID(),
            jobType = "worker-test",
            attempts = attempts,
            maxAttempts = maxAttempts,
            inputJson = "{}",
            leaseOwner = "lease-${UUID.randomUUID()}"
        )
}
