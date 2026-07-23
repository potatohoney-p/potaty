/*
 * Copyright (c) 2026, Potaty
 */

package com.potaty.backend.github

import com.potaty.backend.api.ApiError
import com.potaty.backend.auth.Permission
import com.potaty.backend.auth.Rbac
import com.potaty.backend.auth.tenant
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
data class GitHubConnectStartResponse(
    val installationUrl: String,
    val expiresAt: String
)

@Serializable
data class GitHubConnectionResponse(
    val connectionId: String,
    val accountLogin: String,
    val accountType: String,
    val connectedByGitHubLogin: String,
    val connectedAt: String
)

@Serializable data class GitHubConnectionsResponse(val connections: List<GitHubConnectionResponse>)

fun Route.githubConnectionRoutes(service: GitHubConnectionService?) {
    post("/github/connections/start") {
        val tenant = call.tenant()
        Rbac.require(tenant, Permission.MANAGE_CREDENTIALS)
        val connector = service ?: return@post notConfigured(call)
        try {
            val start = connector.start(tenant)
            call.respond(
                GitHubConnectStartResponse(
                    installationUrl = start.installationUrl,
                    expiresAt = start.expiresAt.toString()
                )
            )
        } catch (cause: GitHubConnectionException) {
            call.respondGitHubConnectionError(cause)
        }
    }

    // GitHub redirects to the setup URL without Potaty authorization headers. Signed one-time
    // state is the sole authority here; installation_id remains an untrusted candidate.
    get("/github/setup") {
        val connector = service ?: return@get notConfigured(call)
        val state =
            call.request.queryParameters["state"]
                ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("invalid_state", "Missing GitHub state")
                )
        val candidate =
            call.request.queryParameters["installation_id"]?.toLongOrNull()
                ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("bad_request", "Missing or invalid GitHub installation candidate")
                )
        try {
            call.respondRedirect(connector.continueFromSetup(state, candidate), permanent = false)
        } catch (cause: GitHubConnectionException) {
            call.respondGitHubConnectionError(cause)
        }
    }

    // OAuth callback is also unauthenticated at HTTP level. Its state is user/workspace-bound,
    // one-time, and followed by a live membership/role re-check before persistence.
    get("/github/oauth/callback") {
        val connector = service ?: return@get notConfigured(call)
        if (call.request.queryParameters["error"] != null) {
            return@get call.respond(
                HttpStatusCode.BadRequest,
                ApiError("github_authorization_denied", "GitHub authorization was not completed")
            )
        }
        val state =
            call.request.queryParameters["state"]
                ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("invalid_state", "Missing GitHub state")
                )
        val code =
            call.request.queryParameters["code"]
                ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("bad_request", "Missing GitHub OAuth code")
                )
        try {
            val connection = connector.completeOAuth(state, code)
            call.respondRedirect(connector.successRedirect(connection.id), permanent = false)
        } catch (cause: GitHubConnectionException) {
            call.respondGitHubConnectionError(cause)
        }
    }

    get("/github/connections") {
        val tenant = call.tenant()
        Rbac.require(tenant, Permission.MANAGE_CREDENTIALS)
        val connector = service ?: return@get notConfigured(call)
        val workspaceId =
            tenant.workspaceId.toUuidOrNull()
                ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("bad_request", "Invalid workspace")
                )
        call.respond(
            GitHubConnectionsResponse(
                connector.list(workspaceId).map { connection ->
                    GitHubConnectionResponse(
                        connectionId = connection.id.toString(),
                        accountLogin = connection.accountLogin,
                        accountType = connection.accountType,
                        connectedByGitHubLogin = connection.githubLogin,
                        connectedAt = connection.connectedAt.toString()
                    )
                }
            )
        )
    }

    delete("/github/connections/{connectionId}") {
        val tenant = call.tenant()
        Rbac.require(tenant, Permission.MANAGE_CREDENTIALS)
        val connector = service ?: return@delete notConfigured(call)
        val workspaceId =
            tenant.workspaceId.toUuidOrNull()
                ?: return@delete call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("bad_request", "Invalid workspace")
                )
        val connectionId =
            call.parameters["connectionId"]?.toUuidOrNull()
                ?: return@delete call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("bad_request", "Invalid connectionId")
                )
        if (connector.disconnect(workspaceId, connectionId) == null) {
            return@delete call.respond(
                HttpStatusCode.NotFound,
                ApiError("not_found", "GitHub connection not found")
            )
        }
        call.respond(HttpStatusCode.NoContent)
    }
}

private suspend fun notConfigured(call: ApplicationCall) {
    call.respond(
        HttpStatusCode.ServiceUnavailable,
        ApiError("not_configured", "GitHub App connection is not configured")
    )
}

internal suspend fun ApplicationCall.respondGitHubConnectionError(
    cause: GitHubConnectionException
) {
    val (status, code) =
        when (cause.kind) {
            GitHubConnectionErrorKind.INVALID_STATE -> HttpStatusCode.BadRequest to "invalid_state"
            GitHubConnectionErrorKind.INVALID_REQUEST -> HttpStatusCode.BadRequest to "bad_request"
            GitHubConnectionErrorKind.NOT_AUTHORIZED ->
                HttpStatusCode.Forbidden to "github_not_authorized"
            GitHubConnectionErrorKind.CONNECTION_REQUIRED ->
                HttpStatusCode.Conflict to "github_connection_required"
            GitHubConnectionErrorKind.NOT_FOUND -> HttpStatusCode.NotFound to "not_found"
            GitHubConnectionErrorKind.CONFLICT ->
                HttpStatusCode.Conflict to "github_connection_conflict"
            GitHubConnectionErrorKind.UPSTREAM -> HttpStatusCode.BadGateway to "github_error"
        }
    val safeMessage =
        when (cause.kind) {
            GitHubConnectionErrorKind.INVALID_STATE ->
                "GitHub connection state is invalid or expired"
            GitHubConnectionErrorKind.INVALID_REQUEST -> "GitHub connection request is invalid"
            GitHubConnectionErrorKind.NOT_AUTHORIZED ->
                "GitHub did not authorize this connection"
            GitHubConnectionErrorKind.CONNECTION_REQUIRED ->
                "Connect a GitHub installation first"
            GitHubConnectionErrorKind.NOT_FOUND -> "GitHub connection was not found"
            GitHubConnectionErrorKind.CONFLICT ->
                "GitHub connection conflicts with existing state"
            GitHubConnectionErrorKind.UPSTREAM ->
                "GitHub connection service is temporarily unavailable"
        }
    respond(status, ApiError(code, safeMessage))
}

internal fun String.toUuidOrNull(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()
