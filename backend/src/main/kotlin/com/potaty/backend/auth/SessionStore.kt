/*
 * Copyright (c) 2026, Potaty
 *
 * Session resolution: maps an opaque bearer token to a workspace-scoped [TenantContext].
 *
 * Tokens are WORKSPACE-SCOPED on purpose: a token authorizes exactly one (user, workspace, role)
 * tuple. This keeps tenant isolation structural — a token issued for workspace A can never read
 * workspace B's data because every repository call filters by the resolved workspaceId, and the
 * resolver here never returns a context for a different workspace.
 *
 * The in-memory implementation is strictly for explicit local development and tests. Production
 * uses signed JWTs and re-checks current workspace membership before attaching a tenant context.
 */

package com.potaty.backend.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.potaty.backend.config.AuthConfig
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.Date
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

interface SessionStore {
    /** Resolves a bearer token to its tenant context, or null if unknown/expired/revoked. */
    suspend fun resolve(token: String): TenantContext?

    /** Issues a fresh session token bound to [context]. */
    fun issue(context: TenantContext): String

    /** Revokes a token (logout). No-op if unknown. */
    fun revoke(token: String)
}

class InMemorySessionStore : SessionStore {
    private val tokens = ConcurrentHashMap<String, TenantContext>()

    override suspend fun resolve(token: String): TenantContext? = tokens[token]

    override fun issue(context: TenantContext): String {
        val token = "tok_" + UUID.randomUUID().toString().replace("-", "")
        tokens[token] = context
        return token
    }

    override fun revoke(token: String) {
        tokens.remove(token)
    }

    /** Seeds a known token (e.g. a dev/integration token from config). Returns [token]. */
    fun seed(token: String, context: TenantContext): String {
        tokens[token] = context
        return token
    }

    companion object {
        /**
         * Builds a store pre-seeded with a single OWNER dev token. Used in local/dev mode and
         * integration tests so the API is exercisable without a real identity provider.
         */
        fun withDevToken(
            token: String,
            workspaceId: String,
            userId: String,
            role: WorkspaceRole = WorkspaceRole.OWNER
        ): InMemorySessionStore =
            InMemorySessionStore().apply {
                seed(token, TenantContext(workspaceId = workspaceId, userId = userId, role = role))
            }
    }
}

/**
 * HMAC-SHA256 signed, workspace-scoped JWT sessions for self-hosted production deployments. Issuer,
 * audience, expiry, signature, subject, workspace and role are all mandatory. The token itself is
 * never persisted. [revoke] provides process-local immediate revocation; deployments needing
 * durable/global revocation should keep access tokens short-lived and add an external
 * revocation/session service in front of this interface.
 */
class JwtSessionStore(
    private val issuer: String,
    private val audience: String,
    secret: String,
    private val ttlSeconds: Int,
    private val clock: Clock = Clock.systemUTC()
) : SessionStore {
    private val algorithm = Algorithm.HMAC256(secret)
    private val verifier =
        JWT.require(algorithm)
            .withIssuer(issuer)
            .withAudience(audience)
            .withClaimPresence("sub")
            .withClaimPresence("jti")
            .withClaimPresence("iat")
            .withClaimPresence("exp")
            .withClaimPresence(WORKSPACE_CLAIM)
            .withClaimPresence(ROLE_CLAIM)
            .acceptLeeway(CLOCK_SKEW_SECONDS)
            .build()
    private val revokedUntil = ConcurrentHashMap<String, Instant>()

    constructor(
        config: AuthConfig
    ) : this(
        issuer = config.jwtIssuer,
        audience = config.jwtAudience,
        secret = config.jwtSecret,
        ttlSeconds = config.jwtTtlSeconds
    )

    override suspend fun resolve(token: String): TenantContext? {
        if (token.length !in 1..MAX_TOKEN_LENGTH) return null
        if (!hasCanonicalJwtEncoding(token)) return null
        purgeExpiredRevocations()
        return try {
            val jwt = verifier.verify(token)
            val jti = jwt.id ?: return null
            val issuedAt = jwt.issuedAt?.toInstant() ?: return null
            val expiresAt = jwt.expiresAt?.toInstant() ?: return null
            val now = clock.instant()
            if (issuedAt.isAfter(now.plusSeconds(CLOCK_SKEW_SECONDS)) || !expiresAt.isAfter(now)) {
                return null
            }
            val lifetimeSeconds = ChronoUnit.SECONDS.between(issuedAt, expiresAt)
            if (lifetimeSeconds !in 1..(ttlSeconds.toLong() + CLOCK_SKEW_SECONDS)) return null
            if (revokedUntil.containsKey(jti)) return null

            val workspaceId =
                jwt.getClaim(WORKSPACE_CLAIM).asString()?.takeIf(::isUuid) ?: return null
            val userId = jwt.subject?.takeIf(::isUuid) ?: return null
            val roleWire = jwt.getClaim(ROLE_CLAIM).asString() ?: return null
            val role = runCatching { WorkspaceRole.fromWire(roleWire) }.getOrNull() ?: return null
            TenantContext(workspaceId = workspaceId, userId = userId, role = role)
        } catch (_: JWTVerificationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    override fun issue(context: TenantContext): String {
        require(isUuid(context.workspaceId)) { "workspaceId must be a UUID" }
        require(isUuid(context.userId)) { "userId must be a UUID" }
        val issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS)
        val expiresAt = issuedAt.plusSeconds(ttlSeconds.toLong())
        return JWT.create()
            .withIssuer(issuer)
            .withAudience(audience)
            .withSubject(context.userId)
            .withJWTId(UUID.randomUUID().toString())
            .withIssuedAt(Date.from(issuedAt))
            .withExpiresAt(Date.from(expiresAt))
            .withClaim(WORKSPACE_CLAIM, context.workspaceId)
            .withClaim(ROLE_CLAIM, context.role.name.lowercase())
            .sign(algorithm)
    }

    override fun revoke(token: String) {
        try {
            val jwt = verifier.verify(token)
            val jti = jwt.id ?: return
            val expiry = jwt.expiresAt?.toInstant() ?: return
            if (expiry.isAfter(clock.instant())) revokedUntil[jti] = expiry
        } catch (_: JWTVerificationException) {
            // Invalid tokens are already unusable; revocation is intentionally a no-op.
        }
    }

    private fun purgeExpiredRevocations() {
        val now = clock.instant()
        revokedUntil.entries.removeIf { (_, expiry) -> !expiry.isAfter(now) }
    }

    /**
     * java-jwt verifies decoded signature bytes, so a token with non-zero unused Base64URL pad bits
     * can otherwise be accepted even though its serialized form was changed. Requiring canonical,
     * unpadded Base64URL closes that ambiguity before signature verification.
     */
    private fun hasCanonicalJwtEncoding(token: String): Boolean {
        val segments = token.split('.')
        if (segments.size != JWT_SEGMENT_COUNT || segments.any { it.isEmpty() }) return false
        return try {
            segments.all { segment ->
                val decoded = Base64.getUrlDecoder().decode(segment)
                Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) == segment
            }
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private fun isUuid(value: String): Boolean = runCatching { UUID.fromString(value) }.isSuccess

    private companion object {
        const val WORKSPACE_CLAIM = "workspace_id"
        const val ROLE_CLAIM = "role"
        const val CLOCK_SKEW_SECONDS = 5L
        const val MAX_TOKEN_LENGTH = 8_192
        const val JWT_SEGMENT_COUNT = 3
    }
}

/** Keeps stateless token claims synchronized with the current durable membership/role record. */
class MembershipBoundSessionStore(
    private val delegate: SessionStore,
    private val membershipMatches: suspend (TenantContext) -> Boolean
) : SessionStore {
    override suspend fun resolve(token: String): TenantContext? =
        delegate.resolve(token)?.takeIf { membershipMatches(it) }

    override fun issue(context: TenantContext): String = delegate.issue(context)

    override fun revoke(token: String) = delegate.revoke(token)
}
