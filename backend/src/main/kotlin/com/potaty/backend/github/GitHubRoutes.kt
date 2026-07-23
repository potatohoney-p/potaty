/*
 * Copyright (c) 2026, Potaty
 *
 * GitHub routes (plan section 4.2 P1 GitHub input, section 18). Two endpoints:
 *
 *   POST /github/webhook
 *     UNAUTHENTICATED (no tenant): GitHub calls this. We verify the X-Hub-Signature-256 HMAC over
 *     the RAW body and reject replays (X-GitHub-Delivery), then return 204. A bad/missing signature
 *     is 401; a replay is 200 (already processed) so GitHub stops retrying. (Re-index scheduling off
 *     a verified push is intentionally left to a follow-up: the webhook only needs to authenticate
 *     here, and the workspace/installation -> project mapping is configured out of band.)
 *
 *   POST /projects/{projectId}/github/index
 *     TENANT-SCOPED (RBAC CREATE_SOURCE): mints an installation token via GitHubAppService, indexes
 *     the requested owner/repo/ref read-only via GitHubIndexer, and returns the new sourceVersionId.
 *
 * Both handlers are null-safe when GitHub is not configured (503 instead of NPE).
 */

package com.potaty.backend.github

import com.potaty.backend.AppGraph
import com.potaty.backend.api.ApiError
import com.potaty.backend.api.MUTATION_IDEMPOTENCY_KEY_MESSAGE
import com.potaty.backend.api.isValidMutationIdempotencyKey
import com.potaty.backend.auth.Permission
import com.potaty.backend.auth.Rbac
import com.potaty.backend.auth.tenant
import com.potaty.backend.jobs.Idempotency
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.net.URI
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GitHubIndexRequest(
    val owner: String,
    val repo: String,
    @SerialName("ref")
    val ref: String = "main",
    /** Omit only when the workspace has exactly one active verified connection. */
    val connectionId: String? = null,
    /** Rejected compatibility trap: setup installation ids are untrusted and never accepted. */
    val installationId: Long? = null
)

@Serializable
data class GitHubIndexResponse(
    val sourceId: String,
    val sourceVersionId: String,
    val contentHash: String,
    val filesIndexed: Int,
    val filesSkipped: Int,
    val chunkCount: Int,
    val treeTruncated: Boolean
)

/** Index a PUBLIC repo by URL with no GitHub App / credentials (anonymous GitHub REST API). */
@Serializable
data class GitHubIndexUrlRequest(
    @SerialName("repoUrl")
    val repoUrl: String,
    @SerialName("ref")
    val ref: String? = null
)

@Serializable
data class GitHubIndexUrlResponse(
    val sourceId: String,
    val sourceVersionId: String,
    val contentHash: String,
    val filesIndexed: Int,
    val filesSkipped: Int,
    val chunkCount: Int,
    val treeTruncated: Boolean,
    val owner: String,
    val repo: String,
    val ref: String
)

/** Owner/repo/(optional ref) parsed from a pasted GitHub URL or "owner/repo" shorthand. */
internal data class RepoCoords(val owner: String, val repo: String, val ref: String?)

internal fun parseGitHubRepoUrl(input: String): RepoCoords? {
    val candidate = input.trim().takeIf { it.length in 1..MAX_REPO_URL_LENGTH } ?: return null
    val path =
        when {
            candidate.startsWith("git@github.com:", ignoreCase = true) ->
                candidate.substringAfter(':')
            candidate.startsWith("https://", ignoreCase = true) ||
                candidate.startsWith("http://", ignoreCase = true) -> {
                val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
                if (uri.host?.lowercase() !in GITHUB_HOSTS) return null
                if (!uri.userInfo.isNullOrBlank() || uri.port != -1) return null
                uri.path.removePrefix("/")
            }
            candidate.startsWith("github.com/", ignoreCase = true) ->
                candidate.substringAfter('/')
            candidate.startsWith("www.github.com/", ignoreCase = true) ->
                candidate.substringAfter('/')
            "://" in candidate || candidate.startsWith("git@", ignoreCase = true) -> return null
            else -> candidate
        }

    val parts = path.trimEnd('/').split("/").filter { it.isNotBlank() }
    if (parts.size < 2) return null
    val owner = parts[0]
    val repo = parts[1].removeSuffix(".git")
    val ref =
        if (parts.size >= 4 && parts[2] == "tree") {
            parts.drop(3).joinToString("/")
        } else {
            null
        }
    if (!validGitHubOwner(owner) || !validGitHubRepo(repo)) return null
    if (ref != null && !validGitRef(ref)) return null
    return RepoCoords(owner, repo, ref)
}

internal fun validGitHubCoordinates(owner: String, repo: String, ref: String): Boolean =
    validGitHubOwner(owner) && validGitHubRepo(repo) && validGitRef(ref)

private fun validGitHubOwner(value: String): Boolean = GITHUB_OWNER.matches(value)

private fun validGitHubRepo(value: String): Boolean = GITHUB_REPO.matches(value)

private fun validGitRef(value: String): Boolean =
    value.length in 1..MAX_GIT_REF_LENGTH &&
        value.none { it.isISOControl() || it.isWhitespace() || it in GIT_REF_FORBIDDEN_CHARS } &&
        ".." !in value &&
        "@{" !in value &&
        "//" !in value &&
        !value.startsWith('/') &&
        !value.endsWith('/') &&
        !value.endsWith('.') &&
        !value.endsWith(".lock", ignoreCase = true)

private val GITHUB_HOSTS = setOf("github.com", "www.github.com")
private val GITHUB_OWNER = Regex("""^[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?$""")
private val GITHUB_REPO = Regex("""^[A-Za-z0-9._-]{1,100}$""")
private val GIT_REF_FORBIDDEN_CHARS = setOf('~', '^', ':', '?', '*', '[', '\\')
private const val MAX_REPO_URL_LENGTH = 512
private const val MAX_GIT_REF_LENGTH = 512

/**
 * Mounts the GitHub routes. [verifier], [appService] and [indexer] are null when GitHub is not
 * configured (no App id / private key); the handlers then return 503 so the rest of the API still
 * runs out-of-the-box on H2/dev.
 */
fun Route.githubRoutes(graph: AppGraph) {
    // POST /api/v1/github/webhook  (unauthenticated; GitHub-signed)
    post("/github/webhook") {
        val v = graph.webhookVerifier
            ?: return@post call.respond(
                HttpStatusCode.ServiceUnavailable,
                ApiError("not_configured", "GitHub is not configured")
            )

        val signature = call.request.headers["X-Hub-Signature-256"]
        val deliveryId = call.request.headers["X-GitHub-Delivery"]

        if (!v.isDeliveryIdValid(deliveryId)) {
            return@post call.respond(
                HttpStatusCode.BadRequest,
                ApiError("invalid_delivery_id", "missing or invalid GitHub delivery id")
            )
        }
        val rawBody = call.receiveText().toByteArray(Charsets.UTF_8)

        if (!v.isSignatureValid(signature, rawBody)) {
            return@post call.respond(
                HttpStatusCode.Unauthorized,
                ApiError("bad_signature", "invalid webhook signature")
            )
        }
        // First delivery -> 204 No Content; a replay -> 200 OK (acknowledged, not reprocessed).
        if (!v.registerDelivery(deliveryId)) {
            return@post call.respond(HttpStatusCode.OK, GitHubAck("duplicate"))
        }
        call.respond(HttpStatusCode.NoContent)
    }

    // POST /api/v1/projects/{projectId}/github/index  (tenant-scoped)
    route("/projects/{projectId}/github") {
        post("/index") {
            val tenant = call.tenant()
            Rbac.require(tenant, Permission.CREATE_SOURCE)

            val idx = graph.gitHubIndexer
            if (idx == null) {
                return@post call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    ApiError("not_configured", "GitHub indexing is not available")
                )
            }

            val workspaceId = tenant.workspaceId.toUuidOrNull()
                ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("bad_request", "invalid workspace")
                )
            val projectId = call.parameters["projectId"]?.toUuidOrNull()
                ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("bad_request", "missing/invalid projectId")
                )
            val idempotencyKey = call.request.headers["Idempotency-Key"]
                ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("bad_request", "missing Idempotency-Key header")
                )
            if (!isValidMutationIdempotencyKey(idempotencyKey)) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("bad_request", MUTATION_IDEMPOTENCY_KEY_MESSAGE)
                )
            }

            val body = call.receive<GitHubIndexRequest>()
            if (body.installationId != null) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError(
                        "untrusted_installation_id",
                        "Raw installationId is not accepted; use a verified connectionId"
                    )
                )
            }
            if (!validGitHubCoordinates(body.owner, body.repo, body.ref)) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("bad_request", "owner, repo or ref is invalid")
                )
            }

            val createdBy = tenant.userId.toUuidOrNull()
            val requestHash =
                "sha256:" +
                    Idempotency.sha256(
                        graph.json.encodeToString(GitHubIndexRequest.serializer(), body)
                    )
            val stableIngestionKey = "github-app:$idempotencyKey"
            val result = try {
                graph.identities.requireProject(workspaceId, projectId)
                idx.replay(
                    workspaceId,
                    projectId,
                    stableIngestionKey,
                    requestHash
                )?.let { replay ->
                    return@post call.respond(
                        HttpStatusCode.Created,
                        replay.toGitHubIndexResponse()
                    )
                }
                val app = graph.gitHubAppService
                val connector = graph.gitHubConnectionService
                if (app == null || connector == null) {
                    return@post call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        ApiError("not_configured", "GitHub is not configured")
                    )
                }
                val connection = connector.resolve(workspaceId, body.connectionId)
                idx.index(
                    workspaceId = workspaceId,
                    projectId = projectId,
                    owner = body.owner,
                    repo = body.repo,
                    ref = body.ref,
                    createdBy = createdBy,
                    ingestionKey = stableIngestionKey,
                    requestHash = requestHash,
                    installationTokenProvider = {
                        app.createInstallationToken(connection.installationId)
                    }
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
                result.toGitHubIndexResponse()
            )
        }

        // POST /api/v1/projects/{projectId}/github/index-url
        // Anonymous PUBLIC-repo indexing by URL — no GitHub App / installationId required.
        post("/index-url") {
            val tenant = call.tenant()
            Rbac.require(tenant, Permission.CREATE_SOURCE)

            val idx = graph.gitHubIndexer
                ?: return@post call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    ApiError("not_configured", "GitHub indexing is not available")
                )

            val workspaceId = tenant.workspaceId.toUuidOrNull()
                ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("bad_request", "invalid workspace")
                )
            val projectId = call.parameters["projectId"]?.toUuidOrNull()
                ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("bad_request", "missing/invalid projectId")
                )
            val idempotencyKey = call.request.headers["Idempotency-Key"]
                ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("bad_request", "missing Idempotency-Key header")
                )
            if (!isValidMutationIdempotencyKey(idempotencyKey)) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("bad_request", MUTATION_IDEMPOTENCY_KEY_MESSAGE)
                )
            }

            val body = call.receive<GitHubIndexUrlRequest>()
            val coords = parseGitHubRepoUrl(body.repoUrl)
                ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("bad_request", "enter a valid github.com repository URL")
                )

            val createdBy = tenant.userId.toUuidOrNull()
            val requestHash =
                "sha256:" +
                    Idempotency.sha256(
                        graph.json.encodeToString(GitHubIndexUrlRequest.serializer(), body)
                    )
            val stableIngestionKey = "github-public:$idempotencyKey"
            val result = try {
                graph.identities.requireProject(workspaceId, projectId)
                idx.replay(
                    workspaceId,
                    projectId,
                    stableIngestionKey,
                    requestHash
                )?.let { replay ->
                    return@post call.respond(
                        HttpStatusCode.Created,
                        replay.toGitHubIndexUrlResponse()
                    )
                }
                val ref = body.ref?.takeIf { it.isNotBlank() } ?: coords.ref
                if (ref != null && !validGitRef(ref)) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ApiError("bad_request", "repository ref is invalid")
                    )
                }
                idx.index(
                    workspaceId = workspaceId,
                    projectId = projectId,
                    owner = coords.owner,
                    repo = coords.repo,
                    ref = ref,
                    createdBy = createdBy,
                    ingestionKey = stableIngestionKey,
                    requestHash = requestHash
                )
            } catch (e: GitHubTokenException) {
                return@post call.respond(
                    HttpStatusCode.BadGateway,
                    ApiError(
                        "github_error",
                        e.message ?: "GitHub request failed (repo may be private or rate-limited)"
                    )
                )
            }

            call.respond(
                HttpStatusCode.Created,
                result.toGitHubIndexUrlResponse()
            )
        }
    }
}

@Serializable
private data class GitHubAck(val status: String)

private fun IndexResult.toGitHubIndexResponse() =
    GitHubIndexResponse(
        sourceId = sourceId.toString(),
        sourceVersionId = sourceVersionId.toString(),
        contentHash = contentHash,
        filesIndexed = filesIndexed,
        filesSkipped = filesSkipped,
        chunkCount = chunkCount,
        treeTruncated = treeTruncated
    )

private fun IndexResult.toGitHubIndexUrlResponse() =
    GitHubIndexUrlResponse(
        sourceId = sourceId.toString(),
        sourceVersionId = sourceVersionId.toString(),
        contentHash = contentHash,
        filesIndexed = filesIndexed,
        filesSkipped = filesSkipped,
        chunkCount = chunkCount,
        treeTruncated = treeTruncated,
        owner = owner,
        repo = repo,
        ref = ref
    )
