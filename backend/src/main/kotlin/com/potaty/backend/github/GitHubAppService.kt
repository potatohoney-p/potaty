/*
 * Copyright (c) 2026, Potaty
 *
 * GitHub App authentication (plan section 4.2 P1 GitHub input). A GitHub App authenticates as
 * itself with a short-lived RS256 JWT signed by its private key, then exchanges that JWT for an
 * INSTALLATION access token scoped to one installation:
 *
 *   1. buildAppJwt(): {alg:RS256} JWT, iss=appId, iat=now-60s (clock skew), exp=now+9m, signed
 *      with SHA256withRSA over the PEM (PKCS#8) private key — pure java.security, no JWT library.
 *   2. createInstallationToken(): POST {apiBaseUrl}/app/installations/{id}/access_tokens with
 *      `Authorization: Bearer <jwt>` (Ktor client) -> InstallationTokenResponse.token.
 *
 * The token is then handed to GitHubIndexer for read-only tree/blob fetches.
 */

package com.potaty.backend.github

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import kotlinx.serialization.json.Json

class GitHubTokenException(message: String) : RuntimeException(message)

class GitHubAppService(
    private val config: GitHubConfig,
    private val httpClient: HttpClient,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Builds a signed GitHub App JWT. [nowSeconds] is injectable for deterministic tests. Throws if
     * no private key is configured.
     */
    fun buildAppJwt(nowSeconds: Long = clock() / 1000): String {
        if (config.privateKeyPem.isBlank()) {
            throw GitHubTokenException("GitHub App private key is not configured")
        }
        val header = """{"alg":"RS256","typ":"JWT"}"""
        // iat back-dated 60s for clock skew; exp 9 minutes out (GitHub max is 10).
        val payload =
            """{"iat":${nowSeconds - 60},"exp":${nowSeconds + 540},"iss":"${config.appId}"}"""
        val signingInput =
            base64Url(header.toByteArray(Charsets.UTF_8)) +
                "." +
                base64Url(payload.toByteArray(Charsets.UTF_8))

        val signature =
            Signature.getInstance("SHA256withRSA")
                .apply {
                    initSign(loadPrivateKey(config.privateKeyPem))
                    update(signingInput.toByteArray(Charsets.US_ASCII))
                }
                .sign()

        return signingInput + "." + base64Url(signature)
    }

    /**
     * Exchanges the App JWT for an installation access token. Returns the bearer token string. Maps
     * non-2xx responses to [GitHubTokenException].
     */
    suspend fun createInstallationToken(installationId: Long): String {
        val jwt = buildAppJwt()
        val response =
            httpClient.post(
                "${config.apiBaseUrl}/app/installations/$installationId/access_tokens"
            ) {
                header("Authorization", "Bearer $jwt")
                header("Accept", "application/vnd.github+json")
                header("X-GitHub-Api-Version", "2022-11-28")
                contentType(ContentType.Application.Json)
            }
        val text = response.bodyAsText()
        if (response.status.value !in 200..299) {
            throw GitHubTokenException(
                "installation token request failed (${response.status.value})"
            )
        }
        val parsed = json.decodeFromString(InstallationTokenResponse.serializer(), text)
        if (parsed.token.isBlank()) {
            throw GitHubTokenException("installation token response had no token")
        }
        return parsed.token
    }

    private fun loadPrivateKey(pem: String): java.security.PrivateKey {
        val cleaned =
            pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replace("\\s".toRegex(), "")
        val der = Base64.getDecoder().decode(cleaned)
        return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
