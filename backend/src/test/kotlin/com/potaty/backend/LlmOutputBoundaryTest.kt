/*
 * Copyright (c) 2026, Potaty
 */

package com.potaty.backend

import com.potaty.backend.llm.LlmDiagramEnricher
import com.potaty.backend.llm.auth.ApiKeyCredential
import com.potaty.backend.llm.provider.MockProvider
import com.potaty.backend.llm.provider.ProviderId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

class LlmOutputBoundaryTest {
    private val enricher =
        LlmDiagramEnricher(
            provider = MockProvider(),
            credential =
            ApiKeyCredential(
                id = "test",
                workspaceId = "workspace",
                provider = ProviderId.MOCK,
                encryptedApiKeyRef = "unused",
                label = "test",
                createdByUserId = "user"
            ),
            model = "mock"
        )

    @Test
    fun boundsAndSanitizesProviderControlledGraphFields() {
        val output = buildJsonObject {
            putJsonArray("nodes") {
                repeat(30) { index ->
                    add(
                        buildJsonObject {
                            put("id", "provider-id-$index\nignored")
                            put("label", "node $index\n" + "x".repeat(200))
                            put("type", "SERVICE")
                        }
                    )
                }
            }
            put("edges", buildJsonArray {})
        }

        val extraction = requireNotNull(enricher.mapToExtraction(output))
        assertEquals(18, extraction.entities.size)
        assertTrue(extraction.entities.all { it.key.matches(Regex("llm_n\\d+")) })
        assertTrue(extraction.entities.all { it.label.length <= 120 })
        assertFalse(extraction.entities.any { '\n' in it.label || "provider-id" in it.key })
    }
}
