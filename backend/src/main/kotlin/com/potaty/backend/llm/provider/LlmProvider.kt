/*
 * Copyright (c) 2026, Potaty
 *
 * Provider abstraction (plan section 3.2). All four capabilities are suspend; concrete
 * providers do server-side HTTP only. Credentials never reach the browser.
 */

package com.potaty.backend.llm.provider

import com.potaty.backend.llm.auth.LlmCredential
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

enum class ProviderId {
    OPENAI,
    ANTHROPIC,
    MOCK
}

interface LlmProvider {
    val providerId: ProviderId

    suspend fun generateStructured(input: StructuredGenerationInput): LlmResult<JsonObject>
    suspend fun generateText(input: TextGenerationInput): LlmResult<String>
    suspend fun embed(input: EmbeddingInput): LlmResult<EmbeddingBatch>
    suspend fun transcribe(input: TranscriptionInput): LlmResult<TranscriptArtifact>
}

/**
 * A single prompt part with a trust role (plan 15.1). Only [PromptPartRole.SOURCE_DATA] may
 * contain untrusted source text; builders must enforce this (see PromptInjectionGuard).
 */
data class PromptPart(
    val role: PromptPartRole,
    val text: String
)

enum class PromptPartRole {
    SYSTEM_POLICY,
    DEVELOPER_INSTRUCTIONS,
    TASK_INSTRUCTIONS,
    SOURCE_DATA,
    USER_REQUEST,
    SCHEMA
}

data class StructuredGenerationInput(
    val credential: LlmCredential,
    val model: String,
    val parts: List<PromptPart>,
    /** JSON Schema the output must conform to. Drives forced tool-use / response_format. */
    val jsonSchema: JsonElement,
    val maxOutputTokens: Int = 4096,
    val temperature: Double = 0.0
)

data class TextGenerationInput(
    val credential: LlmCredential,
    val model: String,
    val parts: List<PromptPart>,
    val maxOutputTokens: Int = 2048,
    val temperature: Double = 0.2
)

data class EmbeddingInput(
    val credential: LlmCredential,
    val model: String,
    val texts: List<String>
)

data class EmbeddingBatch(
    val vectors: List<FloatArray>,
    val dimensions: Int
)

data class TranscriptionInput(
    val credential: LlmCredential,
    val model: String,
    val audioObjectKey: String,
    val mimeType: String
)

data class TranscriptArtifact(
    val text: String,
    val segments: List<TranscriptSegment>
)

data class TranscriptSegment(
    val startMs: Int,
    val endMs: Int,
    val speaker: String?,
    val text: String
)

/** Token accounting recorded into usage_events. */
data class TokenUsage(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cachedInputTokens: Int = 0
) {
    init {
        require(inputTokens >= 0) { "inputTokens must not be negative" }
        require(outputTokens >= 0) { "outputTokens must not be negative" }
        require(cachedInputTokens >= 0) { "cachedInputTokens must not be negative" }
    }

    operator fun plus(other: TokenUsage): TokenUsage =
        TokenUsage(
            inputTokens = saturatedAdd(inputTokens, other.inputTokens),
            outputTokens = saturatedAdd(outputTokens, other.outputTokens),
            cachedInputTokens = saturatedAdd(cachedInputTokens, other.cachedInputTokens)
        )

    fun isEmpty(): Boolean = inputTokens == 0 && outputTokens == 0 && cachedInputTokens == 0

    private fun saturatedAdd(left: Int, right: Int): Int =
        (left.toLong() + right.toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}
