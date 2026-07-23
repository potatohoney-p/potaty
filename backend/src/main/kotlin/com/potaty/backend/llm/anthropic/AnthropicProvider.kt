/*
 * Copyright (c) 2026, Potaty
 *
 * Anthropic provider. Structured output uses FORCED TOOL USE (a single tool whose
 * input_schema is the required JSON schema; tool_choice forces that tool), which is
 * Anthropic's reliable path to schema-conformant JSON.
 *
 * AUTH (plan section 3.1):
 *   - Hosted Potaty: x-api-key header from an ApiKeyCredential (Console billing).
 *   - BYO / self-hosted only: a ProviderOAuthCredential may send
 *       Authorization: Bearer <token>
 *       anthropic-beta: oauth-2025-04-20   (or the then-current beta id)
 *     This path is DISABLED unless SecurityConfig.allowProviderOAuth is true AND the
 *     credential's legalUseCaseApproved is true. Consumer subscription tokens MUST NOT be
 *     wrapped here (see LlmCredential header).
 *
 * generateStructured/generateText make real POST /v1/messages calls (Ktor client) and parse the
 * tool_use input / text blocks; HTTP errors map via ProviderErrorMapper. embed/transcribe return
 * UNSUPPORTED by design (Anthropic offers neither; embeddings route to OpenAI/Voyage).
 */

package com.potaty.backend.llm.anthropic

import com.potaty.backend.llm.auth.ApiKeyCredential
import com.potaty.backend.llm.auth.CredentialStore
import com.potaty.backend.llm.auth.EncryptedSecretRef
import com.potaty.backend.llm.auth.LlmCredential
import com.potaty.backend.llm.auth.ProviderOAuthCredential
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
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private const val ANTHROPIC_VERSION = "2023-06-01"
private const val ANTHROPIC_OAUTH_BETA = "oauth-2025-04-20"

private val JSON = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

class AnthropicProvider(
    private val baseUrl: String,
    private val httpClient: HttpClient,
    private val credentialStore: CredentialStore,
    private val allowProviderOAuth: Boolean = false
) : LlmProvider {
    override val providerId: ProviderId = ProviderId.ANTHROPIC

    /** Builds the auth + version headers for a request based on credential type. */
    internal fun authHeaders(credential: LlmCredential): Map<String, String> {
        val common = mapOf("anthropic-version" to ANTHROPIC_VERSION)
        return when (credential) {
            is ApiKeyCredential -> {
                val key =
                    credentialStore.open(
                        credential.workspaceId,
                        EncryptedSecretRef(credential.encryptedApiKeyRef)
                    )
                common + ("x-api-key" to key)
            }
            is ProviderOAuthCredential -> {
                require(allowProviderOAuth) {
                    "Provider OAuth is disabled; set POTATY_ALLOW_PROVIDER_OAUTH=true only " +
                        "for an approved BYO/self-hosted deployment"
                }
                require(credential.legalUseCaseApproved) {
                    "ProviderOAuthCredential requires legalUseCaseApproved=true " +
                        "(BYO/self-hosted only)"
                }
                val token =
                    credentialStore.open(
                        credential.workspaceId,
                        EncryptedSecretRef(credential.encryptedAccessTokenRef)
                    )
                common +
                    mapOf(
                        "Authorization" to "Bearer $token",
                        "anthropic-beta" to ANTHROPIC_OAUTH_BETA
                    )
            }
        }
    }

    override suspend fun generateStructured(
        input: StructuredGenerationInput
    ): LlmResult<JsonObject> {
        val (system, user) = PromptAssembler.split(input.parts)
        // Forced tool use: a single "emit" tool whose input_schema is the required schema.
        val body = buildJsonObject {
            put("model", input.model)
            put("max_tokens", input.maxOutputTokens)
            put("temperature", input.temperature)
            if (system.isNotBlank()) put("system", system)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    put("content", user)
                }
            }
            putJsonArray("tools") {
                addJsonObject {
                    put("name", "emit")
                    put(
                        "description",
                        "Return the result strictly as JSON conforming to input_schema."
                    )
                    put("input_schema", input.jsonSchema)
                }
            }
            putJsonObject("tool_choice") {
                put("type", "tool")
                put("name", "emit")
            }
        }
        return request(input.credential, body) { root ->
            val content =
                root["content"]?.jsonArray
                    ?: return@request null to "Anthropic response had no content array"
            val toolUse =
                content
                    .map { it.jsonObject }
                    .firstOrNull { it["type"]?.jsonPrimitive?.content == "tool_use" }
            val emitted =
                toolUse?.get("input")?.jsonObject
                    ?: return@request null to "Anthropic response had no tool_use input"
            emitted to null
        }
    }

    override suspend fun generateText(input: TextGenerationInput): LlmResult<String> {
        val (system, user) = PromptAssembler.split(input.parts)
        val body = buildJsonObject {
            put("model", input.model)
            put("max_tokens", input.maxOutputTokens)
            put("temperature", input.temperature)
            if (system.isNotBlank()) put("system", system)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    put("content", user)
                }
            }
        }
        val result =
            request(input.credential, body) { root ->
                val text =
                    root["content"]
                        ?.jsonArray
                        ?.map { it.jsonObject }
                        ?.firstOrNull { it["type"]?.jsonPrimitive?.content == "text" }
                        ?.get("text")
                        ?.jsonPrimitive
                        ?.content ?: return@request null to "Anthropic response had no text block"
                buildJsonObject { put("text", text) } to null
            }
        return when (result) {
            is LlmResult.Success ->
                LlmResult.Success(
                    result.value["text"]!!.jsonPrimitive.content,
                    result.usage
                )
            is LlmResult.Failure -> result
        }
    }

    /**
     * Shared POST /v1/messages + error mapping + usage parsing. [extract] pulls the result JSON.
     */
    private suspend fun request(
        credential: LlmCredential,
        body: JsonObject,
        extract: (JsonObject) -> Pair<JsonObject?, String?>
    ): LlmResult<JsonObject> =
        try {
            val response =
                httpClient.post("$baseUrl/v1/messages") {
                    authHeaders(credential).forEach { (k, v) -> header(k, v) }
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
                            "Anthropic response was not a JSON object"
                        )
                    )
                } else {
                    val usage = usageFrom(root)
                    val (value, error) =
                        runCatching { extract(root) }
                            .getOrElse { null to "could not parse Anthropic response" }
                    if (value == null) {
                        LlmResult.Failure(
                            LlmError(
                                LlmErrorKind.INVALID_OUTPUT,
                                error ?: "could not parse Anthropic response"
                            ),
                            usage
                        )
                    } else {
                        LlmResult.Success(value, usage)
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
        return TokenUsage(
            inputTokens =
            runCatching { usage["input_tokens"]?.jsonPrimitive?.intOrNull }
                .getOrNull()
                ?.coerceAtLeast(0) ?: 0,
            outputTokens =
            runCatching { usage["output_tokens"]?.jsonPrimitive?.intOrNull }
                .getOrNull()
                ?.coerceAtLeast(0) ?: 0
        )
    }

    override suspend fun embed(input: EmbeddingInput): LlmResult<EmbeddingBatch> {
        // Anthropic does not provide a first-party embeddings endpoint; route embeddings to
        // OpenAI/Voyage via ModelRouter instead. Returning UNSUPPORTED is intentional.
        return LlmResult.Failure(
            LlmError(LlmErrorKind.UNSUPPORTED, "Anthropic has no embeddings endpoint")
        )
    }

    override suspend fun transcribe(input: TranscriptionInput): LlmResult<TranscriptArtifact> {
        return LlmResult.Failure(
            LlmError(LlmErrorKind.UNSUPPORTED, "Anthropic has no transcription endpoint")
        )
    }
}
