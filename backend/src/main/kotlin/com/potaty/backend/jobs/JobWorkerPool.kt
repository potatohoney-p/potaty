/*
 * Copyright (c) 2026, Potaty
 *
 * Worker pool that polls the JobQueue, runs the pipeline, and applies retry/terminal
 * transitions. Designed to run as a background coroutine scope alongside the HTTP server.
 */

package com.potaty.backend.jobs

import com.potaty.backend.persistence.repositories.JobRepository
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import org.slf4j.LoggerFactory

private class LeaseLostCancellationException(jobId: UUID) :
    CancellationException("Lease ownership was lost for job $jobId")

class JobWorkerPool(
    private val queue: JobQueue,
    private val jobs: JobRepository,
    private val pipelineFactory: () -> JobPipeline,
    private val workerCount: Int = 2,
    private val pollIntervalMs: Long = 1_000,
    private val leaseSeconds: Long = 120,
    private val onTerminal: suspend (workspaceId: UUID, jobId: UUID) -> Unit = { _, _ -> }
) {
    private val log = LoggerFactory.getLogger(JobWorkerPool::class.java)
    private val scope = CoroutineScope(SupervisorJob())
    private val workers = mutableListOf<Job>()

    fun start() {
        repeat(workerCount) { idx ->
            val workerId = "worker-$idx-${UUID.randomUUID().toString().take(8)}"
            workers += scope.launch { loop(workerId) }
        }
        log.info("JobWorkerPool started with {} workers", workerCount)
    }

    fun stop() {
        scope.cancel()
    }

    private suspend fun loop(workerId: String) {
        while (scope.isActive) {
            try {
                val claimed = queue.claim(workerId, limit = 1, leaseSeconds = leaseSeconds)
                if (claimed.isEmpty()) {
                    delay(pollIntervalMs)
                    continue
                }
                claimed.forEach { runJob(it) }
            } catch (cancelled: CancellationException) {
                // Normal shutdown: do not turn cooperative cancellation into an error or retry.
                throw cancelled
            } catch (t: Throwable) {
                log.error(
                    "Worker {} loop failed with exceptionType={}",
                    workerId,
                    exceptionType(t),
                    sanitizedThrowable(t)
                )
                delay(pollIntervalMs)
            }
        }
    }

    private suspend fun runJob(job: ClaimedJob) {
        val context =
            JobContext(
                jobId = job.id,
                workspaceId = job.workspaceId,
                projectId = job.projectId,
                createdBy = job.createdBy,
                emitEvent = { stage, message ->
                    jobs.recordEvent(job.workspaceId, job.id, "STAGE_PROGRESS", stage.name, message)
                }
            )

        val result =
            try {
                runPipelineWithHeartbeat(job, context)
            } catch (leaseLost: LeaseLostCancellationException) {
                // Cancellation/reclaim removes or replaces our fencing token. The authoritative row
                // already describes what should happen next, so this worker must not write a
                // terminal
                // transition (or turn cooperative cancellation into a retry).
                log.warn("Stopped job {} after losing its lease", job.id)
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                val incidentId = UUID.randomUUID().toString()
                val type = exceptionType(t)
                val reason = "unexpected pipeline exception (incident=$incidentId, type=$type)"
                log.error(
                    "Job {} pipeline failed incidentId={} exceptionType={}",
                    job.id,
                    incidentId,
                    type,
                    sanitizedThrowable(t)
                )
                handleUnexpectedFailure(job, reason)
                return
            }

        when (result) {
            is StageResult.Ok -> {
                // The terminal stage returns the job output JSON (diagram/version ids).
                val outputJson = (result.value as? String) ?: """{"status":"ok"}"""
                applyTerminal(
                    job,
                    "complete"
                ) { queue.complete(job.id, job.workspaceId, job.leaseOwner, outputJson) }
            }
            is StageResult.RetryableFailure -> {
                if (RetryPolicy.hasAttemptsLeft(job.attempts, job.maxAttempts)) {
                    val delaySec =
                        result.retryAfter?.inWholeSeconds
                            ?: RetryPolicy.backoffSeconds(job.attempts + 1)
                    warnIfLeaseLost(
                        job,
                        queue.reschedule(
                            job.id,
                            job.workspaceId,
                            job.leaseOwner,
                            delaySec,
                            result.reason
                        ),
                        "reschedule"
                    )
                } else {
                    applyTerminal(
                        job,
                        "fail"
                    ) {
                        queue.fail(
                            job.id,
                            job.workspaceId,
                            job.leaseOwner,
                            errorJson(result.reason, "retries_exhausted")
                        )
                    }
                }
            }
            is StageResult.FatalFailure -> {
                applyTerminal(
                    job,
                    "fail"
                ) {
                    queue.fail(
                        job.id,
                        job.workspaceId,
                        job.leaseOwner,
                        errorJson(result.reason, "fatal")
                    )
                }
            }
            is StageResult.NeedsUserInput -> {
                applyTerminal(
                    job,
                    "needs_input"
                ) {
                    queue.needsInput(job.id, job.workspaceId, job.leaseOwner, result.reason)
                }
            }
        }
    }

    private suspend fun runPipelineWithHeartbeat(
        job: ClaimedJob,
        context: JobContext
    ): StageResult<Any?> = supervisorScope {
        val pipeline = async { pipelineFactory().run(job.inputJson, context) }
        val heartbeat = launch {
            while (isActive) {
                delay(heartbeatIntervalMs())
                val renewed =
                    try {
                        queue.renewLease(
                            jobId = job.id,
                            workspaceId = job.workspaceId,
                            leaseOwner = job.leaseOwner,
                            leaseSeconds = leaseSeconds
                        )
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        log.error(
                            "Lease heartbeat failed for job {} with exceptionType={}; " +
                                "stopping stale work",
                            job.id,
                            exceptionType(t),
                            sanitizedThrowable(t)
                        )
                        false
                    }
                if (!renewed) {
                    pipeline.cancel(LeaseLostCancellationException(job.id))
                    return@launch
                }
            }
        }

        try {
            pipeline.await()
        } finally {
            heartbeat.cancelAndJoin()
        }
    }

    /**
     * Renew at one third of the lease duration, capped so production cancellation remains
     * responsive even with a long lease while short test/development leases stay safe.
     */
    private fun heartbeatIntervalMs(): Long =
        ((leaseSeconds.coerceAtLeast(1) * 1_000L) / 3L).coerceIn(100L, 5_000L)

    private suspend fun handleUnexpectedFailure(job: ClaimedJob, reason: String) {
        if (RetryPolicy.hasAttemptsLeft(job.attempts, job.maxAttempts)) {
            val delaySeconds = RetryPolicy.backoffSeconds(job.attempts + 1)
            warnIfLeaseLost(
                job,
                queue.reschedule(job.id, job.workspaceId, job.leaseOwner, delaySeconds, reason),
                "reschedule unexpected exception"
            )
        } else {
            applyTerminal(
                job,
                "fail unexpected exception"
            ) {
                queue.fail(
                    job.id,
                    job.workspaceId,
                    job.leaseOwner,
                    errorJson(reason, "unexpected_exception")
                )
            }
        }
    }

    private suspend fun applyTerminal(
        job: ClaimedJob,
        transition: String,
        operation: suspend () -> Boolean
    ) {
        val updated = operation()
        warnIfLeaseLost(job, updated, transition)
        if (!updated) return
        try {
            onTerminal(job.workspaceId, job.id)
        } catch (t: Throwable) {
            // The durable reservation expires as a conservative crash-recovery backstop. Do not
            // roll back an authoritative terminal job transition because cleanup telemetry failed.
            log.error(
                "Terminal cleanup failed for job {} with exceptionType={}",
                job.id,
                exceptionType(t),
                sanitizedThrowable(t)
            )
        }
    }

    private fun warnIfLeaseLost(job: ClaimedJob, updated: Boolean, transition: String) {
        if (!updated) {
            log.warn(
                "Ignored stale job transition '{}' for job {} because lease ownership was lost",
                transition,
                job.id
            )
        }
    }

    private fun errorJson(reason: String, kind: String): String =
        """{"kind":"$kind","reason":${jsonString(reason)}}"""

    private fun jsonString(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    /**
     * Preserve exception shape and stack frames for operators without copying messages that can
     * contain provider payloads, SQL details, credentials, or imported source text.
     */
    private fun sanitizedThrowable(cause: Throwable, depth: Int = 0): Throwable {
        val sanitized =
            RuntimeException(exceptionType(cause)).also { it.stackTrace = cause.stackTrace }
        if (depth >= MAX_SANITIZED_CAUSE_DEPTH) return sanitized

        cause.cause
            ?.takeUnless { it === cause }
            ?.let { sanitized.initCause(sanitizedThrowable(it, depth + 1)) }
        cause.suppressed.take(MAX_SANITIZED_SUPPRESSED).forEach {
            sanitized.addSuppressed(sanitizedThrowable(it, depth + 1))
        }
        return sanitized
    }

    private fun exceptionType(cause: Throwable): String =
        cause::class.qualifiedName ?: cause::class.simpleName ?: "Throwable"

    private companion object {
        const val MAX_SANITIZED_CAUSE_DEPTH = 8
        const val MAX_SANITIZED_SUPPRESSED = 8
    }
}
