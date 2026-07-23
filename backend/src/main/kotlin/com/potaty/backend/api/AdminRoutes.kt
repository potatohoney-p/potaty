/*
 * Copyright (c) 2026, Potaty
 *
 * Admin diagnostics (plan 22-23 operations). Tenant-scoped and gated on RBAC MANAGE_WORKSPACE
 * (OWNER only by default; see Rbac), mounted under /api/v1:
 *
 *   GET /admin/jobs/{jobId}   — diagnostics for one job (status, attempts, type, sanitized error/
 *                               output) from JobRepository. Tenant-scoped: a job is only visible
 *                               to its own workspace, so admins of workspace A cannot inspect B.
 *   GET /admin/usage          — this workspace's month-to-date estimated spend (UsageRecorder),
 *                               for the cost dashboard.
 *
 * No cross-workspace read path: every query filters by the caller's workspaceId.
 */

package com.potaty.backend.api

import com.potaty.backend.AppGraph
import com.potaty.backend.auth.Permission
import com.potaty.backend.auth.Rbac
import com.potaty.backend.auth.tenant
import com.potaty.backend.usage.ExternalSpendDecision
import com.potaty.backend.usage.MAX_RECONCILIATION_CHARGE_USD
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
data class AdminJobDiagnosticsResponse(
    val jobId: String,
    val workspaceId: String,
    val projectId: String? = null,
    val jobType: String,
    val status: String,
    val attempts: Int,
    val maxAttempts: Int,
    /** True once the worker pool has stored the job's terminal output (diagram/version ids). */
    val hasOutput: Boolean
)

@Serializable
data class AdminUsageResponse(
    val workspaceId: String,
    /** Estimated USD spend since the first instant of the current UTC calendar month. */
    val monthToDateCostUsd: Double
)

@Serializable
data class AdminPendingExternalSpendItem(
    val reservationId: String,
    val amountUsd: Double,
    val operation: String,
    val provider: String,
    val model: String,
    val stage: String,
    val metadataJson: String? = null,
    val startedAt: String,
    /** The processing lease has expired by the time this item appears in the queue. */
    val reconcilableAt: String
)

@Serializable
data class AdminPendingExternalSpendResponse(
    val workspaceId: String,
    val items: List<AdminPendingExternalSpendItem>
)

@Serializable
data class AdminExternalSpendReconcileRequest(
    val decision: String,
    val chargeUsd: Double? = null,
    val reason: String,
    /** Destructive cost decisions require an explicit acknowledgement from the operator UI/API. */
    val confirm: Boolean = false
)

@Serializable
data class AdminExternalSpendReconcileResponse(
    val reservationId: String,
    val decision: String,
    val chargedUsd: Double,
    val reconciledAt: String,
    val reconciledBy: String
)

fun Route.adminRoutes(graph: AppGraph) {
    route("/admin") {
        // GET /api/v1/admin/jobs/{jobId}
        get("/jobs/{jobId}") {
            val tenant = call.tenant()
            Rbac.require(tenant, Permission.MANAGE_WORKSPACE)

            val workspaceId = tenant.workspaceId.toUuidOrNull()
                ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("bad_request", "invalid workspace")
                )
            val jobId = call.parameters["jobId"]?.toUuidOrNull()
                ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("bad_request", "missing/invalid jobId")
                )

            val job = graph.jobs.findById(workspaceId, jobId)
                ?: return@get call.respond(
                    HttpStatusCode.NotFound,
                    ApiError("not_found", "job not found")
                )

            call.respond(
                AdminJobDiagnosticsResponse(
                    jobId = job.id.toString(),
                    workspaceId = job.workspaceId.toString(),
                    projectId = job.projectId?.toString(),
                    jobType = job.jobType,
                    status = job.status,
                    attempts = job.attempts,
                    maxAttempts = job.maxAttempts,
                    hasOutput = job.outputJson != null
                )
            )
        }

        // GET /api/v1/admin/usage
        get("/usage") {
            val tenant = call.tenant()
            Rbac.require(tenant, Permission.MANAGE_WORKSPACE)

            val workspaceId = tenant.workspaceId.toUuidOrNull()
                ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("bad_request", "invalid workspace")
                )

            val cost = graph.usage.sumCostThisMonth(workspaceId)
            call.respond(
                AdminUsageResponse(workspaceId = workspaceId.toString(), monthToDateCostUsd = cost)
            )
        }

        // GET /api/v1/admin/external-spend/pending
        get("/external-spend/pending") {
            val tenant = call.tenant()
            Rbac.require(tenant, Permission.MANAGE_WORKSPACE)
            val workspaceId = tenant.workspaceId.toUuidOrNull()
                ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("bad_request", "invalid workspace")
                )
            val items = graph.quotaGuard.listPendingExternalSpend(workspaceId)
            call.respond(
                AdminPendingExternalSpendResponse(
                    workspaceId = workspaceId.toString(),
                    items =
                    items.map { item ->
                        AdminPendingExternalSpendItem(
                            reservationId = item.id.toString(),
                            amountUsd = item.amountUsd,
                            operation = item.operation,
                            provider = item.provider,
                            model = item.model,
                            stage = item.stage,
                            metadataJson = item.metadataJson,
                            startedAt = item.startedAt.toString(),
                            reconcilableAt = item.reconcilableAt.toString()
                        )
                    }
                )
            )
        }

        // POST /api/v1/admin/external-spend/{reservationId}/reconcile
        post("/external-spend/{reservationId}/reconcile") {
            val tenant = call.tenant()
            Rbac.require(tenant, Permission.MANAGE_WORKSPACE)
            val workspaceId = tenant.workspaceId.toUuidOrNull()
                ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("bad_request", "invalid workspace")
                )
            val actorUserId = tenant.userId.toUuidOrNull()
                ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("bad_request", "invalid user")
                )
            val reservationId = call.parameters["reservationId"]?.toUuidOrNull()
                ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("bad_request", "missing/invalid reservationId")
                )
            val body = call.receive<AdminExternalSpendReconcileRequest>()
            val decision = runCatching {
                ExternalSpendDecision.valueOf(body.decision.trim().uppercase())
            }.getOrNull()
            val safeReason = body.reason.trim()
            val invalidCharge = body.chargeUsd?.let {
                !it.isFinite() || it !in 0.0..MAX_RECONCILIATION_CHARGE_USD
            } ?: false
            if (
                !body.confirm ||
                decision == null ||
                safeReason.length !in 10..500 ||
                safeReason.any(Char::isISOControl) ||
                BIDI_CONTROL_PATTERN.containsMatchIn(safeReason) ||
                invalidCharge ||
                (decision == ExternalSpendDecision.CHARGE && body.chargeUsd == null) ||
                (decision == ExternalSpendDecision.RELEASE && body.chargeUsd != null)
            ) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError(
                        "bad_request",
                        "confirm a charge/release decision with an explicit charge amount " +
                            "and a safe 10-500 character reason"
                    )
                )
            }

            val result =
                graph.usage.reconcileExternalSpend(
                    workspaceId = workspaceId,
                    reservationId = reservationId,
                    actorUserId = actorUserId,
                    decision = decision,
                    chargeUsd = body.chargeUsd,
                    reason = safeReason
                )
            call.respond(
                AdminExternalSpendReconcileResponse(
                    reservationId = result.reservationId.toString(),
                    decision = result.decision.name.lowercase(),
                    chargedUsd = result.chargedUsd,
                    reconciledAt = result.reconciledAt.toString(),
                    reconciledBy = result.reconciledBy.toString()
                )
            )
        }
    }
}

private fun String.toUuidOrNull(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()

private val BIDI_CONTROL_PATTERN = Regex("[\\u202A-\\u202E\\u2066-\\u2069]")
