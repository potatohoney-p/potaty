/*
 * Copyright (c) 2026, Potaty
 *
 * Builds LlmProvider instances. Each provider gets a shared Ktor HttpClient and the
 * CredentialStore so it can resolve secrets server-side at call time.
 */

package com.potaty.backend.llm.provider

import com.potaty.backend.config.LlmConfig
import com.potaty.backend.llm.anthropic.AnthropicProvider
import com.potaty.backend.llm.auth.CredentialStore
import com.potaty.backend.llm.openai.OpenAiProvider
import io.ktor.client.HttpClient

class ProviderFactory(
    private val llmConfig: LlmConfig,
    private val credentialStore: CredentialStore,
    private val httpClient: HttpClient,
    private val allowProviderOAuth: Boolean = false
) {
    private val cache = mutableMapOf<ProviderId, LlmProvider>()

    fun get(providerId: ProviderId): LlmProvider = cache.getOrPut(providerId) {
        when (providerId) {
            ProviderId.OPENAI -> OpenAiProvider(
                llmConfig.openAiBaseUrl,
                httpClient,
                credentialStore
            )
            ProviderId.ANTHROPIC -> AnthropicProvider(
                llmConfig.anthropicBaseUrl,
                httpClient,
                credentialStore,
                allowProviderOAuth
            )
            ProviderId.MOCK -> MockProvider()
        }
    }
}
