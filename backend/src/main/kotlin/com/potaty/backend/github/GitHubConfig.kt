/*
 * Copyright (c) 2026, Potaty
 *
 * GitHub App configuration (plan section 4.2 P1 GitHub input). Like all config, model/endpoint
 * values are environment-driven, never source constants. A new `github: GitHubConfig` field is
 * added to AppConfig (see wiring) and populated from env in AppConfig.fromEnv.
 *
 *   - apiBaseUrl:        GitHub REST base (api.github.com, or a GHES host).
 *   - appId:             the GitHub App's numeric id (string here; used as JWT `iss`).
 *   - privateKeyPem:     the App's RSA private key in PEM (PKCS#8). Blank in dev/h2.
 *   - webhookSecret:     shared secret for X-Hub-Signature-256 verification.
 *   - maxFileBytes:      per-file cap; larger blobs are skipped at index time.
 *   - maxFilesPerIndex:  upper bound on blobs fetched in a single index run.
 *
 * [enabled] is true only when both an appId and a private key are present, so the rest of the
 * service runs out-of-the-box without GitHub credentials.
 */

package com.potaty.backend.github

data class GitHubConfig(
    val apiBaseUrl: String,
    val appId: String,
    val privateKeyPem: String,
    val webhookSecret: String,
    val maxFileBytes: Long,
    val maxFilesPerIndex: Int,
    val appSlug: String = "",
    val oauthClientId: String = "",
    val oauthClientSecret: String = "",
    val oauthCallbackUrl: String = "",
    val publicBaseUrl: String = "",
    val connectStateSecret: String = "",
    val connectStateTtlSeconds: Int = 900,
    val webBaseUrl: String = "https://github.com"
) {
    val enabled: Boolean
        get() = appId.isNotBlank() && privateKeyPem.isNotBlank()

    val connectEnabled: Boolean
        get() =
            enabled &&
                appSlug.isNotBlank() &&
                oauthClientId.isNotBlank() &&
                oauthClientSecret.isNotBlank() &&
                oauthCallbackUrl.isNotBlank() &&
                publicBaseUrl.isNotBlank() &&
                connectStateSecret.isNotBlank()

    fun validate(production: Boolean) {
        // Public-repository indexing is available even without a configured App, so its outbound
        // base URLs must always be validated before the early return below.
        validateBaseUrl(apiBaseUrl, "GITHUB_API_BASE_URL", production)
        validateBaseUrl(webBaseUrl, "GITHUB_WEB_BASE_URL", production)

        val privateIdentityFields =
            listOf(
                appId,
                privateKeyPem,
                appSlug,
                oauthClientId,
                oauthClientSecret,
                connectStateSecret
            )
        if (privateIdentityFields.none { it.isNotBlank() }) return

        val requiredFields =
            privateIdentityFields +
                listOf(
                    oauthCallbackUrl,
                    publicBaseUrl
                )
        require(requiredFields.all { it.isNotBlank() }) {
            "Private GitHub integration requires app id/key/slug, OAuth credentials, " +
                "callback/public URLs, and state secret"
        }
        require(appId.toLongOrNull()?.let { it > 0 } == true) {
            "GITHUB_APP_ID must be a positive integer"
        }
        require(appSlug.matches(Regex("[a-z0-9](?:[a-z0-9-]{0,98}[a-z0-9])?"))) {
            "GITHUB_APP_SLUG must be a lowercase GitHub App slug"
        }
        require(oauthClientSecret.length >= 20) {
            "GITHUB_OAUTH_CLIENT_SECRET is unexpectedly short"
        }
        require(connectStateSecret.length >= 32 && connectStateSecret.toSet().size >= 8) {
            "GITHUB_CONNECT_STATE_SECRET must be at least 32 characters with adequate entropy"
        }
        require(connectStateTtlSeconds in 60..1_800) {
            "GITHUB_CONNECT_STATE_TTL_SECONDS must be between 60 and 1800"
        }
        validateBaseUrl(publicBaseUrl, "POTATY_PUBLIC_BASE_URL", production)
        validateBaseUrl(oauthCallbackUrl, "GITHUB_OAUTH_CALLBACK_URL", production)
    }

    private fun validateBaseUrl(value: String, name: String, production: Boolean) {
        val uri = runCatching { java.net.URI(value) }.getOrNull()
        require(
            uri != null &&
                uri.host != null &&
                uri.userInfo == null &&
                uri.query == null &&
                uri.fragment == null
        ) {
            "$name must be an absolute URL without credentials, query, or fragment"
        }
        val httpGuidance = if (production) "" else " (HTTP is allowed only outside production)"
        require(uri.scheme == "https" || (!production && uri.scheme == "http")) {
            "$name must use HTTPS$httpGuidance"
        }
    }
}
