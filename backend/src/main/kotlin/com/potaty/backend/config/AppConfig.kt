/*
 * Copyright (c) 2026, Potaty
 *
 * Centralized, immutable application configuration loaded from the environment.
 * Model IDs are configuration, never source constants (plan section 3.4).
 */

package com.potaty.backend.config

import com.potaty.backend.github.GitHubConfig

data class AppConfig(
    val environment: RuntimeEnvironment,
    val server: ServerConfig,
    val database: DatabaseConfig,
    val cors: CorsConfig,
    val security: SecurityConfig,
    val auth: AuthConfig,
    val llm: LlmConfig,
    val github: GitHubConfig
) {
    companion object {
        private const val MIN_JWT_SECRET_LENGTH = 32
        private const val MAX_CORS_ORIGIN_LENGTH = 2_048
        private val INSECURE_SECRETS =
            setOf("secret", "password", "change-me", "changeme", "dev-secret")

        fun fromEnv(env: EnvConfig = EnvConfig.system()): AppConfig {
            val environment = RuntimeEnvironment.fromWire(env.string("POTATY_ENV", "development"))
            val explicitDevAuth = env.bool("POTATY_DEV_AUTH", false)
            val authMode =
                AuthMode.fromWire(
                    env.string("POTATY_AUTH_MODE", if (explicitDevAuth) "dev" else "jwt")
                )

            // "h2" is embedded and ephemeral; production is deliberately Postgres-only.
            val dbMode = env.string("POTATY_DB_MODE", "h2").lowercase()
            val config =
                AppConfig(
                    environment = environment,
                    server =
                    ServerConfig(
                        port = env.int("POTATY_PORT", 8080),
                        host = env.string("POTATY_HOST", "0.0.0.0")
                    ),
                    database =
                    DatabaseConfig(
                        mode = dbMode,
                        jdbcUrl =
                        env.string(
                            "POTATY_DB_URL",
                            if (dbMode == "h2") {
                                "jdbc:h2:mem:potaty;DB_CLOSE_DELAY=-1;MODE=PostgreSQL"
                            } else {
                                "jdbc:postgresql://localhost:5432/potaty"
                            }
                        ),
                        username =
                        env.string(
                            "POTATY_DB_USER",
                            if (dbMode == "h2") "sa" else "potaty"
                        ),
                        password =
                        env.string(
                            "POTATY_DB_PASSWORD",
                            if (dbMode == "h2") "" else "potaty"
                        ),
                        maxPoolSize = env.int("POTATY_DB_POOL_SIZE", 10),
                        // Flyway owns the Postgres schema; H2 uses SchemaUtils.create (pg DDL
                        // is pg-only).
                        runFlywayMigrations = env.bool("POTATY_DB_MIGRATE", dbMode != "h2")
                    ),
                    cors =
                    CorsConfig(
                        allowedOrigins =
                        env.list(
                            "POTATY_CORS_ORIGINS",
                            listOf("http://localhost:8080")
                        )
                    ),
                    security =
                    SecurityConfig(
                        // Development envelope-encryption key material. A hosted production
                        // deployment must replace EnvelopeCredentialStore with a real KMS/HSM
                        // implementation; startup validation only rejects known placeholder keys.
                        credentialMasterKeyRef =
                        env.string(
                            "POTATY_CREDENTIAL_KMS_KEY",
                            "local-dev-key"
                        ),
                        // Enables non-sanctioned provider OAuth flows (BYO, self-hosted only).
                        // Off by default.
                        allowProviderOAuth = env.bool("POTATY_ALLOW_PROVIDER_OAUTH", false)
                    ),
                    auth =
                    AuthConfig(
                        mode = authMode,
                        devToken = env.string("POTATY_DEV_TOKEN", "dev-token"),
                        devWorkspaceId =
                        env.string(
                            "POTATY_DEV_WORKSPACE_ID",
                            "00000000-0000-0000-0000-000000000001"
                        ),
                        devUserId =
                        env.string(
                            "POTATY_DEV_USER_ID",
                            "00000000-0000-0000-0000-000000000002"
                        ),
                        devProjectId =
                        env.string(
                            "POTATY_DEV_PROJECT_ID",
                            "00000000-0000-0000-0000-000000000010"
                        ),
                        jwtIssuer = env.string("POTATY_JWT_ISSUER", ""),
                        jwtAudience = env.string("POTATY_JWT_AUDIENCE", ""),
                        jwtSecret = env.string("POTATY_JWT_SECRET", ""),
                        jwtTtlSeconds = env.int("POTATY_JWT_TTL_SECONDS", 3_600)
                    ),
                    llm =
                    LlmConfig(
                        // Model routing table (plan 3.4). Keyed by ModelTier x ProviderId.
                        openAiBaseUrl =
                        env.string("OPENAI_BASE_URL", "https://api.openai.com").trimEnd('/'),
                        anthropicBaseUrl =
                        env.string("ANTHROPIC_BASE_URL", "https://api.anthropic.com").trimEnd('/'),
                        routes =
                        ModelRoutes(
                            cheapStructured =
                            ProviderModels(
                                openai =
                                env.string(
                                    "OPENAI_CHEAP_STRUCTURED_MODEL",
                                    "gpt-4o-mini"
                                ),
                                anthropic =
                                env.string(
                                    "ANTHROPIC_CHEAP_STRUCTURED_MODEL",
                                    "claude-3-5-haiku-latest"
                                )
                            ),
                            midStructured =
                            ProviderModels(
                                openai =
                                env.string("OPENAI_MID_STRUCTURED_MODEL", "gpt-4o"),
                                anthropic =
                                env.string(
                                    "ANTHROPIC_MID_STRUCTURED_MODEL",
                                    "claude-3-5-sonnet-latest"
                                )
                            ),
                            highReasoning =
                            ProviderModels(
                                openai =
                                env.string("OPENAI_HIGH_REASONING_MODEL", "o1"),
                                anthropic =
                                env.string(
                                    "ANTHROPIC_HIGH_REASONING_MODEL",
                                    "claude-opus-4-latest"
                                )
                            ),
                            embeddings =
                            ProviderModels(
                                openai =
                                env.string(
                                    "OPENAI_EMBEDDING_MODEL",
                                    "text-embedding-3-small"
                                ),
                                anthropic = null
                            ),
                            transcription =
                            ProviderModels(
                                openai =
                                env.string(
                                    "OPENAI_TRANSCRIPTION_MODEL",
                                    "whisper-1"
                                ),
                                anthropic = null
                            )
                        )
                    ),
                    github =
                    GitHubConfig(
                        apiBaseUrl =
                        env.string("GITHUB_API_BASE_URL", "https://api.github.com"),
                        appId = env.string("GITHUB_APP_ID", ""),
                        privateKeyPem = env.string("GITHUB_APP_PRIVATE_KEY", ""),
                        webhookSecret = env.string("GITHUB_WEBHOOK_SECRET", ""),
                        maxFileBytes = env.int("GITHUB_MAX_FILE_BYTES", 1_000_000).toLong(),
                        maxFilesPerIndex = env.int("GITHUB_MAX_FILES_PER_INDEX", 2000),
                        appSlug = env.string("GITHUB_APP_SLUG", ""),
                        oauthClientId = env.string("GITHUB_OAUTH_CLIENT_ID", ""),
                        oauthClientSecret = env.string("GITHUB_OAUTH_CLIENT_SECRET", ""),
                        oauthCallbackUrl = env.string("GITHUB_OAUTH_CALLBACK_URL", ""),
                        publicBaseUrl = env.string("POTATY_PUBLIC_BASE_URL", ""),
                        connectStateSecret = env.string("GITHUB_CONNECT_STATE_SECRET", ""),
                        connectStateTtlSeconds =
                        env.int("GITHUB_CONNECT_STATE_TTL_SECONDS", 900),
                        webBaseUrl = env.string("GITHUB_WEB_BASE_URL", "https://github.com")
                    )
                )
            config.validateForStartup()
            return config
        }
    }

    /**
     * Rejects configurations that would silently start with development credentials or storage in
     * production. This validation runs before a socket is opened or a database connection is made.
     */
    fun validateForStartup() {
        require(database.mode in setOf("h2", "postgres")) {
            "POTATY_DB_MODE must be either 'h2' or 'postgres'"
        }
        require(auth.jwtTtlSeconds in 60..86_400) {
            "POTATY_JWT_TTL_SECONDS must be between 60 and 86400 seconds"
        }
        require(
            cors.allowedOrigins.isNotEmpty() && cors.allowedOrigins.all(::isValidCorsOrigin)
        ) {
            "POTATY_CORS_ORIGINS must contain only scheme://host[:port] origins"
        }

        when (auth.mode) {
            AuthMode.DEV -> {
                require(environment != RuntimeEnvironment.PRODUCTION) {
                    "Development bearer authentication is forbidden in production"
                }
                require(auth.devToken.length >= 12 && auth.devToken != "dev-token") {
                    "Development auth requires an explicit POTATY_DEV_TOKEN " +
                        "of at least 12 characters"
                }
                listOf(auth.devWorkspaceId, auth.devUserId, auth.devProjectId).forEach { id ->
                    require(runCatching { java.util.UUID.fromString(id) }.isSuccess) {
                        "Development bootstrap IDs must be valid UUIDs"
                    }
                }
            }

            AuthMode.JWT -> {
                require(auth.jwtIssuer.isNotBlank()) {
                    "POTATY_JWT_ISSUER is required in JWT auth mode"
                }
                require(auth.jwtAudience.isNotBlank()) {
                    "POTATY_JWT_AUDIENCE is required in JWT auth mode"
                }
                require(auth.jwtSecret.length >= MIN_JWT_SECRET_LENGTH) {
                    "POTATY_JWT_SECRET must be at least $MIN_JWT_SECRET_LENGTH characters"
                }
                require(auth.jwtSecret.toSet().size >= 8) {
                    "POTATY_JWT_SECRET must not be a low-entropy repeated value"
                }
                require(auth.jwtSecret.lowercase() !in INSECURE_SECRETS) {
                    "POTATY_JWT_SECRET uses an insecure placeholder value"
                }
            }
        }

        if (environment == RuntimeEnvironment.PRODUCTION) {
            require(database.mode == "postgres") { "Production requires POTATY_DB_MODE=postgres" }
            require(
                database.password.isNotBlank() && database.password.trim().lowercase() != "potaty"
            ) {
                "Production requires a non-default POTATY_DB_PASSWORD"
            }
            require(
                security.credentialMasterKeyRef.isNotBlank() &&
                    security.credentialMasterKeyRef != "local-dev-key"
            ) {
                "Production requires a non-development POTATY_CREDENTIAL_KMS_KEY"
            }
            require(
                cors.allowedOrigins.isNotEmpty() &&
                    cors.allowedOrigins.none { origin ->
                        origin == "*" ||
                            origin.contains("localhost", ignoreCase = true) ||
                            origin.contains("127.0.0.1") ||
                            !origin.startsWith("https://")
                    }
            ) {
                "Production CORS origins must be explicit HTTPS origins"
            }
        }
        val production = environment == RuntimeEnvironment.PRODUCTION
        llm.validate(production)
        github.validate(production)
    }

    private fun isValidCorsOrigin(origin: String): Boolean {
        if (origin.length !in 1..MAX_CORS_ORIGIN_LENGTH) return false
        val uri = runCatching { java.net.URI(origin) }.getOrNull() ?: return false
        return uri.scheme in setOf("http", "https") &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo == null &&
            uri.rawPath.isNullOrEmpty() &&
            uri.rawQuery == null &&
            uri.rawFragment == null &&
            (uri.port == -1 || uri.port in 1..65_535)
    }
}

enum class RuntimeEnvironment {
    DEVELOPMENT,
    TEST,
    PRODUCTION;

    companion object {
        fun fromWire(value: String): RuntimeEnvironment =
            when (value.trim().lowercase()) {
                "development",
                "dev",
                "local" -> DEVELOPMENT
                "test",
                "testing" -> TEST
                "production",
                "prod" -> PRODUCTION
                else -> error("POTATY_ENV must be development, test, or production")
            }
    }
}

enum class AuthMode {
    DEV,
    JWT;

    companion object {
        fun fromWire(value: String): AuthMode =
            when (value.trim().lowercase()) {
                "dev" -> DEV
                "jwt" -> JWT
                else -> error("POTATY_AUTH_MODE must be either 'dev' or 'jwt'")
            }
    }
}

data class ServerConfig(
    val port: Int,
    val host: String
)

data class DatabaseConfig(
    /** "h2" (embedded, ephemeral; schema via SchemaUtils) or "postgres" (Flyway-managed). */
    val mode: String,
    val jdbcUrl: String,
    val username: String,
    val password: String,
    val maxPoolSize: Int,
    val runFlywayMigrations: Boolean
) {
    val isH2: Boolean
        get() = mode == "h2"
}

/**
 * Local/dev authentication. When [devAuthEnabled] is true, [devToken] is seeded into the in-memory
 * SessionStore as an OWNER of [devWorkspaceId], so the API is exercisable without a real identity
 * provider. Production sets devAuthEnabled=false and wires a real session source.
 */
data class AuthConfig(
    val mode: AuthMode,
    val devToken: String,
    val devWorkspaceId: String,
    val devUserId: String,
    val devProjectId: String,
    val jwtIssuer: String,
    val jwtAudience: String,
    /** HMAC signing secret. It is read from the environment and must never be logged. */
    val jwtSecret: String,
    val jwtTtlSeconds: Int
) {
    val devAuthEnabled: Boolean
        get() = mode == AuthMode.DEV
}

data class CorsConfig(val allowedOrigins: List<String>)

data class SecurityConfig(
    val credentialMasterKeyRef: String,
    val allowProviderOAuth: Boolean
)

data class LlmConfig(
    val openAiBaseUrl: String,
    val anthropicBaseUrl: String,
    val routes: ModelRoutes
) {
    fun validate(production: Boolean) {
        validateProviderBaseUrl("OPENAI_BASE_URL", openAiBaseUrl, production)
        validateProviderBaseUrl("ANTHROPIC_BASE_URL", anthropicBaseUrl, production)
    }
}

private fun validateProviderBaseUrl(name: String, value: String, production: Boolean) {
    val uri = runCatching { java.net.URI(value) }.getOrNull()
    require(
        uri != null &&
            uri.scheme in setOf("http", "https") &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo == null &&
            uri.rawQuery == null &&
            uri.rawFragment == null &&
            (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") &&
            (uri.port == -1 || uri.port in 1..65_535)
    ) {
        "$name must be a scheme://host[:port] provider root without credentials, path, " +
            "query, or fragment"
    }
    require(!production || uri.scheme == "https") {
        "$name must use HTTPS in production"
    }
}

/**
 * Each pipeline stage declares a ModelTier; the router maps tier -> concrete model per provider.
 */
enum class ModelTier {
    CHEAP_STRUCTURED,
    MID_STRUCTURED,
    HIGH_REASONING,
    EMBEDDINGS,
    TRANSCRIPTION
}

data class ModelRoutes(
    val cheapStructured: ProviderModels,
    val midStructured: ProviderModels,
    val highReasoning: ProviderModels,
    val embeddings: ProviderModels,
    val transcription: ProviderModels
) {
    fun forTier(tier: ModelTier): ProviderModels =
        when (tier) {
            ModelTier.CHEAP_STRUCTURED -> cheapStructured
            ModelTier.MID_STRUCTURED -> midStructured
            ModelTier.HIGH_REASONING -> highReasoning
            ModelTier.EMBEDDINGS -> embeddings
            ModelTier.TRANSCRIPTION -> transcription
        }
}

data class ProviderModels(
    val openai: String?,
    val anthropic: String?
)
