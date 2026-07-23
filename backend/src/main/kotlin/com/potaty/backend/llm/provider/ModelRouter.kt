/*
 * Copyright (c) 2026, Potaty
 *
 * Model router (plan section 3.4). Maps a (ModelTier, ProviderId) to a concrete model id from
 * configuration. Business logic and prompts declare a tier, never a model name.
 */

package com.potaty.backend.llm.provider

import com.potaty.backend.config.LlmConfig
import com.potaty.backend.config.ModelTier

class ModelRouter(private val llmConfig: LlmConfig) {

    /** Resolves the model id for [tier] on [provider], or null if that provider has no model for it. */
    fun resolve(tier: ModelTier, provider: ProviderId): String? {
        val models = llmConfig.routes.forTier(tier)
        return when (provider) {
            ProviderId.OPENAI -> models.openai
            ProviderId.ANTHROPIC -> models.anthropic
            ProviderId.MOCK -> "mock-${tier.name.lowercase()}"
        }
    }

    /** Resolves the model id or throws if the provider cannot serve the tier. */
    fun require(tier: ModelTier, provider: ProviderId): String =
        resolve(tier, provider)
            ?: error("Provider $provider has no configured model for tier $tier")
}
