/*
 * Copyright (c) 2026, Potaty
 *
 * WS14 evaluation runner (plan section 18 / 21.3). Runs the GROUNDED text -> DiagramIR pipeline
 * ([DiagramPipeline.generate]) on each [EvalCorpus] fixture against a live [AppGraph] (H2 in tests)
 * and computes [EvalMetrics]. No LLM and no network are involved: the runner ingests each fixture's
 * text through the same normalize -> chunk -> persist path the API uses, then generates and scores.
 *
 * The runner does NOT own the [AppGraph] lifecycle -- callers build the graph (via testConfig() in
 * tests) and stop() it. This keeps eval usable both as a test gate and as a future CLI/eval-worker.
 */

package com.potaty.backend.eval

import com.potaty.backend.AppGraph
import com.potaty.backend.api.DiagramJobRequest
import com.potaty.backend.source.Chunker
import com.potaty.backend.source.SourceNormalizer
import java.util.UUID

object EvalRunner {

    /**
     * Ingests one fixture into [graph], generates a diagram, and scores it.
     *
     * @param workspaceId tenant to ingest/generate under (every query is workspace-scoped).
     * @param createdBy optional user id recorded on the source/diagram (may be null).
     */
    suspend fun runFixture(
        graph: AppGraph,
        fixture: EvalFixture,
        workspaceId: UUID,
        projectId: UUID,
        createdBy: UUID? = null
    ): EvalMetrics {
        // --- ingest: normalize -> chunk -> persist (same path as the source API) ---
        val normalized = SourceNormalizer.normalize(fixture.sourceText)
        val chunks = Chunker.chunk(normalized.canonicalText)
        val source = graph.sources.createSource(
            workspaceId = workspaceId,
            projectId = projectId,
            sourceType = "TEXT_PASTE",
            displayName = "eval:${fixture.id}",
            externalRefJson = "{}",
            createdBy = createdBy
        )
        val version = graph.sources.createVersion(
            workspaceId = workspaceId,
            sourceId = source.id,
            contentHash = "sha256:" + normalized.contentHash,
            normalizedTextObjectKey = null,
            rawObjectKey = null,
            metadataJson = "{}"
        )
        graph.sources.saveChunks(workspaceId, version.id, chunks)

        // --- generate (grounded, deterministic) ---
        val request = DiagramJobRequest(
            sourceVersionIds = listOf(version.id.toString()),
            diagramType = fixture.diagramType,
            objective = fixture.objective,
            outputFormats = listOf("mermaid")
        )
        val result = graph.diagramPipeline.generate(
            workspaceId = workspaceId,
            projectId = projectId,
            createdBy = createdBy,
            request = request
        )

        // --- score against the fixture's ground truth ---
        return EvalMetricsCalculator.compute(result.ir, fixture)
    }

    /**
     * Runs the entire [EvalCorpus] (or a supplied fixture list) and returns the aggregate report.
     */
    suspend fun runCorpus(
        graph: AppGraph,
        workspaceId: UUID,
        projectId: UUID,
        createdBy: UUID? = null,
        fixtures: List<EvalFixture> = EvalCorpus.ALL
    ): EvalReport {
        val perFixture = fixtures.map { runFixture(graph, it, workspaceId, projectId, createdBy) }
        return EvalReport(perFixture)
    }
}
