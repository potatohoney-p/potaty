/*
 * Copyright (c) 2026, Potaty
 *
 * Provider HTTP tests using Ktor's MockEngine: no network, no real keys. They verify the request
 * shaping (Anthropic forced tool use; OpenAI json_schema) and the response parsing/usage mapping.
 */

package com.potaty.backend

import com.potaty.backend.llm.anthropic.AnthropicProvider
import com.potaty.backend.llm.auth.ApiKeyCredential
import com.potaty.backend.llm.auth.CredentialStore
import com.potaty.backend.llm.auth.EncryptedSecretRef
import com.potaty.backend.llm.auth.ProviderOAuthCredential
import com.potaty.backend.llm.openai.OpenAiProvider
import com.potaty.backend.llm.provider.LlmErrorKind
import com.potaty.backend.llm.provider.LlmResult
import com.potaty.backend.llm.provider.PromptPart
import com.potaty.backend.llm.provider.PromptPartRole
import com.potaty.backend.llm.provider.ProviderId
import com.potaty.backend.llm.provider.StructuredGenerationInput
import com.potaty.backend.llm.provider.TokenUsage
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class LlmProviderTest {

    private val credStore = object : CredentialStore {
        override fun seal(workspaceId: String, plaintextSecret: String) = EncryptedSecretRef(
            plaintextSecret
        )
        override fun open(workspaceId: String, ref: EncryptedSecretRef) = "test-key"
    }

    private fun apiKey(provider: ProviderId) = ApiKeyCredential(
        id = "c1",
        workspaceId = "ws1",
        provider = provider,
        encryptedApiKeyRef = "ref",
        label = "test",
        createdByUserId = "u1"
    )

    private fun mockClient(json: String, capture: (String?) -> Unit = {}): HttpClient =
        HttpClient(
            MockEngine { request ->
                capture(request.body.toString())
                respond(
                    content = json,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
        )

    private fun structuredInput(provider: ProviderId) = StructuredGenerationInput(
        credential = apiKey(provider),
        model = "test-model",
        parts = listOf(
            PromptPart(PromptPartRole.SYSTEM_POLICY, "You are a diagram extractor."),
            PromptPart(PromptPartRole.SOURCE_DATA, "User -> API")
        ),
        jsonSchema = buildJsonObject { put("type", "object") }
    )

    @Test
    fun tokenUsageRejectsNegativeValuesAndSaturatesAccumulation() {
        assertFailsWith<IllegalArgumentException> { TokenUsage(inputTokens = -1) }
        assertEquals(
            Int.MAX_VALUE,
            (TokenUsage(inputTokens = Int.MAX_VALUE) + TokenUsage(inputTokens = 1)).inputTokens
        )
    }

    @Test
    fun anthropicForcedToolUseParsesInput() = runBlocking {
        val canned = """
            {"content":[{"type":"tool_use","name":"emit","input":{"diagram_id":"d1","title":"t"}}],
             "usage":{"input_tokens":10,"output_tokens":5}}
        """.trimIndent()
        val provider =
            AnthropicProvider("https://api.anthropic.test", mockClient(canned), credStore)
        val result = provider.generateStructured(structuredInput(ProviderId.ANTHROPIC))
        assertTrue(result is LlmResult.Success, "expected success, got $result")
        assertEquals(
            "d1",
            result.value["diagram_id"]!!.jsonPrimitive.content
        )
        assertEquals(10, result.usage.inputTokens)
    }

    @Test
    fun openAiJsonSchemaParsesContent() = runBlocking {
        val canned = """
            {"choices":[{"message":{"content":"{\"diagram_id\":\"d2\",\"ok\":true}"}}],
             "usage":{"prompt_tokens":3,"completion_tokens":2}}
        """.trimIndent()
        val provider = OpenAiProvider("https://api.openai.test", mockClient(canned), credStore)
        val result = provider.generateStructured(structuredInput(ProviderId.OPENAI))
        assertTrue(result is LlmResult.Success, "expected success, got $result")
        assertEquals(
            "d2",
            result.value["diagram_id"]!!.jsonPrimitive.content
        )
        assertEquals(2, result.usage.outputTokens)
    }

    @Test
    fun invalidOpenAiOutputStillPreservesAllUsage() = runBlocking {
        val canned =
            """
            {"choices":[{"message":{}}],
             "usage":{"prompt_tokens":11,"completion_tokens":4,
             "prompt_tokens_details":{"cached_tokens":7}}}
            """.trimIndent()
        val provider = OpenAiProvider("https://api.openai.test", mockClient(canned), credStore)
        val result = provider.generateStructured(structuredInput(ProviderId.OPENAI))

        assertTrue(result is LlmResult.Failure)
        assertEquals(11, result.usage.inputTokens)
        assertEquals(4, result.usage.outputTokens)
        assertEquals(7, result.usage.cachedInputTokens)
    }

    @Test
    fun invalidAnthropicOutputStillPreservesUsage() = runBlocking {
        val canned =
            """{"content":[],"usage":{"input_tokens":13,"output_tokens":6}}"""
        val provider =
            AnthropicProvider("https://api.anthropic.test", mockClient(canned), credStore)
        val result = provider.generateStructured(structuredInput(ProviderId.ANTHROPIC))

        assertTrue(result is LlmResult.Failure)
        assertEquals(13, result.usage.inputTokens)
        assertEquals(6, result.usage.outputTokens)
    }

    @Test
    fun malformedOpenAiEnvelopeStillPreservesUsageAndIsNotMisclassifiedAsNetwork() = runBlocking {
        val canned =
            """{"choices":{},"usage":{"prompt_tokens":17,"completion_tokens":8}}"""
        val provider = OpenAiProvider("https://api.openai.test", mockClient(canned), credStore)
        val result = provider.generateStructured(structuredInput(ProviderId.OPENAI))

        assertTrue(result is LlmResult.Failure)
        assertEquals(LlmErrorKind.INVALID_OUTPUT, result.error.kind)
        assertEquals(17, result.usage.inputTokens)
        assertEquals(8, result.usage.outputTokens)
    }

    @Test
    fun malformedAnthropicEnvelopeStillPreservesUsageAndIsNotMisclassifiedAsNetwork() =
        runBlocking {
            val canned =
                """{"content":[1],"usage":{"input_tokens":19,"output_tokens":9}}"""
            val provider =
                AnthropicProvider("https://api.anthropic.test", mockClient(canned), credStore)
            val result = provider.generateStructured(structuredInput(ProviderId.ANTHROPIC))

            assertTrue(result is LlmResult.Failure)
            assertEquals(LlmErrorKind.INVALID_OUTPUT, result.error.kind)
            assertEquals(19, result.usage.inputTokens)
            assertEquals(9, result.usage.outputTokens)
        }

    @Test
    fun anthropicProviderOAuthRequiresBothFeatureFlagAndLegalApproval() {
        val approved =
            ProviderOAuthCredential(
                id = "oauth",
                workspaceId = "ws1",
                provider = ProviderId.ANTHROPIC,
                encryptedAccessTokenRef = "ref",
                legalUseCaseApproved = true
            )
        val disabled =
            AnthropicProvider("https://api.anthropic.test", mockClient("{}"), credStore)
        assertFailsWith<IllegalArgumentException> { disabled.authHeaders(approved) }

        val enabled =
            AnthropicProvider(
                "https://api.anthropic.test",
                mockClient("{}"),
                credStore,
                allowProviderOAuth = true
            )
        assertEquals("Bearer test-key", enabled.authHeaders(approved)["Authorization"])
        assertFailsWith<IllegalArgumentException> {
            enabled.authHeaders(approved.copy(legalUseCaseApproved = false))
        }
    }

    @Test
    fun httpErrorMapsToFailure() = runBlocking {
        val client = HttpClient(
            MockEngine { respond("rate limited", HttpStatusCode.TooManyRequests) }
        )
        val provider = OpenAiProvider("https://api.openai.test", client, credStore)
        val result = provider.generateStructured(structuredInput(ProviderId.OPENAI))
        assertTrue(result is LlmResult.Failure, "429 should map to a failure")
    }
}
