/*
 * Copyright (c) 2026, Potaty
 */

package com.potaty.backend

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.potaty.backend.auth.JwtSessionStore
import com.potaty.backend.auth.TenantContext
import com.potaty.backend.auth.WorkspaceRole
import com.potaty.backend.config.AppConfig
import com.potaty.backend.config.AuthConfig
import com.potaty.backend.config.AuthMode
import com.potaty.backend.config.EnvConfig
import java.time.Instant
import java.util.Date
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AuthHardeningTest {
    private val secret = "a-production-grade-test-secret-32-bytes-long"
    private val auth = AuthConfig(
        mode = AuthMode.JWT,
        devToken = "unused-dev-token",
        devWorkspaceId = "00000000-0000-0000-0000-000000000001",
        devUserId = "00000000-0000-0000-0000-000000000002",
        devProjectId = "00000000-0000-0000-0000-000000000010",
        jwtIssuer = "https://issuer.potaty.test",
        jwtAudience = "potaty-api",
        jwtSecret = secret,
        jwtTtlSeconds = 3_600
    )
    private val context = TenantContext(
        workspaceId = "00000000-0000-0000-0000-000000000001",
        userId = "00000000-0000-0000-0000-000000000002",
        role = WorkspaceRole.EDITOR
    )

    @Test
    fun signedJwtRoundTripsAndRevokes() = kotlinx.coroutines.runBlocking {
        val store = JwtSessionStore(auth)
        val token = store.issue(context)

        assertEquals(context, store.resolve(token))
        store.revoke(token)
        assertNull(store.resolve(token))
    }

    @Test
    fun rejectsTamperingMissingExpiryAndExcessiveLifetime() = kotlinx.coroutines.runBlocking {
        val store = JwtSessionStore(auth)
        val valid = store.issue(context)
        val tampered = valid.dropLast(1) + if (valid.last() == 'a') "b" else "a"
        assertNull(store.resolve(tampered))

        val now = Instant.now()
        val noExpiry = JWT.create()
            .withIssuer(auth.jwtIssuer)
            .withAudience(auth.jwtAudience)
            .withSubject(context.userId)
            .withJWTId(UUID.randomUUID().toString())
            .withIssuedAt(Date.from(now))
            .withClaim("workspace_id", context.workspaceId)
            .withClaim("role", "editor")
            .sign(Algorithm.HMAC256(secret))
        assertNull(store.resolve(noExpiry))

        val excessive = JWT.create()
            .withIssuer(auth.jwtIssuer)
            .withAudience(auth.jwtAudience)
            .withSubject(context.userId)
            .withJWTId(UUID.randomUUID().toString())
            .withIssuedAt(Date.from(now))
            .withExpiresAt(Date.from(now.plusSeconds(auth.jwtTtlSeconds.toLong() * 2)))
            .withClaim("workspace_id", context.workspaceId)
            .withClaim("role", "editor")
            .sign(Algorithm.HMAC256(secret))
        assertNull(store.resolve(excessive))
    }

    @Test
    fun developmentBearerAuthMustBeExplicitAndProductionRejectsIt() {
        assertFailsWith<IllegalArgumentException> {
            AppConfig.fromEnv(EnvConfig.of(emptyMap()))
        }

        assertFailsWith<IllegalArgumentException> {
            AppConfig.fromEnv(
                EnvConfig.of(
                    mapOf(
                        "POTATY_ENV" to "production",
                        "POTATY_AUTH_MODE" to "dev",
                        "POTATY_DEV_TOKEN" to "explicit-long-development-token",
                        "POTATY_DB_MODE" to "postgres"
                    )
                )
            )
        }
    }

    @Test
    fun hardenedProductionJwtConfigurationIsAcceptedWithoutInProcessMigrations() {
        val config = AppConfig.fromEnv(
            EnvConfig.of(
                mapOf(
                    "POTATY_ENV" to "production",
                    "POTATY_AUTH_MODE" to "jwt",
                    "POTATY_JWT_ISSUER" to auth.jwtIssuer,
                    "POTATY_JWT_AUDIENCE" to auth.jwtAudience,
                    "POTATY_JWT_SECRET" to secret,
                    "POTATY_DB_MODE" to "postgres",
                    "POTATY_DB_URL" to "jdbc:postgresql://db:5432/potaty",
                    "POTATY_DB_USER" to "potaty_app",
                    "POTATY_DB_PASSWORD" to "non-default-database-password",
                    "POTATY_DB_MIGRATE" to "false",
                    "POTATY_CREDENTIAL_KMS_KEY" to "kms://potaty-production",
                    "POTATY_CORS_ORIGINS" to "https://potaty.example"
                )
            )
        )

        assertEquals(AuthMode.JWT, config.auth.mode)
        assertEquals(false, config.database.runFlywayMigrations)
    }

    @Test
    fun corsOriginsRejectPathsQueriesFragmentsAndInvalidPorts() {
        listOf(
            "https://potaty.example/path",
            "https://potaty.example?debug=true",
            "https://potaty.example#fragment",
            "https://potaty.example:70000"
        ).forEach { origin ->
            assertFailsWith<IllegalArgumentException>(origin) {
                AppConfig.fromEnv(
                    EnvConfig.of(
                        mapOf(
                            "POTATY_AUTH_MODE" to "dev",
                            "POTATY_DEV_TOKEN" to "explicit-long-development-token",
                            "POTATY_CORS_ORIGINS" to origin
                        )
                    )
                )
            }
        }
    }

    @Test
    fun publicGitHubBaseUrlIsValidatedWithoutPrivateAppCredentials() {
        assertFailsWith<IllegalArgumentException> {
            AppConfig.fromEnv(
                EnvConfig.of(
                    mapOf(
                        "POTATY_ENV" to "production",
                        "POTATY_AUTH_MODE" to "jwt",
                        "POTATY_JWT_ISSUER" to auth.jwtIssuer,
                        "POTATY_JWT_AUDIENCE" to auth.jwtAudience,
                        "POTATY_JWT_SECRET" to secret,
                        "POTATY_DB_MODE" to "postgres",
                        "POTATY_DB_PASSWORD" to "non-default-database-password",
                        "POTATY_CREDENTIAL_KMS_KEY" to "kms://potaty-production",
                        "POTATY_CORS_ORIGINS" to "https://potaty.example",
                        "GITHUB_API_BASE_URL" to "http://api.github.example"
                    )
                )
            )
        }
    }

    @Test
    fun providerBaseUrlsRejectCredentialsPathsAndProductionHttp() {
        listOf(
            "https://user:secret@api.openai.test",
            "https://api.openai.test/v1",
            "https://api.openai.test?debug=true",
            "https://api.openai.test#fragment"
        ).forEach { baseUrl ->
            assertFailsWith<IllegalArgumentException>(baseUrl) {
                AppConfig.fromEnv(
                    EnvConfig.of(
                        mapOf(
                            "POTATY_AUTH_MODE" to "dev",
                            "POTATY_DEV_TOKEN" to "explicit-long-development-token",
                            "OPENAI_BASE_URL" to baseUrl
                        )
                    )
                )
            }
        }

        assertFailsWith<IllegalArgumentException> {
            AppConfig.fromEnv(
                EnvConfig.of(
                    mapOf(
                        "POTATY_ENV" to "production",
                        "POTATY_AUTH_MODE" to "jwt",
                        "POTATY_JWT_ISSUER" to auth.jwtIssuer,
                        "POTATY_JWT_AUDIENCE" to auth.jwtAudience,
                        "POTATY_JWT_SECRET" to secret,
                        "POTATY_DB_MODE" to "postgres",
                        "POTATY_DB_PASSWORD" to "non-default-database-password",
                        "POTATY_CREDENTIAL_KMS_KEY" to "kms://potaty-production",
                        "POTATY_CORS_ORIGINS" to "https://potaty.example",
                        "OPENAI_BASE_URL" to "http://api.openai.test"
                    )
                )
            )
        }
    }
}
