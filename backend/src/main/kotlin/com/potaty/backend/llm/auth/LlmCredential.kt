/*
 * Copyright (c) 2026, Potaty
 *
 * Credential model (plan section 3.3).
 *
 * PROVIDER REALITY (plan section 3.1) — read before extending:
 *   - OpenAI: API key only. A ChatGPT Plus/Pro/Business/Enterprise subscription is NOT an
 *     API credential and MUST NOT be accepted as one. There is no supported subscription
 *     OAuth path for third-party apps today.
 *   - Anthropic: API key (Console billing) in hosted Potaty. Subscription / Claude.ai OAuth
 *     ("Log in with Claude Pro/Max and route requests through subscription credentials") is
 *     NOT offered as a hosted flow; it is BYO-only and gated behind a feature flag for
 *     self-hosted/local developer mode, subject to provider ToS (see SecurityConfig.allowProviderOAuth).
 *
 * Therefore ProviderOAuthCredential exists in the model for extensibility but is disabled by
 * default and must never be used to wrap a consumer subscription as if it were API access.
 */

package com.potaty.backend.llm.auth

import com.potaty.backend.llm.provider.ProviderId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class CredentialStatus {
    @SerialName("active")
    ACTIVE,

    @SerialName("revoked")
    REVOKED,

    @SerialName("expired")
    EXPIRED,

    @SerialName("error")
    ERROR
}

@Serializable
sealed interface LlmCredential {
    val id: String
    val workspaceId: String
    val provider: ProviderId
    val status: CredentialStatus
}

/** API-key credential. The plaintext key NEVER leaves the server; only an encrypted ref is stored. */
@Serializable
@SerialName("api_key")
data class ApiKeyCredential(
    override val id: String,
    override val workspaceId: String,
    override val provider: ProviderId,
    /** Reference into CredentialStore; resolving it requires the master key (KMS in prod). */
    val encryptedApiKeyRef: String,
    val label: String,
    val createdByUserId: String,
    override val status: CredentialStatus = CredentialStatus.ACTIVE
) : LlmCredential

/**
 * Provider OAuth credential. DISABLED BY DEFAULT (see file header + SecurityConfig).
 * [legalUseCaseApproved] must be true and the feature flag enabled before use.
 */
@Serializable
@SerialName("provider_oauth")
data class ProviderOAuthCredential(
    override val id: String,
    override val workspaceId: String,
    override val provider: ProviderId,
    val encryptedAccessTokenRef: String,
    val encryptedRefreshTokenRef: String? = null,
    val expiresAtEpochMs: Long? = null,
    val scopes: List<String> = emptyList(),
    val providerAccountId: String? = null,
    val legalUseCaseApproved: Boolean = false,
    override val status: CredentialStatus = CredentialStatus.ACTIVE
) : LlmCredential
