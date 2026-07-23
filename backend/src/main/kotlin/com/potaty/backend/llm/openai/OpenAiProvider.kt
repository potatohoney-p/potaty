/*
 * Copyright (c) 2026, Potaty
 *
 * OpenAI provider. Structured output uses response_format json_schema (strict) so the model
 * returns schema-conformant JSON directly.
 *
 * AUTH (plan section 3.1): API KEY ONLY (Authorization: Bearer <api-key>). A ChatGPT
 * subscription is NOT an API credential and is not accepted here. There is no supported
 * subscription-OAuth path for third-party apps, so OpenAiProvider only handles
 * ApiKeyCredential and rejects ProviderOAuthCredential.
 *
 * generateStructured (response_format json_schema strict), generateText, and embed make real
 * POST calls to /v1/chat/completions and /v1/embeddings (Ktor client) with error mapping via
 * ProviderErrorMapper. transcribe (multipart audio) is the one remaining UNSUPPORTED method (WS12).
 */

package com.potaty.backend.llm.openai

import com.potaty.backend.llm.auth.ApiKeyCredential
import com.potaty.backend.llm.auth.CredentialStore
import com.potaty.backend.llm.auth.EncryptedSecretRef
import com.potaty.backend.llm.auth.LlmCredential
import com.potaty.backend.llm.provider.EmbeddingBatch
import com.potaty.backend.llm.provider.EmbeddingInput
import com.potaty.backend.llm.provider.LlmError
import com.potaty.backend.llm.provider.LlmErrorKind
import com.potaty.backend.llm.provider.LlmProvider
import com.potaty.backend.llm.provider.LlmResult
import com.potaty.backend.llm.provider.PromptAssembler
import com.potaty.backend.llm.provider.ProviderErrorMapper
import com.potaty.backend.llm.provider.ProviderId
import com.potaty.backend.llm.provider.StructuredGenerationInput
import com.potaty.backend.llm.provider.TextGenerationInput
import com.potaty.backend.llm.provider.TokenUsage
import com.potaty.backend.llm.provider.TranscriptArtifact
import com.potaty.backend.llm.provider.TranscriptionInput
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private val JSON = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

class OpenAiProvider(
    private val baseUrl: String,
    private val httpClient: HttpClient,
    private val credentialStore: CredentialStore
) : LlmProvider {
    override val providerId: ProviderId = ProviderId.OPENAI

    internal fun authHeader(credential: LlmCredential): String {
        val apiKey =
            (credential as? ApiKeyCredential)
                ?: error(
                    "OpenAI requires an ApiKeyCredential. ChatGPT subscriptions are not API " +
                        "credentials and provider OAuth is not supported for OpenAI."
                )
        val key =
            credentialStore.open(
                apiKey.workspaceId,
                EncryptedSecretRef(apiKey.encryptedApiKeyRef)
            )
        return "Bearer $key"
    }

    override suspend fun generateStructured(
        input: StructuredGenerationInput
    ): LlmResult<JsonObject> {
        val (system, user) = PromptAssembler.split(input.parts)
        val body = buildJsonObject {
            put("model", input.model)
            put("temperature", input.temperature)
            put("max_tokens", input.maxOutputTokens)
            putJsonArray("messages") {
                if (system.isNotBlank()) {
                    addJsonObject {
                        put("role", "system")
                        put(
                            "content",
                            system
                        )
                    }
                }
                addJsonObject {
                    put("role", "user")
                    put("content", user)
                }
            }
            putJsonObject("response_format") {
                put("type", "json_schema")
                putJsonObject("json_schema") {
                    put("name", "diagram_result")
                    put("strict", true)
                    put("schema", input.jsonSchema)
                }
            }
        }
        return chatCompletion(input.credential, body) { content ->
            runCatching { JSON.parseToJsonElement(content).jsonObject }
                .getOrNull()
                ?.let { it to null } ?: (null to "OpenAI message content was not valid JSON")
        }
    }

    override suspend fun generateText(input: TextGenerationInput): LlmResult<String> {
        val (system, user) = PromptAssembler.split(input.parts)
        val body = buildJsonObject {
            put("model", input.model)
            put("temperature", input.temperature)
            put("max_tokens", input.maxOutputTokens)
            putJsonArray("messages") {
                if (system.isNotBlank()) {
                    addJsonObject {
                        put("role", "system")
                        put(
                            "content",
                            system
                        )
                    }
                }
                addJsonObject {
                    put("role", "user")
                    put("content", user)
                }
            }
        }
        return when (
            val r =
                chatCompletion(input.credential, body) { content ->
                    buildJsonObject {
                        put("text", content)
                    } to null
                }
        ) {
            is LlmResult.Success ->
                LlmResult.Success(
                    r.value["text"]!!.jsonPrimitive.content,
                    r.usage
                )
            is LlmResult.Failure -> r
        }
    }

    override suspend fun embed(input: EmbeddingInput): LlmResult<EmbeddingBatch> =
        try {
            val body = buildJsonObject {
                put("model", input.model)
                putJsonArray("input") { input.texts.forEach { add(it) } }
            }
            val response =
                httpClient.post("$baseUrl/v1/embeddings") {
                    header("Authorization", authHeader(input.credential))
                    contentType(ContentType.Application.Json)
                    setBody(JSON.encodeToString(JsonObject.serializer(), body))
                }
            val text = response.bodyAsText()
            if (response.status.value !in 200..299) {
                LlmResult.Failure(ProviderErrorMapper.fromHttpStatus(response.status.value, text))
            } else {
                val root = runCatching { JSON.parseToJsonElement(text).jsonObject }.getOrNull()
                if (root == null) {
                    LlmResult.Failure(
                        LlmError(
                            LlmErrorKind.INVALID_OUTPUT,
                            "OpenAI embedding response was not a JSON object"
                        )
                    )
                } else {
                    val usage = usageFrom(root)
                    val vectors =
                        runCatching {
                            root["data"]
                                ?.jsonArray
                                .orEmpty()
                                .map { row ->
                                    row.jsonObject["embedding"]!!
                                        .jsonArray
                                        .map { it.jsonPrimitive.float }
                                        .toFloatArray()
                                }
                        }.getOrNull()
                    val dims = vectors?.firstOrNull()?.size ?: 0
                    val valid =
                        !vectors.isNullOrEmpty() &&
                            dims > 0 &&
                            vectors.all { vector ->
                                vector.size == dims && vector.all { value -> value.isFinite() }
                            }
                    if (!valid) {
                        LlmResult.Failure(
                            LlmError(
                                LlmErrorKind.INVALID_OUTPUT,
                                "OpenAI embedding response had invalid vectors"
                            ),
                            usage
                        )
                    } else {
                        LlmResult.Success(EmbeddingBatch(requireNotNull(vectors), dims), usage)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            LlmResult.Failure(
                LlmError(LlmErrorKind.NETWORK, t.message ?: "network error", retryable = true)
            )
        }

    override suspend fun transcribe(input: TranscriptionInput): LlmResult<TranscriptArtifact> {
        // Audio transcription (multipart /v1/audio/transcriptions) is a P1 feature (WS12); not
        // wired.
        return LlmResult.Failure(
            LlmError(LlmErrorKind.UNSUPPORTED, "OpenAI transcription is not yet wired")
        )
    }

    private suspend fun chatCompletion(
        credential: LlmCredential,
        body: JsonObject,
        extract: (String) -> Pair<JsonObject?, String?>
    ): LlmResult<JsonObject> =
        try {
            val response =
                httpClient.post("$baseUrl/v1/chat/completions") {
                    header("Authorization", authHeader(credential))
                    contentType(ContentType.Application.Json)
                    setBody(JSON.encodeToString(JsonObject.serializer(), body))
                }
            val text = response.bodyAsText()
            if (response.status.value !in 200..299) {
                LlmResult.Failure(ProviderErrorMapper.fromHttpStatus(response.status.value, text))
            } else {
                val root = runCatching { JSON.parseToJsonElement(text).jsonObject }.getOrNull()
                if (root == null) {
                    LlmResult.Failure(
                        LlmError(
                            LlmErrorKind.INVALID_OUTPUT,
                            "OpenAI response was not a JSON object"
                        )
                    )
                } else {
                    val usage = usageFrom(root)
                    val content =
                        runCatching {
                            root["choices"]
                                ?.jsonArray
                                ?.firstOrNull()
                                ?.jsonObject
                                ?.get("message")
                                ?.jsonObject
                                ?.get("content")
                                ?.jsonPrimitive
                                ?.content
                        }.getOrNull()
                    if (content == null) {
                        LlmResult.Failure(
                            LlmError(
                                LlmErrorKind.INVALID_OUTPUT,
                                "OpenAI response had no message content"
                            ),
                            usage
                        )
                    } else {
                        val (value, error) =
                            runCatching { extract(content) }
                                .getOrElse { null to "could not parse OpenAI content" }
                        if (value == null) {
                            LlmResult.Failure(
                                LlmError(
                                    LlmErrorKind.INVALID_OUTPUT,
                                    error ?: "could not parse OpenAI content"
                                ),
                                usage
                            )
                        } else {
                            LlmResult.Success(value, usage)
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            LlmResult.Failure(
                LlmError(LlmErrorKind.NETWORK, t.message ?: "network error", retryable = true)
            )
        }

    private fun usageFrom(root: JsonObject): TokenUsage {
        val usage = runCatching { root["usage"]?.jsonObject }.getOrNull() ?: return TokenUsage()
        val promptDetails =
            runCatching { usage["prompt_tokens_details"]?.jsonObject }.getOrNull()
        val inputTokens =
            runCatching { usage["prompt_tokens"]?.jsonPrimitive?.intOrNull }
                .getOrNull()
                ?.coerceAtLeast(0) ?: 0
        val outputTokens =
            runCatching { usage["completion_tokens"]?.jsonPrimitive?.intOrNull }
                .getOrNull()
                ?.coerceAtLeast(0) ?: 0
        val cachedInputTokens =
            runCatching { promptDetails?.get("cached_tokens")?.jsonPrimitive?.intOrNull }
                .getOrNull()
                ?.coerceAtLeast(0) ?: 0
        return TokenUsage(
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            cachedInputTokens = cachedInputTokens
        )
    }
}
