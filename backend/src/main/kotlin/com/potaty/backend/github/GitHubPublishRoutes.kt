/*
 * Copyright (c) 2026, Potaty
 *
 * GitHub PR-publish route (WS11; plan 18.6, 4.2 "PR publish"):
 *
 *   POST /api/v1/diagrams/{diagramId}/versions/{versionId}/github/pr
 *     TENANT-SCOPED (RBAC PUBLISH_PR). Loads the stored, validated IR for the version, compiles a
 *     `docs/diagram.md` (Markdown + embedded Mermaid via the codegen renderers), mints an installation
 *     token, and opens a pull request whose body carries the AI-generated disclosure, the evidence/
 *     validation summary and a human review checklist. NEVER merges.
 *
 * Like the other GitHub routes, the handler is null-safe when GitHub is not configured (503) so the
 * rest of the API still runs on H2/dev without GitHub credentials.
 */

package com.potaty.backend.github

import com.potaty.backend.AppGraph
import com.potaty.backend.api.ApiError
import com.potaty.backend.auth.Permission
import com.potaty.backend.auth.Rbac
import com.potaty.backend.auth.tenant
import com.potaty.backend.ir.Severity
import com.potaty.backend.ir.ValidationReport
import com.potaty.backend.ir.toApiDto
import com.potaty.codegen.CodegenFacade
import com.potaty.codegen.CodegenFormat
import com.potaty.ir.EvidenceCoverage
import com.potaty.ir.EvidenceCoverageScorer
import com.potaty.ir.IrJson
import com.potaty.ir.IrValidator
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GitHubPrRequest(
    val owner: String,
    val repo: String,
    /** Omit only when the workspace has exactly one active verified connection. */
    val connectionId: String? = null,
    /** Rejected compatibility trap: setup installation ids are untrusted and never accepted. */
    val installationId: Long? = null,
    /** Target branch the PR merges INTO. */
    @SerialName("baseBranch") val baseBranch: String = "main",
    /**
     * The branch to create and push to. Defaults to a deterministic, version-scoped name so repeat
     * publishes of the SAME version collide on the branch (and GitHub rejects the duplicate ref)
     * rather than spamming branches.
     */
    val branch: String? = null,
    /** Repository path of the Markdown doc to add/update. */
    @SerialName("docPath") val docPath: String? = null,
    val title: String? = null
)

@Serializable
data class GitHubPrResponse(
    val number: Int,
    @SerialName("htmlUrl") val htmlUrl: String,
    val branch: String,
    val state: String,
    @SerialName("docPath") val docPath: String
)

internal fun publishBlockReason(
    report: ValidationReport,
    coverage: EvidenceCoverage
): String? {
    if (report.valid && coverage.meetsThreshold()) return null
    val reasons = buildList {
        if (!report.valid) add("validation failed")
        if (!coverage.meetsThreshold()) {
            add(
                "evidence coverage below threshold " +
                    "(nodes=${"%.1f".format(coverage.nodeCoverage * 100)}%, " +
                    "edges=${"%.1f".format(coverage.edgeCoverage * 100)}%)"
            )
        }
    }
    return "diagram is not eligible for publication: ${reasons.joinToString("; ")}"
}

/**
 * Mounts the PR-publish route. [publisher] and [graph.gitHubAppService] are null when GitHub is not
 * configured; the handler then returns 503. [graph] gives the route the stored IR (graph.diagrams)
 * and the codegen renderers used to compile the published document.
 */
fun Route.gitHubPublishRoutes(graph: AppGraph, publisher: GitHubPublisher?) {
    route("/diagrams/{diagramId}/versions/{versionId}/github") {
        post("/pr") {
            val tenant = call.tenant()
            Rbac.require(tenant, Permission.PUBLISH_PR)

            val app = graph.gitHubAppService
            val pub = publisher
            if (app == null || pub == null) {
                return@post call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    ApiError("not_configured", "GitHub is not configured")
                )
            }

            val workspaceId =
                tenant.workspaceId.toUuidOrNull()
                    ?: return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ApiError("bad_request", "invalid workspace")
                    )
            val diagramId =
                call.parameters["diagramId"]?.toUuidOrNull()
                    ?: return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ApiError("bad_request", "missing/invalid diagramId")
                    )
            val versionId =
                call.parameters["versionId"]?.toUuidOrNull()
                    ?: return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ApiError("bad_request", "missing/invalid versionId")
                    )

            val body = call.receive<GitHubPrRequest>()
            if (body.installationId != null) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError(
                        "untrusted_installation_id",
                        "Raw installationId is not accepted; use a verified connectionId"
                    )
                )
            }
            if (body.owner.isBlank() || body.repo.isBlank() || body.baseBranch.isBlank()) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("bad_request", "owner, repo and baseBranch are required")
                )
            }

            val version =
                graph.diagrams.findVersion(workspaceId, diagramId, versionId)
                    ?: return@post call.respond(
                        HttpStatusCode.NotFound,
                        ApiError("not_found", "diagram version not found")
                    )

            // Decode the canonical IR and recompute the evidence/validation figures from it (the
            // stored figures are display DTOs; the scorer is the single source of truth).
            val ir = IrJson.decode(version.irJson)
            val coverage: EvidenceCoverage = EvidenceCoverageScorer.score(ir)
            val report: ValidationReport = IrValidator().validate(ir).toApiDto()

            // Publishing is an external side effect, so fail closed before compiling files,
            // minting an installation token, or contacting GitHub.
            val blockReason = publishBlockReason(report, coverage)
            if (blockReason != null) {
                return@post call.respond(
                    HttpStatusCode.UnprocessableEntity,
                    ApiError(
                        error = "publish_blocked",
                        message = blockReason
                    )
                )
            }

            // The published document: Markdown (which already embeds the diagram as Mermaid + an
            // AI-generated disclosure + evidence summary) compiled from the stored IR.
            val markdown = CodegenFacade.compile(ir, CodegenFormat.MARKDOWN)
            val mermaid = CodegenFacade.compile(ir, CodegenFormat.MERMAID)

            val docPath =
                (
                    body.docPath?.takeIf {
                        it.isNotBlank()
                    } ?: defaultDocPath(diagramId)
                    )
                    .removePrefix("/")
            val branch = body.branch?.takeIf { it.isNotBlank() } ?: "potaty/diagram-$versionId"
            val title =
                body.title?.takeIf { it.isNotBlank() } ?: "docs: add ${ir.title} diagram (Potaty)"

            val evidence =
                PublishEvidence(
                    nodeCount = ir.nodes.size,
                    edgeCount = ir.edges.size,
                    nodeCoverage = coverage.nodeCoverage,
                    edgeCoverage = coverage.edgeCoverage,
                    groundedEdgeRatio = coverage.groundedEdgeRatio,
                    inferredEdgeCount = coverage.inferredEdgeCount,
                    unsupportedCriticalClaims = coverage.unsupportedCriticalClaims,
                    validationValid = report.valid,
                    validationErrorCount =
                    report.violations.count { it.severity == Severity.ERROR },
                    validationWarningCount =
                    report.warnings.size +
                        report.violations.count {
                            it.severity == Severity.WARNING
                        },
                    meetsPublishThreshold = coverage.meetsThreshold(),
                    sourceSummary =
                    ir.sourceSnapshotIds.takeIf { it.isNotEmpty() }?.joinToString(", ")
                )

            val diagramSummary = buildString {
                append("Adds `").append(docPath).append("` describing **")
                append(ir.title).append("** (")
                append(ir.diagramType.name.lowercase()).append(").")
                // Compact one-line diagram preview so the PR is reviewable even before render.
                appendLine().appendLine()
                append("```mermaid\n").append(mermaid).append("\n```")
            }

            val result =
                try {
                    val connector =
                        graph.gitHubConnectionService
                            ?: return@post call.respond(
                                HttpStatusCode.ServiceUnavailable,
                                ApiError(
                                    "not_configured",
                                    "GitHub App connection is not configured"
                                )
                            )
                    val connection = connector.resolve(workspaceId, body.connectionId)
                    pub.publishPullRequest(
                        installationToken = app.createInstallationToken(connection.installationId),
                        owner = body.owner,
                        repo = body.repo,
                        baseBranch = body.baseBranch,
                        branch = branch,
                        title = title,
                        commitMessage = "docs: add ${ir.title} diagram (generated by Potaty)",
                        diagramSummary = diagramSummary,
                        files = listOf(PublishFile(path = docPath, content = markdown)),
                        evidence = evidence
                    )
                } catch (e: GitHubTokenException) {
                    return@post call.respond(
                        HttpStatusCode.BadGateway,
                        ApiError("github_error", e.message ?: "GitHub request failed")
                    )
                } catch (e: GitHubConnectionException) {
                    return@post call.respondGitHubConnectionError(e)
                }

            call.respond(
                HttpStatusCode.Created,
                GitHubPrResponse(
                    number = result.number,
                    htmlUrl = result.htmlUrl,
                    branch = result.branch,
                    state = result.state,
                    docPath = docPath
                )
            )
        }
    }
}

private fun defaultDocPath(diagramId: UUID): String = "docs/diagrams/$diagramId.md"
