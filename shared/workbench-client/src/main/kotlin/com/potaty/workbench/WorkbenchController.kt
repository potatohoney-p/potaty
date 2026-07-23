/*
 * Copyright (c) 2026, Potaty
 *
 * Orchestrates the source-grounded "paste text -> diagram" flow for the editor UI:
 * createSource -> createDiagramJob -> poll -> getDiagramVersion. The poll delay is injected
 * (`sleep`) so the module needs no coroutines-runtime dependency: the host app passes
 * kotlinx.coroutines.delay; tests pass a no-op.
 */

package com.potaty.workbench

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.jsonPrimitive

sealed interface WorkbenchResult {
    /** Job finished and a diagram version was fetched; [mermaid] is the rendered Mermaid if present. */
    data class Ready(
        val version: DiagramVersionResponse,
        val mermaid: String?,
        val source: WorkbenchSourceSummary
    ) : WorkbenchResult

    /** Job did not yield a consumable version, including a succeeded job with missing output. */
    data class Failed(
        val status: String,
        val reason: String? = null,
        /** Server acknowledgement when cancellation was requested after job creation. */
        val cancellation: CancelJobResponse? = null
    ) : WorkbenchResult
}

/**
 * Whether the browser may retire this attempt's job idempotency key. A fetched [WorkbenchResult.Ready]
 * is consumed. A known non-success terminal state is also consumed because a human retry is a new
 * attempt. A successful job without usable output is deliberately *not* consumed: replay must keep
 * the same key and recover that server job instead of creating another billable generation.
 */
fun WorkbenchResult.consumesRetryAttempt(): Boolean =
    when (this) {
        is WorkbenchResult.Ready -> true
        is WorkbenchResult.Failed ->
            JobPoller.isTerminal(status) && !JobPoller.isSuccess(status)
    }

/** Source and safety metadata kept alongside the generated artifact for the evidence workbench. */
data class WorkbenchSourceSummary(
    val sourceType: String,
    val displayName: String,
    val sourceId: String,
    val sourceVersionId: String,
    val secretsRedacted: Int = 0,
    val piiWarnings: Int = 0,
    val filesIndexed: Int = 0,
    val filesSkipped: Int = 0,
    val chunkCount: Int = 0,
    val treeTruncated: Boolean = false,
    val repository: String? = null,
    val ref: String? = null
)

/** A stable UI-facing progress event; callers should treat [progress] as an estimate, not billing data. */
data class WorkbenchProgress(
    val status: String,
    val currentStage: String?,
    val progress: Double
)

class WorkbenchController(
    private val client: PotatyApiClient,
    private val pollDelayMs: Long = 800,
    // Three provider attempts may each consume the configured 180-second HTTP timeout. Keep the
    // browser attached long enough for those attempts plus queueing and persistence overhead.
    private val maxPolls: Int = 900,
    private val sleep: suspend (Long) -> Unit = {}
) {
    suspend fun generateFromText(
        projectId: String,
        text: String,
        diagramType: String,
        sourceIdempotencyKey: String,
        jobIdempotencyKey: String,
        objective: String? = null,
        displayName: String = "Pasted text",
        sourceType: String = "TEXT_PASTE",
        outputFormats: List<String> = listOf("mermaid"),
        abstractionLevel: String = "medium",
        onProgress: (WorkbenchProgress) -> Unit = {},
        isCancelled: () -> Boolean = { false }
    ): WorkbenchResult {
        onProgress(WorkbenchProgress("preparing", "normalize", 0.03))
        val source = client.createSource(
            projectId,
            CreateSourceRequest(sourceType = sourceType, displayName = displayName, content = text),
            sourceIdempotencyKey
        )
        return runJobAndFetch(
            projectId = projectId,
            sourceVersionId = source.sourceVersionId,
            diagramType = diagramType,
            jobIdempotencyKey = jobIdempotencyKey,
            objective = objective,
            outputFormats = outputFormats,
            abstractionLevel = abstractionLevel,
            source = WorkbenchSourceSummary(
                sourceType = sourceType,
                displayName = displayName,
                sourceId = source.sourceId,
                sourceVersionId = source.sourceVersionId,
                secretsRedacted = source.secretsRedacted,
                piiWarnings = source.piiWarnings
            ),
            onProgress = onProgress,
            isCancelled = isCancelled
        )
    }

    suspend fun generateFromTranscript(
        projectId: String,
        text: String,
        diagramType: String,
        sourceIdempotencyKey: String,
        jobIdempotencyKey: String,
        displayName: String,
        objective: String? = null,
        outputFormats: List<String> = listOf("mermaid"),
        abstractionLevel: String = "medium",
        onProgress: (WorkbenchProgress) -> Unit = {},
        isCancelled: () -> Boolean = { false }
    ): WorkbenchResult = generateFromText(
        projectId = projectId,
        text = text,
        diagramType = diagramType,
        sourceIdempotencyKey = sourceIdempotencyKey,
        jobIdempotencyKey = jobIdempotencyKey,
        objective = objective,
        displayName = displayName,
        sourceType = "TRANSCRIPT",
        outputFormats = outputFormats,
        abstractionLevel = abstractionLevel,
        onProgress = onProgress,
        isCancelled = isCancelled
    )

    suspend fun generateFromGitHub(
        projectId: String,
        repoUrl: String,
        diagramType: String,
        sourceIdempotencyKey: String,
        jobIdempotencyKey: String,
        ref: String? = null,
        objective: String? = null,
        outputFormats: List<String> = listOf("mermaid"),
        abstractionLevel: String = "medium",
        onProgress: (WorkbenchProgress) -> Unit = {},
        isCancelled: () -> Boolean = { false }
    ): WorkbenchResult {
        onProgress(WorkbenchProgress("indexing", "normalize", 0.03))
        val indexed =
            client.indexGitHubUrl(
                projectId,
                GitHubIndexUrlRequest(repoUrl, ref),
                sourceIdempotencyKey
            )
        return runJobAndFetch(
            projectId = projectId,
            sourceVersionId = indexed.sourceVersionId,
            diagramType = diagramType,
            jobIdempotencyKey = jobIdempotencyKey,
            objective = objective,
            outputFormats = outputFormats,
            abstractionLevel = abstractionLevel,
            source = WorkbenchSourceSummary(
                sourceType = "GITHUB_REPOSITORY",
                displayName = "${indexed.owner}/${indexed.repo}",
                sourceId = indexed.sourceId,
                sourceVersionId = indexed.sourceVersionId,
                filesIndexed = indexed.filesIndexed,
                filesSkipped = indexed.filesSkipped,
                chunkCount = indexed.chunkCount,
                treeTruncated = indexed.treeTruncated,
                repository = "${indexed.owner}/${indexed.repo}",
                ref = indexed.ref
            ),
            onProgress = onProgress,
            isCancelled = isCancelled
        )
    }

    private suspend fun runJobAndFetch(
        projectId: String,
        sourceVersionId: String,
        diagramType: String,
        jobIdempotencyKey: String,
        objective: String?,
        outputFormats: List<String>,
        abstractionLevel: String,
        source: WorkbenchSourceSummary,
        onProgress: (WorkbenchProgress) -> Unit,
        isCancelled: () -> Boolean
    ): WorkbenchResult {
        if (isCancelled()) return WorkbenchResult.Failed("cancelled", "Generation cancelled")
        onProgress(WorkbenchProgress("queued", "extract", 0.12))
        val job = client.createDiagramJob(
            projectId,
            DiagramJobRequest(
                sourceVersionIds = listOf(sourceVersionId),
                diagramType = diagramType,
                objective = objective,
                scope = JobScope(abstractionLevel = abstractionLevel),
                outputFormats = outputFormats
            ),
            jobIdempotencyKey
        )
        if (isCancelled()) return cancelCreatedJob(job.jobId)

        var status = job.status
        var output = (null as kotlinx.serialization.json.JsonObject?)
        var terminalReason: String? = null
        var polls = 0
        // The enqueue endpoint intentionally returns no output. An idempotent replay can already
        // be succeeded, so refresh it once before deciding whether the terminal result is usable.
        if (JobPoller.isSuccess(status)) {
            if (isCancelled()) return cancelCreatedJob(job.jobId)
            val snapshot = client.getJob(job.jobId)
            status = snapshot.status
            output = snapshot.output
            terminalReason = snapshot.reason
        }
        while (!JobPoller.isTerminal(status) && polls < maxPolls) {
            if (isCancelled()) return cancelCreatedJob(job.jobId)
            sleep(pollDelayMs)
            if (isCancelled()) return cancelCreatedJob(job.jobId)
            val s = client.getJob(job.jobId)
            status = s.status
            output = s.output
            terminalReason = s.reason
            val estimated = (0.14 + (s.progress.coerceIn(0.0, 1.0) * 0.72)).coerceAtMost(0.86)
            onProgress(WorkbenchProgress(status, s.currentStage ?: "plan", estimated))
            polls++
            if (isCancelled()) return cancelCreatedJob(job.jobId)
        }
        if (!JobPoller.isSuccess(status)) {
            val reason =
                if (polls >= maxPolls) {
                    "Generation exceeded the 12-minute polling window. " +
                        "The server may still be processing it."
                } else {
                    terminalReason
                }
            return WorkbenchResult.Failed(status, reason)
        }

        val out = output ?: return WorkbenchResult.Failed(status, "succeeded job had no output")
        val diagramId = out["diagramId"]?.jsonPrimitive?.content
            ?: return WorkbenchResult.Failed(status, "succeeded job had no diagramId in output")
        val versionId = out["versionId"]?.jsonPrimitive?.content
            ?: return WorkbenchResult.Failed(status, "succeeded job had no versionId in output")

        if (isCancelled()) return cancelCreatedJob(job.jobId)
        onProgress(WorkbenchProgress("rendering", "render", 0.92))
        val version = client.getDiagramVersion(diagramId, versionId)
        val mermaid = version.renderings.firstOrNull {
            it.format.equals("mermaid", ignoreCase = true)
        }?.contentText
        onProgress(WorkbenchProgress("succeeded", "render", 1.0))
        return WorkbenchResult.Ready(version, mermaid, source)
    }

    private suspend fun cancelCreatedJob(jobId: String): WorkbenchResult.Failed {
        val acknowledgement = try {
            client.cancelJob(jobId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
        if (acknowledgement == null || !JobPoller.isTerminal(acknowledgement.status)) {
            // Stop the local flow, but preserve the job idempotency key. A retry can then recover
            // the same server job instead of starting a second billable generation.
            return WorkbenchResult.Failed(
                status = CANCELLATION_UNCONFIRMED,
                reason =
                "Stopped locally, but server cancellation was not confirmed. " +
                    "Retry to resume the same job.",
                cancellation = acknowledgement
            )
        }
        if (JobPoller.isSuccess(acknowledgement.status)) {
            // The job won the race with cancellation. Preserve the key until a retry fetches its
            // authoritative output; treating this as a consumed terminal result would duplicate it.
            return WorkbenchResult.Failed(
                status = CANCELLATION_RESULT_PENDING,
                reason = "The job completed while cancellation was requested. Retry to load it.",
                cancellation = acknowledgement
            )
        }
        return WorkbenchResult.Failed(
            status = acknowledgement.status,
            reason =
            if (acknowledgement.cancelled) {
                "Generation cancelled"
            } else {
                "Generation finished before cancellation"
            },
            cancellation = acknowledgement
        )
    }
}

private const val CANCELLATION_UNCONFIRMED = "cancellation_unconfirmed"
private const val CANCELLATION_RESULT_PENDING = "cancellation_result_pending"
