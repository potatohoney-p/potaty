/*
 * Copyright (c) 2026, Potaty
 *
 * Deterministic provider for tests, local dev, and the eval harness. Produces stable,
 * schema-shaped output without any network calls or credentials.
 */

package com.potaty.backend.llm.provider

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class MockProvider : LlmProvider {
    override val providerId: ProviderId = ProviderId.MOCK

    override suspend fun generateStructured(
        input: StructuredGenerationInput
    ): LlmResult<JsonObject> {
        val obj = buildJsonObject {
            put("provider", "mock")
            put("model", input.model)
            put("partCount", input.parts.size)
            put("deterministic", true)
        }
        return LlmResult.Success(
            obj,
            TokenUsage(
                inputTokens =
                input.parts.sumOf {
                    it.text.length / 4
                },
                outputTokens = 16
            )
        )
    }

    override suspend fun generateText(input: TextGenerationInput): LlmResult<String> {
        val text = "[mock:${input.model}] " + input.parts.joinToString(" | ") { it.role.name }
        return LlmResult.Success(text, TokenUsage(outputTokens = text.length / 4))
    }

    override suspend fun embed(input: EmbeddingInput): LlmResult<EmbeddingBatch> {
        // Deterministic pseudo-embeddings: hash each text into a fixed small vector.
        val dim = 8
        val vectors =
            input.texts.map { text ->
                FloatArray(dim) { i -> ((text.hashCode() ushr i) and 0xFF) / 255f }
            }
        return LlmResult.Success(EmbeddingBatch(vectors, dim))
    }

    override suspend fun transcribe(input: TranscriptionInput): LlmResult<TranscriptArtifact> {
        val artifact =
            TranscriptArtifact(
                text = "mock transcript for ${input.audioObjectKey}",
                segments = listOf(TranscriptSegment(0, 1000, "speaker_1", "mock transcript"))
            )
        return LlmResult.Success(artifact)
    }
}
