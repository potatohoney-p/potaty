/*
 * Copyright (c) 2026, Potaty
 */

package com.potaty.backend

import com.potaty.backend.auth.TenantContext
import com.potaty.backend.auth.WorkspaceRole
import com.potaty.backend.github.GitHubConfig
import com.potaty.backend.github.GitHubConnectPhase
import com.potaty.backend.github.GitHubConnectStateCodec
import com.potaty.backend.github.GitHubConnectStateException
import com.potaty.backend.github.GitHubConnectionErrorKind
import com.potaty.backend.github.GitHubConnectionException
import com.potaty.backend.github.GitHubConnectionService
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class GitHubConnectionSecurityTest {

    @Test
    fun signedStateRejectsTamperingExpiryAndPhaseConfusion() {
        val secret = "state-secret-" + "x7Qp".repeat(16)
        val issuedAt = Instant.parse("2026-07-16T00:00:00Z")
        val issuer =
            GitHubConnectStateCodec(
                secret = secret,
                ttlSeconds = 300,
                clock = Clock.fixed(issuedAt, ZoneOffset.UTC)
            )
        val workspaceId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val (token, state) = issuer.issue(GitHubConnectPhase.INSTALL, workspaceId, userId)

        assertEquals(state, issuer.verify(token, GitHubConnectPhase.INSTALL))
        assertFailsWith<GitHubConnectStateException> {
            issuer.verify(token, GitHubConnectPhase.OAUTH)
        }

        val payloadIndex = token.indexOf('.') / 2
        val replacement = if (token[payloadIndex] == 'A') 'B' else 'A'
        val tampered = token.replaceRange(payloadIndex, payloadIndex + 1, replacement.toString())
        assertFailsWith<GitHubConnectStateException> {
            issuer.verify(tampered, GitHubConnectPhase.INSTALL)
        }

        val expiredVerifier =
            GitHubConnectStateCodec(
                secret = secret,
                ttlSeconds = 300,
                clock = Clock.fixed(issuedAt.plusSeconds(301), ZoneOffset.UTC)
            )
        assertFailsWith<GitHubConnectStateException> {
            expiredVerifier.verify(token, GitHubConnectPhase.INSTALL)
        }

        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            issuer.pkceChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
        )
    }

    @Test
    fun persistedStateCanBeConsumedOnlyOnce() = runBlocking {
        val graph = AppGraph.create(testConfig())
        try {
            val codec = GitHubConnectStateCodec("S".repeat(64), 300)
            val (_, state) =
                codec.issue(
                    GitHubConnectPhase.OAUTH,
                    UUID.fromString(graph.config.auth.devWorkspaceId),
                    UUID.fromString(graph.config.auth.devUserId),
                    candidateInstallationId = 123
                )
            graph.gitHubConnectStates.create(state, "pkce-verifier")

            val first = graph.gitHubConnectStates.consume(state)
            assertNotNull(first)
            assertEquals(123, first.candidateInstallationId)
            assertEquals("pkce-verifier", first.pkceVerifier)
            assertNull(graph.gitHubConnectStates.consume(state), "state replay must fail closed")
        } finally {
            graph.stop()
        }
    }

    @Test
    fun oauthPersistsOnlyInstallationVerifiedForAuthorizingUser() = runBlocking {
        val fixture = fixture(candidateVisible = true)
        try {
            val start = fixture.service.start(fixture.tenant)
            val installState = requireNotNull(Url(start.installationUrl).parameters["state"])
            val oauthUrl = fixture.service.continueFromSetup(installState, 123)
            val oauthState = requireNotNull(Url(oauthUrl).parameters["state"])

            val connection = fixture.service.completeOAuth(oauthState, "one-time-code")

            assertEquals(123, connection.installationId)
            assertEquals("acme", connection.accountLogin)
            assertEquals("octocat", connection.githubLogin)
            assertEquals(
                listOf(connection.id),
                fixture.service.list(UUID.fromString(fixture.tenant.workspaceId)).map { it.id }
            )
            val replay =
                assertFailsWith<GitHubConnectionException> {
                    fixture.service.completeOAuth(oauthState, "one-time-code")
                }
            assertEquals(GitHubConnectionErrorKind.INVALID_STATE, replay.kind)
            assertEquals(
                1,
                fixture.tokenExchangeCount,
                "a replay must fail before another token exchange"
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun oauthRejectsCandidateMissingFromAuthorizingUsersInstallations() = runBlocking {
        val fixture = fixture(candidateVisible = false)
        try {
            val installState =
                Url(fixture.service.start(fixture.tenant).installationUrl).parameters["state"]!!
            val oauthState =
                Url(fixture.service.continueFromSetup(installState, 123)).parameters["state"]!!

            val error =
                assertFailsWith<GitHubConnectionException> {
                    fixture.service.completeOAuth(oauthState, "one-time-code")
                }

            assertEquals(GitHubConnectionErrorKind.NOT_AUTHORIZED, error.kind)
            assertTrue(fixture.service.list(UUID.fromString(fixture.tenant.workspaceId)).isEmpty())
        } finally {
            fixture.close()
        }
    }

    private fun fixture(candidateVisible: Boolean): Fixture {
        val graph = AppGraph.create(testConfig())
        val config = githubConfig()
        var exchanges = 0
        val client =
            HttpClient(
                MockEngine { request ->
                    when (request.url.encodedPath) {
                        "/login/oauth/access_token" -> {
                            exchanges += 1
                            respondJson("""{"access_token":"gho_ephemeral"}""")
                        }
                        "/user" -> respondJson("""{"id":7,"login":"octocat"}""")
                        "/user/installations" -> {
                            val installationId = if (candidateVisible) 123 else 999
                            respondJson(
                                """
                                {
                                  "total_count": 1,
                                  "installations": [{
                                    "id": $installationId,
                                    "app_id": 42,
                                    "app_slug": "potaty-test",
                                    "html_url": "https://github.test/settings/installations/$installationId",
                                    "account": {"id": 88, "login": "acme", "type": "Organization"}
                                  }]
                                }
                                """.trimIndent()
                            )
                        }
                        else -> respond("not found", HttpStatusCode.NotFound)
                    }
                }
            )
        val service =
            GitHubConnectionService(
                config = config,
                connections = graph.gitHubConnections,
                states = graph.gitHubConnectStates,
                identities = graph.identities,
                httpClient = client
            )
        val tenant =
            TenantContext(
                workspaceId = graph.config.auth.devWorkspaceId,
                userId = graph.config.auth.devUserId,
                role = WorkspaceRole.OWNER
            )
        return Fixture(graph, client, service, tenant) { exchanges }
    }

    private fun githubConfig() =
        GitHubConfig(
            apiBaseUrl = "https://api.github.test",
            appId = "42",
            privateKeyPem = "test-only-key",
            webhookSecret = "test-webhook-secret",
            maxFileBytes = 100_000,
            maxFilesPerIndex = 100,
            appSlug = "potaty-test",
            oauthClientId = "client-id",
            oauthClientSecret = "client-secret-that-is-long-enough",
            oauthCallbackUrl = "https://potaty.test/api/v1/github/oauth/callback",
            publicBaseUrl = "https://potaty.test/settings/integrations",
            connectStateSecret = "connect-state-secret-" + "9aZ!".repeat(12),
            connectStateTtlSeconds = 300,
            webBaseUrl = "https://github.test"
        )

    private fun io.ktor.client.engine.mock.MockRequestHandleScope.respondJson(body: String) =
        respond(
            content = body,
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json")
        )

    private data class Fixture(
        val graph: AppGraph,
        val client: HttpClient,
        val service: GitHubConnectionService,
        val tenant: TenantContext,
        private val exchanges: () -> Int
    ) {
        val tokenExchangeCount: Int
            get() = exchanges()

        fun close() {
            client.close()
            graph.stop()
        }
    }
}
