/*
 * Copyright (c) 2026, Potaty
 *
 * Compact HMAC-signed state used across the GitHub installation and OAuth redirects. Signatures
 * prevent tampering; the database-backed nonce makes each state one-time across app instances.
 */

package com.potaty.backend.github

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class GitHubConnectPhase {
    INSTALL,
    OAUTH
}

@Serializable
data class GitHubConnectState(
    val version: Int = 1,
    val phase: GitHubConnectPhase,
    val nonce: String,
    val workspaceId: String,
    val userId: String,
    val candidateInstallationId: Long? = null,
    val issuedAtEpochSeconds: Long,
    val expiresAtEpochSeconds: Long
)

class GitHubConnectStateException(message: String) : RuntimeException(message)

class GitHubConnectStateCodec(
    secret: String,
    private val ttlSeconds: Int,
    private val clock: Clock = Clock.systemUTC(),
    private val random: SecureRandom = SecureRandom()
) {
    private val key = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), HMAC_ALGORITHM)
    private val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
    }

    fun issue(
        phase: GitHubConnectPhase,
        workspaceId: UUID,
        userId: UUID,
        candidateInstallationId: Long? = null
    ): Pair<String, GitHubConnectState> {
        if (phase == GitHubConnectPhase.INSTALL && candidateInstallationId != null) {
            throw IllegalArgumentException("INSTALL state cannot carry an installation candidate")
        }
        if (
            phase == GitHubConnectPhase.OAUTH &&
            (candidateInstallationId == null || candidateInstallationId <= 0)
        ) {
            throw IllegalArgumentException("OAUTH state requires a positive installation candidate")
        }
        val issuedAt = clock.instant().epochSecond
        val state =
            GitHubConnectState(
                phase = phase,
                nonce = randomToken(32),
                workspaceId = workspaceId.toString(),
                userId = userId.toString(),
                candidateInstallationId = candidateInstallationId,
                issuedAtEpochSeconds = issuedAt,
                expiresAtEpochSeconds = issuedAt + ttlSeconds
            )
        val payload =
            base64Url(json.encodeToString(GitHubConnectState.serializer(), state).toByteArray())
        val signature = base64Url(sign(payload.toByteArray(Charsets.US_ASCII)))
        return "$payload.$signature" to state
    }

    fun verify(token: String, expectedPhase: GitHubConnectPhase): GitHubConnectState {
        if (token.length !in 20..MAX_STATE_LENGTH) throw invalidState()
        val parts = token.split('.')
        if (parts.size != 2) throw invalidState()
        val payloadPart = parts[0]
        val suppliedSignature = decodeBase64(parts[1])
        val expectedSignature = sign(payloadPart.toByteArray(Charsets.US_ASCII))
        if (!MessageDigest.isEqual(expectedSignature, suppliedSignature)) throw invalidState()

        val state =
            try {
                json.decodeFromString(
                    GitHubConnectState.serializer(),
                    decodeBase64(payloadPart).toString(Charsets.UTF_8)
                )
            } catch (_: Exception) {
                throw invalidState()
            }
        val now = clock.instant().epochSecond
        val lifetime = state.expiresAtEpochSeconds - state.issuedAtEpochSeconds
        if (
            state.version != 1 ||
            state.phase != expectedPhase ||
            runCatching { UUID.fromString(state.workspaceId) }.isFailure ||
            runCatching { UUID.fromString(state.userId) }.isFailure ||
            state.issuedAtEpochSeconds > now + CLOCK_SKEW_SECONDS ||
            state.expiresAtEpochSeconds <= now ||
            lifetime !in 1..ttlSeconds.toLong()
        ) {
            throw invalidState()
        }
        if (state.phase == GitHubConnectPhase.INSTALL && state.candidateInstallationId != null) {
            throw invalidState()
        }
        if (
            state.phase == GitHubConnectPhase.OAUTH &&
            (state.candidateInstallationId == null || state.candidateInstallationId <= 0)
        ) {
            throw invalidState()
        }
        return state
    }

    fun randomPkceVerifier(): String = randomToken(32)

    fun pkceChallenge(verifier: String): String =
        base64Url(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        )

    private fun randomToken(byteCount: Int): String =
        ByteArray(byteCount).also(random::nextBytes).let(::base64Url)

    private fun sign(value: ByteArray): ByteArray =
        Mac.getInstance(HMAC_ALGORITHM).run {
            init(key)
            doFinal(value)
        }

    private fun decodeBase64(value: String): ByteArray =
        try {
            Base64.getUrlDecoder().decode(value)
        } catch (_: IllegalArgumentException) {
            throw invalidState()
        }

    private fun base64Url(value: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value)

    private fun invalidState() =
        GitHubConnectStateException("GitHub connect state is invalid, expired, or already used")

    private companion object {
        const val HMAC_ALGORITHM = "HmacSHA256"
        const val CLOCK_SKEW_SECONDS = 5L
        const val MAX_STATE_LENGTH = 4_096
    }
}
