/*
 * Copyright (c) 2026, Potaty
 *
 * Diagram routes (plan 10.2, 10.4, 10.6). Enqueues generation jobs (idempotent), serves a stored
 * diagram version (re-rendering code formats from the canonical IR), and exports code formats.
 * Natural-language patch (10.5) is not yet wired and returns 501. Every handler is tenant-scoped.
 */

package com.potaty.backend.api

import com.potaty.backend.AppGraph
import com.potaty.backend.auth.Permission
import com.potaty.backend.auth.Rbac
import com.potaty.backend.auth.tenant
import com.potaty.backend.diagram.DiagramPipeline
import com.potaty.backend.ir.ValidationReport
import com.potaty.ir.EvidenceCoverage
import com.potaty.ir.IrJson
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.util.UUID
import kotlinx.serialization.json.jsonObject

private const val MAX_SOURCE_VERSIONS_PER_JOB = 100
private const val MAX_IDEMPOTENCY_KEY_CHARS = 200
private const val MAX_OBJECTIVE_CHARS = 1_000
private const val MAX_SCOPE_TERMS = 50
private const val MAX_SCOPE_TERM_CHARS = 200
private const val MAX_OUTPUT_FORMATS = 8
private val IDEMPOTENCY_KEY_PATTERN = Regex("""^[A-Za-z0-9][A-Za-z0-9._:-]{0,199}$""")
private val ABSTRACTION_LEVELS = setOf("low", "medium", "high")

fun Route.diagramRoutes(graph: AppGraph) {
    // 10.2 — create a diagram-generation job
    route("/projects/{projectId}/diagram-jobs") {
        post {
            val tenant = call.tenant()
            Rbac.require(tenant, Permission.RUN_JOB)

            val workspaceId = tenant.workspaceUuid() ?: return@post badId(call)
            val projectId = call.parameters["projectId"]?.toUuidOrNull() ?: return@post badId(call)
            val idempotencyKey =
                call.request.headers["Idempotency-Key"]
                    ?: return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ApiError("bad_request", "missing Idempotency-Key header")
                    )
            if (
                idempotencyKey.length > MAX_IDEMPOTENCY_KEY_CHARS ||
                !IDEMPOTENCY_KEY_PATTERN.matches(idempotencyKey)
            ) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError(
                        "bad_request",
                        "Idempotency-Key must use 1-$MAX_IDEMPOTENCY_KEY_CHARS " +
                            "letters, digits, period, underscore, colon, or hyphen"
                    )
                )
            }

            val body = call.receive<DiagramJobRequest>()
            if ((body.objective?.length ?: 0) > MAX_OBJECTIVE_CHARS) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError(
                        "bad_request",
                        "objective must contain at most $MAX_OBJECTIVE_CHARS characters"
                    )
                )
            }
            val scopeTerms = body.scope.include + body.scope.exclude
            if (
                body.scope.include.size > MAX_SCOPE_TERMS ||
                body.scope.exclude.size > MAX_SCOPE_TERMS ||
                scopeTerms.any { it.length > MAX_SCOPE_TERM_CHARS }
            ) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError(
                        "bad_request",
                        "scope lists may contain at most $MAX_SCOPE_TERMS terms of " +
                            "$MAX_SCOPE_TERM_CHARS characters each"
                    )
                )
            }
            if (body.scope.abstractionLevel.lowercase() !in ABSTRACTION_LEVELS) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("bad_request", "scope.abstractionLevel must be low, medium, or high")
                )
            }
            if (body.qualityMode != "production" || body.llmProviderPreference != "auto") {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError(
                        "bad_request",
                        "qualityMode=production and llmProviderPreference=auto are the " +
                            "currently supported values"
                    )
                )
            }
            if (body.outputFormats.size > MAX_OUTPUT_FORMATS) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError(
                        "bad_request",
                        "outputFormats must contain at most $MAX_OUTPUT_FORMATS entries"
                    )
                )
            }
            if (body.sourceVersionIds.isEmpty()) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("bad_request", "sourceVersionIds must not be empty")
                )
            }
            if (body.sourceVersionIds.size > MAX_SOURCE_VERSIONS_PER_JOB) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError(
                        "bad_request",
                        "sourceVersionIds must contain at most $MAX_SOURCE_VERSIONS_PER_JOB entries"
                    )
                )
            }
            val sourceVersionIds = mutableListOf<UUID>()
            for (rawId in body.sourceVersionIds) {
                val sourceVersionId =
                    rawId.toUuidOrNull()
                        ?: return@post call.respond(
                            HttpStatusCode.BadRequest,
                            ApiError("bad_request", "every sourceVersionId must be a valid UUID")
                        )
                sourceVersionIds += sourceVersionId
            }
            if (sourceVersionIds.toSet().size != sourceVersionIds.size) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("bad_request", "sourceVersionIds must not contain duplicates")
                )
            }
            val unknownFormats = DiagramPipeline.unsupportedFormats(body.outputFormats)
            if (unknownFormats.isNotEmpty()) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError(
                        "bad_request",
                        "unsupported outputFormats: ${unknownFormats.joinToString(", ")}"
                    )
                )
            }
            graph.identities.requireProject(workspaceId, projectId)
            graph.identities.requireSourceVersionsInProject(
                workspaceId,
                projectId,
                sourceVersionIds
            )

            // Estimate cost from approximate source-chunk tokens, then enforce the workspace quota.
            val sourceTexts = sourceVersionIds.flatMap { svId ->
                graph.sources.listChunks(workspaceId, svId).map { it.text }
            }
            val sourceTokens = com.potaty.backend.cost.CostEstimator.approximateTokens(sourceTexts)
            val estimate = graph.costEstimator.estimate(sourceTokens)
            val inputJson = graph.json.encodeToString(DiagramJobRequest.serializer(), body)
            val reservation =
                graph.quotaGuard.reserve(
                    workspaceId,
                    "diagram:$idempotencyKey",
                    estimate
                ) // throws QuotaExceededException -> 402
            val job =
                try {
                    graph.jobs.enqueue(
                        workspaceId = workspaceId,
                        projectId = projectId,
                        jobType = "diagram_generation",
                        idempotencyKey = idempotencyKey,
                        inputJson = inputJson,
                        createdBy = tenant.userId.toUuidOrNull()
                    ).also { queued ->
                        graph.quotaGuard.attachToJob(workspaceId, reservation.id, queued.id)
                    }
                } catch (cause: Throwable) {
                    // A duplicate caller does not own an existing active reservation and must not
                    // release it when its mismatched idempotency request fails.
                    if (reservation.acquired) {
                        graph.quotaGuard.release(workspaceId, reservation.id)
                    }
                    throw cause
                }

            call.respond(
                HttpStatusCode.Accepted,
                DiagramJobResponse(
                    jobId = job.id.toString(),
                    status = job.status,
                    estimatedCostRange =
                    CostRange(
                        lowUsd = estimate.lowUsd,
                        highUsd = estimate.highUsd
                    )
                )
            )
        }
    }

    route("/diagrams/{diagramId}") {
        // 10.4 — get a diagram version (re-renders code formats from the stored IR)
        get("/versions/{versionId}") {
            val tenant = call.tenant()
            Rbac.require(tenant, Permission.VIEW_DIAGRAM)

            val workspaceId = tenant.workspaceUuid() ?: return@get badId(call)
            val diagramId = call.parameters["diagramId"]?.toUuidOrNull() ?: return@get badId(call)
            val versionId = call.parameters["versionId"]?.toUuidOrNull() ?: return@get badId(call)

            val version =
                graph.diagrams.findVersion(workspaceId, diagramId, versionId)
                    ?: return@get call.respond(
                        HttpStatusCode.NotFound,
                        ApiError("not_found", "diagram version not found")
                    )

            val irElement = graph.json.parseToJsonElement(version.irJson).jsonObject
            val validation =
                graph.json.decodeFromString(
                    ValidationReport.serializer(),
                    version.validationReportJson
                )
            val coverage =
                graph.json.decodeFromString(
                    EvidenceCoverage.serializer(),
                    version.evidenceCoverageJson
                )
            val storedRenderings = graph.diagrams.listRenderings(workspaceId, versionId)
            val renderings =
                if (storedRenderings.isNotEmpty()) {
                    storedRenderings.map {
                        RenderingDto(format = it.format, contentText = it.contentText)
                    }
                } else {
                    // Backward compatibility for versions created before rendering persistence.
                    graph.diagramPipeline.compileRenderings(
                        IrJson.decode(version.irJson),
                        listOf("mermaid")
                    )
                }
            val diagram = graph.diagrams.findDiagram(workspaceId, diagramId)

            call.respond(
                DiagramVersionResponse(
                    diagramId = diagramId.toString(),
                    versionId = versionId.toString(),
                    status = diagram?.status ?: "needs_review",
                    ir = irElement,
                    validationReport = validation,
                    evidenceCoverage =
                    EvidenceCoverageDto(
                        nodeCoverage = coverage.nodeCoverage,
                        edgeCoverage = coverage.edgeCoverage,
                        unsupportedCriticalClaims = coverage.unsupportedCriticalClaims
                    ),
                    renderings = renderings
                )
            )
        }

        // 10.5 — natural-language patch: not yet wired (IrPatcher + critic integration pending).
        post("/versions/{versionId}/patch") {
            val tenant = call.tenant()
            Rbac.require(tenant, Permission.EDIT_DIAGRAM)
            call.respond(
                HttpStatusCode.NotImplemented,
                ApiError("not_implemented", "natural-language patch is not yet available")
            )
        }

        // 10.6 — export: compile requested code formats from the stored IR
        post("/versions/{versionId}/exports") {
            val tenant = call.tenant()
            Rbac.require(tenant, Permission.VIEW_DIAGRAM)

            val workspaceId = tenant.workspaceUuid() ?: return@post badId(call)
            val diagramId = call.parameters["diagramId"]?.toUuidOrNull() ?: return@post badId(call)
            val versionId = call.parameters["versionId"]?.toUuidOrNull() ?: return@post badId(call)
            val body = call.receive<ExportRequest>()
            val unsupportedFormats = DiagramPipeline.unsupportedFormats(body.formats)
            if (unsupportedFormats.isNotEmpty()) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError(
                        "bad_request",
                        "unsupported outputFormats: ${unsupportedFormats.joinToString(", ")}"
                    )
                )
            }

            val version =
                graph.diagrams.findVersion(workspaceId, diagramId, versionId)
                    ?: return@post call.respond(
                        HttpStatusCode.NotFound,
                        ApiError("not_found", "diagram version not found")
                    )

            val ir = IrJson.decode(version.irJson)
            val exports = graph.diagramPipeline.compileRenderings(ir, body.formats)
            call.respond(ExportResponse(exports = exports))
        }
    }
}

private fun com.potaty.backend.auth.TenantContext.workspaceUuid(): UUID? =
    workspaceId.toUuidOrNull()

private fun String.toUuidOrNull(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()

private suspend fun badId(call: ApplicationCall) =
    call.respond(HttpStatusCode.BadRequest, ApiError("bad_request", "missing or invalid path id"))
