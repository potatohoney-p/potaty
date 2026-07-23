/*
 * Copyright (c) 2026, Potaty
 *
 * Job polling routes (plan 10.3). Tenant-scoped: a job is only visible to its own workspace.
 * Once a job succeeds, its output (diagramId / versionId) is returned so the client can fetch
 * the generated diagram version.
 */

package com.potaty.backend.api

import com.potaty.backend.AppGraph
import com.potaty.backend.auth.Permission
import com.potaty.backend.auth.Rbac
import com.potaty.backend.auth.tenant
import com.potaty.backend.jobs.JobStatus
import com.potaty.backend.jobs.StageName
import com.potaty.backend.jobs.isTerminal
import com.potaty.backend.persistence.repositories.JobEventRecord
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.util.UUID
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

fun Route.jobRoutes(graph: AppGraph) {
    route("/jobs/{jobId}") {
        // GET /api/v1/jobs/{jobId}
        get {
            val tenant = call.tenant()
            Rbac.require(tenant, Permission.VIEW_DIAGRAM)

            val workspaceId =
                runCatching { UUID.fromString(tenant.workspaceId) }.getOrNull()
                    ?: return@get call.respond(
                        HttpStatusCode.BadRequest,
                        ApiError("bad_request", "invalid workspace")
                    )
            val jobId =
                call.parameters["jobId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: return@get call.respond(
                        HttpStatusCode.BadRequest,
                        ApiError("bad_request", "missing/invalid jobId")
                    )

            val job =
                graph.jobs.findById(workspaceId, jobId)
                    ?: return@get call.respond(
                        HttpStatusCode.NotFound,
                        ApiError("not_found", "job not found")
                    )

            val events = graph.jobs.listEvents(workspaceId, jobId)
            val progress = jobProgress(JobStatus.fromWire(job.status), events)
            val currentStage = events.lastOrNull { it.stage != null }?.stage
            val output =
                job.outputJson?.let {
                    runCatching { graph.json.parseToJsonElement(it).jsonObject }.getOrNull()
                }

            call.respond(
                JobStatusResponse(
                    jobId = jobId.toString(),
                    status = job.status,
                    currentStage = currentStage,
                    progress = progress,
                    events =
                    events.map { event ->
                        JobEventDto(
                            stage = event.stage,
                            message = event.message,
                            createdAt = event.createdAt.toString()
                        )
                    },
                    reason = terminalReason(JobStatus.fromWire(job.status), job.errorJson, graph),
                    output = output
                )
            )
        }

        // POST /api/v1/jobs/{jobId}/cancel
        // RUN_JOB is deliberately stricter than VIEW_DIAGRAM: viewers may inspect progress but
        // cannot interrupt workspace compute. The queue update itself repeats tenant scoping.
        post("/cancel") {
            val tenant = call.tenant()
            Rbac.require(tenant, Permission.RUN_JOB)

            val workspaceId =
                runCatching { UUID.fromString(tenant.workspaceId) }.getOrNull()
                    ?: return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ApiError("bad_request", "invalid workspace")
                    )
            val jobId =
                call.parameters["jobId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ApiError("bad_request", "missing/invalid jobId")
                    )

            val result =
                graph.jobQueue.requestCancellation(jobId, workspaceId)
                    ?: return@post call.respond(
                        HttpStatusCode.NotFound,
                        ApiError("not_found", "job not found")
                    )

            // Also heal a reservation left behind by an earlier terminal-cleanup failure.
            if (result.status.isTerminal) {
                graph.quotaGuard.releaseForJob(workspaceId, jobId)
            }

            call.respond(
                CancelJobResponse(
                    jobId = jobId.toString(),
                    status = result.status.wire,
                    cancelled = result.cancelled
                )
            )
        }
    }
}

private fun terminalReason(status: JobStatus, errorJson: String?, graph: AppGraph): String? {
    if (status != JobStatus.FAILED && status != JobStatus.NEEDS_INPUT) return null
    val error =
        errorJson?.let {
            runCatching { graph.json.parseToJsonElement(it).jsonObject }.getOrNull()
        } ?: return null
    return listOf("needsInput", "reason", "lastRetryReason")
        .firstNotNullOfOrNull { key ->
            runCatching { error[key]?.jsonPrimitive?.content }.getOrNull()
        }
        ?.filterNot(Char::isISOControl)
        ?.trim()
        ?.take(MAX_JOB_REASON_CHARS)
        ?.takeIf(String::isNotEmpty)
}

private const val MAX_JOB_REASON_CHARS = 500

internal fun jobProgress(status: JobStatus, events: List<JobEventRecord>): Double {
    if (status.isTerminal) {
        return 1.0
    }
    if (status == JobStatus.QUEUED) return 0.0

    val furthestStage =
        events
            .mapNotNull { event ->
                event.stage?.let { stage -> runCatching { StageName.valueOf(stage) }.getOrNull() }
            }
            .maxByOrNull(StageName::ordinal)
    if (furthestStage == null) return 0.01

    return ((furthestStage.ordinal + 1.0) / (StageName.values().size + 1.0)).coerceIn(0.05, 0.95)
}
