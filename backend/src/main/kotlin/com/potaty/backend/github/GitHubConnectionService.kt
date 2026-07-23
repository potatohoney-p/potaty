/*
 * Copyright (c) 2026, Potaty
 *
 * GitHub App install -> setup -> OAuth verification flow. The setup installation_id is only a
 * candidate until GitHub confirms it appears in the installing user's /user/installations list.
 */

@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.potaty.backend.github

import com.potaty.backend.auth.Permission
import com.potaty.backend.auth.Rbac
import com.potaty.backend.auth.TenantContext
import com.potaty.backend.persistence.IdentityRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.http.encodedPath
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class GitHubConnectionErrorKind {
    INVALID_STATE,
    INVALID_REQUEST,
    NOT_AUTHORIZED,
    CONNECTION_REQUIRED,
    NOT_FOUND,
    CONFLICT,
    UPSTREAM
}

class GitHubConnectionException(
    val kind: GitHubConnectionErrorKind,
    message: String
) : RuntimeException(message)

data class GitHubConnectStart(
    val installationUrl: String,
    val expiresAt: Instant
)

class GitHubConnectionService(
    private val config: GitHubConfig,
    private val connections: GitHubConnectionRepository,
    private val states: GitHubConnectStateRepository,
    private val identities: IdentityRepository,
    private val httpClient: HttpClient,
    private val stateCodec: GitHubConnectStateCodec =
        GitHubConnectStateCodec(
            config.connectStateSecret,
            config.connectStateTtlSeconds
        )
) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
    private val expectedAppId =
        config.appId.toLongOrNull()
            ?: throw IllegalArgumentException("GitHub app id must be numeric")

    init {
        require(config.connectEnabled) {
            "GitHub connection service requires complete GitHub App OAuth configuration"
        }
    }

    suspend fun start(tenant: TenantContext): GitHubConnectStart {
        val workspaceId =
            tenant.workspaceId.toUuidOrNull() ?: invalidRequest("Invalid workspace identity")
        val userId = tenant.userId.toUuidOrNull() ?: invalidRequest("Invalid user identity")
        requireCurrentManager(workspaceId, userId)
        val (token, state) = stateCodec.issue(GitHubConnectPhase.INSTALL, workspaceId, userId)
        states.create(state)
        val url =
            URLBuilder(config.webBaseUrl)
                .apply {
                    encodedPath =
                        encodedPath.trimEnd('/') + "/apps/${config.appSlug}/installations/new"
                    parameters.append("state", token)
                }
                .buildString()
        return GitHubConnectStart(url, Instant.ofEpochSecond(state.expiresAtEpochSeconds))
    }

    suspend fun continueFromSetup(stateToken: String, candidateInstallationId: Long): String {
        if (candidateInstallationId <= 0) invalidRequest("Invalid GitHub installation candidate")
        val installState = verifyState(stateToken, GitHubConnectPhase.INSTALL)
        if (states.consume(installState) == null) throw invalidState()

        val workspaceId = UUID.fromString(installState.workspaceId)
        val userId = UUID.fromString(installState.userId)
        requireCurrentManager(workspaceId, userId)
        val verifier = stateCodec.randomPkceVerifier()
        val (oauthToken, oauthState) =
            stateCodec.issue(
                GitHubConnectPhase.OAUTH,
                workspaceId,
                userId,
                candidateInstallationId
            )
        states.create(oauthState, verifier)
        return URLBuilder(config.webBaseUrl)
            .apply {
                encodedPath = encodedPath.trimEnd('/') + "/login/oauth/authorize"
                parameters.append("client_id", config.oauthClientId)
                parameters.append("redirect_uri", config.oauthCallbackUrl)
                parameters.append("state", oauthToken)
                parameters.append("code_challenge", stateCodec.pkceChallenge(verifier))
                parameters.append("code_challenge_method", "S256")
                parameters.append("prompt", "select_account")
            }
            .buildString()
    }

    suspend fun completeOAuth(stateToken: String, code: String): GitHubConnectionRecord {
        if (code.isBlank() || code.length > MAX_OAUTH_CODE_LENGTH) {
            invalidRequest("Invalid GitHub OAuth code")
        }
        val oauthState = verifyState(stateToken, GitHubConnectPhase.OAUTH)
        val attempt = states.consume(oauthState) ?: throw invalidState()
        val candidate = attempt.candidateInstallationId ?: throw invalidState()
        val verifier = attempt.pkceVerifier ?: throw invalidState()
        requireCurrentManager(attempt.workspaceId, attempt.userId)

        // The short-lived user token exists only in this stack frame and is never logged or stored.
        val userToken = exchangeCode(code, verifier)
        val user = fetchUser(userToken)
        val installation =
            findVerifiedInstallation(userToken, candidate)
                ?: throw GitHubConnectionException(
                    GitHubConnectionErrorKind.NOT_AUTHORIZED,
                    "GitHub did not confirm that this installation belongs " +
                        "to the authorizing user and app"
                )
        return try {
            connections.saveVerified(
                workspaceId = attempt.workspaceId,
                connectedByUserId = attempt.userId,
                verified =
                VerifiedGitHubInstallation(
                    installationId = installation.id,
                    appId = installation.appId,
                    accountId = installation.account.id,
                    accountLogin = installation.account.login,
                    accountType = installation.account.type,
                    installationHtmlUrl = installation.htmlUrl,
                    githubUserId = user.id,
                    githubLogin = user.login
                )
            )
        } catch (cause: GitHubConnectionConflictException) {
            throw GitHubConnectionException(
                GitHubConnectionErrorKind.CONFLICT,
                cause.message ?: "Connection conflict"
            )
        }
    }

    suspend fun list(workspaceId: UUID): List<GitHubConnectionRecord> =
        connections.listActive(workspaceId)

    suspend fun disconnect(workspaceId: UUID, connectionId: UUID): GitHubConnectionRecord? =
        connections.disconnect(workspaceId, connectionId)

    suspend fun resolve(workspaceId: UUID, rawConnectionId: String?): GitHubConnectionRecord {
        if (!rawConnectionId.isNullOrBlank()) {
            val connectionId =
                rawConnectionId.toUuidOrNull() ?: invalidRequest("connectionId must be a UUID")
            return connections.findActive(workspaceId, connectionId)
                ?: throw GitHubConnectionException(
                    GitHubConnectionErrorKind.NOT_FOUND,
                    "GitHub connection not found"
                )
        }
        val active = connections.listActive(workspaceId)
        return when (active.size) {
            1 -> active.single()
            0 ->
                throw GitHubConnectionException(
                    GitHubConnectionErrorKind.CONNECTION_REQUIRED,
                    "Connect a GitHub App installation before using a private repository"
                )
            else ->
                throw GitHubConnectionException(
                    GitHubConnectionErrorKind.INVALID_REQUEST,
                    "Multiple GitHub connections are available; connectionId is required"
                )
        }
    }

    fun successRedirect(connectionId: UUID): String =
        URLBuilder(config.publicBaseUrl)
            .apply {
                parameters.append("github", "connected")
                parameters.append("connectionId", connectionId.toString())
            }
            .buildString()

    fun failureRedirect(code: String): String {
        require(code in ALLOWED_FAILURE_CODES) { "Unsupported GitHub connect result code" }
        return URLBuilder(config.publicBaseUrl)
            .apply {
                parameters.append("github", "error")
                parameters.append("reason", code)
            }
            .buildString()
    }

    private suspend fun requireCurrentManager(workspaceId: UUID, userId: UUID) {
        val role = identities.activeMemberRole(workspaceId, userId)
        if (role == null || !Rbac.has(role, Permission.MANAGE_CREDENTIALS)) {
            throw GitHubConnectionException(
                GitHubConnectionErrorKind.NOT_AUTHORIZED,
                "The initiating user is no longer allowed to manage workspace connections"
            )
        }
    }

    private fun verifyState(token: String, phase: GitHubConnectPhase): GitHubConnectState =
        try {
            stateCodec.verify(token, phase)
        } catch (_: GitHubConnectStateException) {
            throw invalidState()
        }

    private suspend fun exchangeCode(code: String, verifier: String): String {
        val response =
            httpClient.submitForm(
                url = config.webBaseUrl.trimEnd('/') + "/login/oauth/access_token",
                formParameters =
                Parameters.build {
                    append("client_id", config.oauthClientId)
                    append("client_secret", config.oauthClientSecret)
                    append("code", code)
                    append("redirect_uri", config.oauthCallbackUrl)
                    append("code_verifier", verifier)
                }
            ) {
                header(HttpHeaders.Accept, "application/json")
            }
        val body = response.bodyAsText()
        if (response.status.value !in 200..299) throw upstream("GitHub OAuth token exchange failed")
        val token = runCatching {
            json.decodeFromString(GitHubOAuthTokenResponse.serializer(), body)
        }.getOrElse { throw upstream("GitHub OAuth token response was invalid") }
        if (!token.error.isNullOrBlank() || token.accessToken.isBlank()) {
            throw upstream("GitHub OAuth authorization was rejected")
        }
        return token.accessToken
    }

    private suspend fun fetchUser(userToken: String): GitHubOAuthUser {
        val response =
            httpClient.get(config.apiBaseUrl.trimEnd('/') + "/user") {
                githubUserHeaders(userToken)
            }
        if (response.status != HttpStatusCode.OK) throw upstream("GitHub user verification failed")
        val user = runCatching {
            json.decodeFromString(GitHubOAuthUser.serializer(), response.bodyAsText())
        }
            .getOrElse { throw upstream("GitHub user response was invalid") }
        if (user.id <= 0 || user.login.isBlank()) {
            throw upstream("GitHub user response was incomplete")
        }
        return user
    }

    private suspend fun findVerifiedInstallation(
        userToken: String,
        candidateInstallationId: Long
    ): GitHubUserInstallation? {
        for (page in 1..MAX_INSTALLATION_PAGES) {
            val response =
                httpClient.get(config.apiBaseUrl.trimEnd('/') + "/user/installations") {
                    githubUserHeaders(userToken)
                    url {
                        parameters.append("per_page", "100")
                        parameters.append("page", page.toString())
                    }
                }
            if (response.status != HttpStatusCode.OK) {
                throw upstream("GitHub installation ownership verification failed")
            }
            val listing = runCatching {
                json.decodeFromString(
                    GitHubUserInstallationsResponse.serializer(),
                    response.bodyAsText()
                )
            }
                .getOrElse { throw upstream("GitHub installations response was invalid") }
            val match =
                listing.installations.firstOrNull { installation ->
                    installation.id == candidateInstallationId &&
                        installation.appId == expectedAppId &&
                        installation.appSlug == config.appSlug &&
                        installation.account.id > 0 &&
                        installation.account.login.isNotBlank() &&
                        installation.account.type.isNotBlank() &&
                        installation.htmlUrl.startsWith(config.webBaseUrl.trimEnd('/') + "/")
                }
            if (match != null) return match
            if (listing.installations.size < 100 || page * 100 >= listing.totalCount) return null
        }
        return null
    }

    private fun io.ktor.client.request.HttpRequestBuilder.githubUserHeaders(token: String) {
        header(HttpHeaders.Authorization, "Bearer $token")
        header(HttpHeaders.Accept, "application/vnd.github+json")
        header("X-GitHub-Api-Version", "2022-11-28")
    }

    private fun invalidRequest(message: String): Nothing =
        throw GitHubConnectionException(GitHubConnectionErrorKind.INVALID_REQUEST, message)

    private fun invalidState(): GitHubConnectionException =
        GitHubConnectionException(
            GitHubConnectionErrorKind.INVALID_STATE,
            "GitHub connect state is invalid, expired, or already used"
        )

    private fun upstream(message: String): GitHubConnectionException =
        GitHubConnectionException(GitHubConnectionErrorKind.UPSTREAM, message)

    private fun String.toUuidOrNull(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()

    private companion object {
        const val MAX_OAUTH_CODE_LENGTH = 1_024
        const val MAX_INSTALLATION_PAGES = 100
        val ALLOWED_FAILURE_CODES =
            setOf(
                "denied",
                "invalid_state",
                "invalid_request",
                "not_authorized",
                "conflict",
                "upstream"
            )
    }
}

@Serializable
private data class GitHubOAuthTokenResponse(
    @SerialName("access_token") val accessToken: String = "",
    val error: String? = null
)

@Serializable
private data class GitHubOAuthUser(
    val id: Long = 0,
    val login: String = ""
)

@Serializable
private data class GitHubUserInstallationsResponse(
    @SerialName("total_count") val totalCount: Int = 0,
    val installations: List<GitHubUserInstallation> = emptyList()
)

@Serializable
private data class GitHubUserInstallation(
    val id: Long = 0,
    @SerialName("app_id") val appId: Long = 0,
    @SerialName("app_slug") val appSlug: String = "",
    @SerialName("html_url") val htmlUrl: String = "",
    val account: GitHubInstallationAccount = GitHubInstallationAccount()
)

@Serializable
private data class GitHubInstallationAccount(
    val id: Long = 0,
    val login: String = "",
    val type: String = ""
)
