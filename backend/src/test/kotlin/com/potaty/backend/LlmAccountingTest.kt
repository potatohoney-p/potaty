/*
 * Copyright (c) 2026, Potaty
 */

package com.potaty.backend

import com.potaty.backend.api.DiagramJobRequest
import com.potaty.backend.cost.CostEstimator
import com.potaty.backend.diagram.DiagramPipeline
import com.potaty.backend.llm.LlmDiagramEnricher
import com.potaty.backend.llm.auth.ApiKeyCredential
import com.potaty.backend.llm.provider.EmbeddingBatch
import com.potaty.backend.llm.provider.EmbeddingInput
import com.potaty.backend.llm.provider.LlmError
import com.potaty.backend.llm.provider.LlmErrorKind
import com.potaty.backend.llm.provider.LlmProvider
import com.potaty.backend.llm.provider.LlmResult
import com.potaty.backend.llm.provider.ProviderId
import com.potaty.backend.llm.provider.StructuredCaller
import com.potaty.backend.llm.provider.StructuredGenerationInput
import com.potaty.backend.llm.provider.TextGenerationInput
import com.potaty.backend.llm.provider.TokenUsage
import com.potaty.backend.llm.provider.TranscriptArtifact
import com.potaty.backend.llm.provider.TranscriptionInput
import com.potaty.backend.source.Chunker
import com.potaty.backend.source.SourceNormalizer
import com.potaty.ir.DiagramType
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

class LlmAccountingTest {

    @Test
    fun deploymentCredentialResolverBindsToRequestedWorkspace() = runBlocking {
        val workspaceId = UUID.randomUUID()
        var resolvedWorkspace: String? = null
        val enricher =
            LlmDiagramEnricher(
                provider =
                SequenceProvider(
                    mutableListOf(LlmResult.Success(graphOutput(), TokenUsage(1, 1)))
                ),
                model = "mock",
                credentialResolver = { requestedWorkspace ->
                    resolvedWorkspace = requestedWorkspace
                    credential(UUID.fromString(requestedWorkspace))
                }
            )

        enricher.enrich(
            "Potaty converts source material into a diagram.",
            DiagramType.ARCHITECTURE,
            workspaceId.toString()
        )

        assertEquals(workspaceId.toString(), resolvedWorkspace)
    }

    @Test
    fun structuredRepairAttemptsAccumulateBillableUsage() = runBlocking {
        val provider =
            SequenceProvider(
                mutableListOf(
                    LlmResult.Success(buildJsonObject { put("invalid", true) }, TokenUsage(10, 2)),
                    LlmResult.Success(
                        buildJsonObject { putJsonArray("nodes") {} },
                        TokenUsage(20, 3)
                    )
                )
            )
        val input = structuredInput()
        val result =
            StructuredCaller(provider, maxRepairAttempts = 1).call(input) { output ->
                if (output["nodes"] == null) "missing nodes" else null
            }

        assertTrue(result is LlmResult.Success)
        assertEquals(30, result.usage.inputTokens)
        assertEquals(5, result.usage.outputTokens)
    }

    @Test
    fun enrichmentRecordsUsageProvenanceCreatorAndRequestedRendering() = runBlocking {
        val config = testConfig()
        val graph = AppGraph.create(config)
        try {
            val workspaceId = UUID.fromString(config.auth.devWorkspaceId)
            val projectId = UUID.fromString(config.auth.devProjectId)
            val userId = UUID.fromString(config.auth.devUserId)
            val jobId = UUID.randomUUID()
            val normalized =
                SourceNormalizer.normalize("Potaty turns source material into diagrams.")
            val source =
                graph.sources.createSource(
                    workspaceId,
                    projectId,
                    "TEXT_PASTE",
                    "sparse",
                    "{}",
                    userId
                )
            val sourceVersion =
                graph.sources.createVersion(
                    workspaceId,
                    source.id,
                    "sha256:${normalized.contentHash}",
                    null,
                    null,
                    "{}"
                )
            graph.sources.saveChunks(
                workspaceId,
                sourceVersion.id,
                Chunker.chunk(normalized.canonicalText)
            )

            val provider =
                SequenceProvider(
                    mutableListOf(
                        LlmResult.Success(graphOutput(), TokenUsage(100, 20, 7))
                    )
                )
            val enricher =
                LlmDiagramEnricher(
                    provider = provider,
                    credential = credential(workspaceId),
                    model = "mock-structured-v1"
                )
            val pipeline =
                DiagramPipeline(
                    sources = graph.sources,
                    diagrams = graph.diagrams,
                    identities = graph.identities,
                    json = graph.json,
                    enricher = enricher,
                    usageRecorder = graph.usage,
                    costEstimator = CostEstimator()
                )

            val result =
                pipeline.generate(
                    workspaceId = workspaceId,
                    projectId = projectId,
                    createdBy = userId,
                    request =
                    DiagramJobRequest(
                        sourceVersionIds = listOf(sourceVersion.id.toString()),
                        diagramType = DiagramType.ARCHITECTURE,
                        objective = "Architecture api_key=sk-${"T".repeat(40)}",
                        outputFormats = listOf("d2")
                    ),
                    jobId = jobId
                )

            assertTrue(graph.usage.sumCostThisMonth(workspaceId) > 0.0)
            assertFalse("sk-" in result.ir.title)
            assertFalse("sk-" in result.ir.objective.orEmpty())
            assertTrue("[REDACTED:" in result.ir.title)
            val stored = graph.diagrams.findVersion(workspaceId, result.diagramId, result.versionId)
            assertNotNull(stored)
            assertEquals(userId, stored.createdBy)
            val trace =
                graph.json
                    .parseToJsonElement(stored.modelTraceJson)
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals("mock", trace["provider"]!!.jsonPrimitive.content)
            assertEquals("mock-structured-v1", trace["model"]!!.jsonPrimitive.content)
            assertEquals(100, trace["inputTokens"]!!.jsonPrimitive.content.toInt())
            assertEquals("true", trace["applied"]!!.jsonPrimitive.content)
            assertEquals(
                listOf("d2"),
                graph.diagrams.listRenderings(workspaceId, result.versionId).map { it.format }
            )
        } finally {
            graph.stop()
        }
    }

    private fun structuredInput() =
        StructuredGenerationInput(
            credential = credential(UUID.randomUUID()),
            model = "mock",
            parts = emptyList(),
            jsonSchema = buildJsonObject { put("type", "object") }
        )

    private fun credential(workspaceId: UUID) =
        ApiKeyCredential(
            id = "test",
            workspaceId = workspaceId.toString(),
            provider = ProviderId.MOCK,
            encryptedApiKeyRef = "unused",
            label = "test",
            createdByUserId = "test"
        )

    private fun graphOutput(): JsonObject = buildJsonObject {
        putJsonArray("nodes") {
            add(
                buildJsonObject {
                    put("id", "source")
                    put("label", "Source material")
                    put("type", "SERVICE")
                }
            )
            add(
                buildJsonObject {
                    put("id", "diagram")
                    put("label", "Diagram")
                    put("type", "SERVICE")
                }
            )
        }
        putJsonArray("edges") {
            add(
                buildJsonObject {
                    put("from", "source")
                    put("to", "diagram")
                    put("label", "becomes")
                    put("type", "CALLS")
                }
            )
        }
    }

    private class SequenceProvider(
        private val structured: MutableList<LlmResult<JsonObject>>
    ) : LlmProvider {
        override val providerId: ProviderId = ProviderId.MOCK

        override suspend fun generateStructured(
            input: StructuredGenerationInput
        ): LlmResult<JsonObject> = structured.removeAt(0)

        override suspend fun generateText(input: TextGenerationInput): LlmResult<String> =
            unsupported()

        override suspend fun embed(input: EmbeddingInput): LlmResult<EmbeddingBatch> = unsupported()

        override suspend fun transcribe(
            input: TranscriptionInput
        ): LlmResult<TranscriptArtifact> = unsupported()

        private fun unsupported() =
            LlmResult.Failure(
                LlmError(LlmErrorKind.UNSUPPORTED, "not used by this deterministic test")
            )
    }
}
