/*
 * Copyright (c) 2026, Potaty
 *
 * End-to-end test of the grounded text -> DiagramIR pipeline (WS5 + WS7 + WS10) against an
 * embedded H2 database, with no LLM: ingest -> extract -> assemble -> validate -> render ->
 * persist -> reload. Proves the core value path works deterministically.
 */

package com.potaty.backend

import com.potaty.backend.api.DiagramJobRequest
import com.potaty.backend.diagram.diagramJobPipeline
import com.potaty.backend.jobs.JobContext
import com.potaty.backend.jobs.StageResult
import com.potaty.backend.source.Chunker
import com.potaty.backend.source.SourceNormalizer
import com.potaty.ir.DiagramIR
import com.potaty.ir.DiagramType
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DiagramPipelineTest {

    private val sampleText =
        """
        User -> API Gateway: login request
        API Gateway -> Auth Service: verify credentials
        Auth Service -> Postgres: read user row
        API Gateway calls Billing Service
        Billing Service writes Postgres
        Billing Service publishes Events Queue
        """
            .trimIndent()

    @Test
    fun unsupportedRendererDoesNotSilentlyFallbackToMermaid() {
        val graph = AppGraph.create(testConfig())
        try {
            val failure =
                assertFailsWith<IllegalArgumentException> {
                    graph.diagramPipeline.compileRenderings(
                        DiagramIR(
                            diagramId = "d1",
                            title = "Unsupported format",
                            diagramType = DiagramType.ARCHITECTURE
                        ),
                        listOf("ascii")
                    )
                }
            assertTrue(failure.message.orEmpty().contains("ascii"))
        } finally {
            graph.stop()
        }
    }

    @Test
    fun textToDiagramEndToEnd() = runBlocking {
        val config = testConfig()
        val graph = AppGraph.create(config)
        try {
            val workspaceId = UUID.fromString(config.auth.devWorkspaceId)
            val userId = UUID.fromString(config.auth.devUserId)
            val projectId = UUID.fromString(config.auth.devProjectId)

            // --- ingest ---
            val normalized = SourceNormalizer.normalize(sampleText)
            val chunks = Chunker.chunk(normalized.canonicalText)
            assertTrue(chunks.isNotEmpty(), "chunker should produce chunks")
            val source =
                graph.sources.createSource(
                    workspaceId,
                    projectId,
                    "TEXT_PASTE",
                    "demo",
                    "{}",
                    userId
                )
            val version =
                graph.sources.createVersion(
                    workspaceId,
                    source.id,
                    "sha256:" + normalized.contentHash,
                    null,
                    null,
                    "{}"
                )
            graph.sources.saveChunks(workspaceId, version.id, chunks)

            // --- generate ---
            val request =
                DiagramJobRequest(
                    sourceVersionIds = listOf(version.id.toString()),
                    diagramType = DiagramType.ARCHITECTURE,
                    objective = "Explain the login + billing flow",
                    outputFormats = listOf("mermaid", "d2")
                )
            val result = graph.diagramPipeline.generate(workspaceId, projectId, userId, request)

            // --- assert IR + validation ---
            assertTrue(
                result.ir.nodes.size >= 5,
                "expected the major services as nodes, got ${result.ir.nodes.map { it.label }}"
            )
            assertTrue(result.ir.edges.isNotEmpty(), "expected relations as edges")
            assertTrue(
                result.validation.valid,
                "IR should be structurally valid; violations=${result.validation.violations}"
            )
            assertTrue(result.coverage.nodeCoverage > 0.99, "all nodes should be evidence-grounded")

            // --- assert renderings ---
            val mermaid = result.renderings.firstOrNull { it.format == "mermaid" }?.contentText
            assertNotNull(mermaid, "mermaid rendering present")
            assertTrue(
                mermaid.contains("flowchart") && mermaid.contains("-->"),
                "mermaid is a flowchart: $mermaid"
            )
            assertTrue(result.renderings.any { it.format == "d2" }, "d2 rendering present")
            assertEquals(
                setOf("d2", "mermaid"),
                graph.diagrams
                    .listRenderings(workspaceId, result.versionId)
                    .map { it.format }
                    .toSet()
            )

            // --- assert persistence round-trip ---
            val loaded = graph.diagrams.findVersion(workspaceId, result.diagramId, result.versionId)
            assertNotNull(loaded, "persisted version should be retrievable")
            assertEquals(result.irJson, loaded.irJson, "stored IR matches")

            // --- tenant isolation: another workspace cannot read it ---
            val otherWs = UUID.randomUUID()
            assertEquals(
                null,
                graph.diagrams.findVersion(otherWs, result.diagramId, result.versionId)
            )
        } finally {
            graph.stop()
        }
    }

    @Test
    fun jobPipelineAdapterProducesVersion() = runBlocking {
        val config = testConfig()
        val graph = AppGraph.create(config)
        try {
            val workspaceId = UUID.fromString(config.auth.devWorkspaceId)
            val userId = UUID.fromString(config.auth.devUserId)
            val projectId = UUID.fromString(config.auth.devProjectId)
            val normalized = SourceNormalizer.normalize(sampleText)
            val source =
                graph.sources.createSource(
                    workspaceId,
                    projectId,
                    "TEXT_PASTE",
                    "demo",
                    "{}",
                    userId
                )
            val version =
                graph.sources.createVersion(
                    workspaceId,
                    source.id,
                    "sha256:" + normalized.contentHash,
                    null,
                    null,
                    "{}"
                )
            graph.sources.saveChunks(
                workspaceId,
                version.id,
                Chunker.chunk(normalized.canonicalText)
            )

            val request =
                DiagramJobRequest(
                    sourceVersionIds = listOf(version.id.toString()),
                    diagramType = DiagramType.ARCHITECTURE,
                    outputFormats = listOf("mermaid")
                )
            val pipeline = diagramJobPipeline(graph.diagramPipeline, graph.json)
            val ctx =
                JobContext(
                    jobId = UUID.randomUUID(),
                    workspaceId = workspaceId,
                    projectId = projectId,
                    createdBy = userId
                ) { _, _ -> }
            val result =
                pipeline.run(
                    graph.json.encodeToString(DiagramJobRequest.serializer(), request),
                    ctx
                )

            assertTrue(result is StageResult.Ok, "worker pipeline should succeed: $result")
            val output =
                graph.json.parseToJsonElement(result.value as String).jsonObject
            val diagramId = UUID.fromString(output["diagramId"]!!.jsonPrimitive.content)
            val versionId = UUID.fromString(output["versionId"]!!.jsonPrimitive.content)
            val stored = graph.diagrams.findVersion(workspaceId, diagramId, versionId)
            assertNotNull(stored, "version persisted by worker path")
            assertEquals(userId, stored.createdBy, "worker preserves the authenticated creator")

            // Replaying the same claimed job must reuse the exact all-or-nothing artifact rather
            // than invoke another persistence path and append a second version.
            val replay =
                pipeline.run(
                    graph.json.encodeToString(DiagramJobRequest.serializer(), request),
                    ctx
                )
            assertTrue(replay is StageResult.Ok)
            assertEquals(result.value, replay.value, "job replay returns identical output")
            val generated = graph.diagrams.findGeneratedArtifact(workspaceId, ctx.jobId)
            assertNotNull(generated)
            assertEquals(diagramId, generated.diagram.id)
            assertEquals(versionId, generated.version.id)
            assertEquals(1, generated.version.versionNumber)
            assertEquals(ctx.jobId, generated.diagram.generationJobId)
            assertEquals(listOf("mermaid"), generated.renderings.map { it.format })
        } finally {
            graph.stop()
        }
    }
}
