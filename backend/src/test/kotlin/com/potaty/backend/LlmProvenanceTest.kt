/*
 * Copyright (c) 2026, Potaty
 */

package com.potaty.backend

import com.potaty.backend.diagram.IrAssembler
import com.potaty.backend.extraction.ExtractedEntity
import com.potaty.backend.extraction.ExtractedRelation
import com.potaty.backend.extraction.ExtractionMerger
import com.potaty.backend.extraction.ExtractionResult
import com.potaty.ir.DiagramType
import com.potaty.ir.EdgeSourceType
import com.potaty.ir.EdgeType
import com.potaty.ir.EvidenceRef
import com.potaty.ir.NodeType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LlmProvenanceTest {

    private val evidence =
        EvidenceRef(
            sourceChunkId = "chunk-1",
            path = "README.md",
            startLine = 1,
            endLine = 1,
            quote = "API calls Auth",
            quoteHash = "hash"
        )

    @Test
    fun inferredAdditionsNeverReplaceOrMasqueradeAsGroundedExtraction() {
        val grounded =
            ExtractionResult(
                entities =
                listOf(
                    ExtractedEntity("api", "API", NodeType.SERVICE, listOf(evidence)),
                    ExtractedEntity("auth", "Auth", NodeType.SERVICE, listOf(evidence))
                ),
                relations =
                listOf(
                    ExtractedRelation("api", "auth", EdgeType.CALLS, "calls", listOf(evidence))
                )
            )
        val inferred =
            ExtractionResult(
                entities =
                listOf(
                    ExtractedEntity(
                        "auth",
                        "Auth rewritten by model",
                        NodeType.GENERIC,
                        listOf(evidence)
                    ),
                    ExtractedEntity("queue", "Queue", NodeType.QUEUE, listOf(evidence))
                ),
                relations =
                listOf(
                    ExtractedRelation(
                        "auth",
                        "queue",
                        EdgeType.PUBLISHES,
                        "events",
                        listOf(evidence)
                    )
                )
            )

        val merge = ExtractionMerger.merge(grounded, inferred)
        val ir =
            IrAssembler.assemble(
                diagramId = "diagram-1",
                title = "System",
                diagramType = DiagramType.ARCHITECTURE,
                objective = null,
                sourceSnapshotIds = listOf("source-1"),
                extraction = merge.extraction,
                generatedBy =
                if (merge.hasInferredAdditions) {
                    "deterministic-extractor+llm-enricher"
                } else {
                    "deterministic-extractor"
                }
            )

        assertEquals(setOf("API", "Auth", "Queue"), ir.nodes.map { it.label }.toSet())
        assertTrue(ir.nodes.single { it.label == "Auth" }.evidence.isNotEmpty())
        val inferredNode = ir.nodes.single { it.label == "Queue" }
        assertTrue(inferredNode.evidence.isEmpty(), "model-provided evidence must never be trusted")
        assertTrue(inferredNode.confidence < 0.7)

        val groundedEdge =
            ir.edges.single { it.from == ir.nodes.single { n -> n.label == "API" }.id }
        assertEquals(EdgeSourceType.DOCUMENT_STATEMENT, groundedEdge.edgeSourceType)
        assertTrue(groundedEdge.evidence.isNotEmpty())

        val inferredEdge =
            ir.edges.single { it.from == ir.nodes.single { n -> n.label == "Auth" }.id }
        assertEquals(EdgeSourceType.LLM_INFERRED, inferredEdge.edgeSourceType)
        assertTrue(inferredEdge.evidence.isEmpty(), "model-provided evidence must be discarded")
        assertTrue(inferredEdge.confidence < 0.7)
        assertEquals("deterministic-extractor+llm-enricher", ir.provenance.generatedBy)
    }
}
