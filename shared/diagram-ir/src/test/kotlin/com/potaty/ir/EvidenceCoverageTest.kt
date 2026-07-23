/*
 * Copyright (c) 2026, Potaty
 */

package com.potaty.ir

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Coverage math for [EvidenceCoverageScorer]. Exercises the node/edge coverage ratios, the
 * grounded-vs-inferred edge accounting, low-confidence counts, the empty-graph convention
 * (ratio of 0/0 == 1.0), and the publish threshold gate.
 */
class EvidenceCoverageTest {

    private fun ev() = EvidenceRef(sourceChunkId = "chk")

    private fun node(
        id: String,
        evidence: List<EvidenceRef> = emptyList(),
        userModified: Boolean = false,
        confidence: Double = 1.0
    ) =
        IrNode(
            id = id,
            label = id,
            evidence = evidence,
            userModified = userModified,
            confidence = confidence
        )

    private fun edge(
        id: String,
        from: String,
        to: String,
        evidence: List<EvidenceRef> = emptyList(),
        sourceType: EdgeSourceType = EdgeSourceType.LLM_INFERRED,
        confidence: Double = 1.0
    ) = IrEdge(
        id = id,
        from = from,
        to = to,
        evidence = evidence,
        edgeSourceType = sourceType,
        confidence = confidence
    )

    private fun ir(nodes: List<IrNode>, edges: List<IrEdge> = emptyList()) =
        DiagramIR(
            diagramId = "d",
            title = "t",
            diagramType = DiagramType.ARCHITECTURE,
            nodes = nodes,
            edges = edges
        )

    @Test
    fun oneOfTwoNodesWithEvidenceGivesHalfCoverage() {
        val coverage = EvidenceCoverageScorer.score(
            ir(nodes = listOf(node("a", evidence = listOf(ev())), node("b")))
        )
        assertEquals(0.5, coverage.nodeCoverage, "1 of 2 grounded nodes -> 0.5 node coverage")
    }

    @Test
    fun userModifiedNodeCountsAsCovered() {
        val coverage = EvidenceCoverageScorer.score(
            ir(nodes = listOf(node("a", userModified = true), node("b")))
        )
        assertEquals(0.5, coverage.nodeCoverage, "user-modified node counts as covered")
    }

    @Test
    fun allNodesGroundedGivesFullCoverage() {
        val coverage = EvidenceCoverageScorer.score(
            ir(
                nodes = listOf(
                    node("a", evidence = listOf(ev())),
                    node("b", evidence = listOf(ev()))
                )
            )
        )
        assertEquals(1.0, coverage.nodeCoverage)
    }

    @Test
    fun edgeCoverageCountsEvidenceAndUserConfirmed() {
        // e1 has evidence (covered), e2 is USER_CONFIRMED with no evidence (covered),
        // e3 is plain LLM_INFERRED with no evidence (not covered) -> 2 of 3 = ~0.6667.
        val coverage = EvidenceCoverageScorer.score(
            ir(
                nodes = listOf(node("a"), node("b")),
                edges = listOf(
                    edge("e1", "a", "b", evidence = listOf(ev())),
                    edge("e2", "a", "b", sourceType = EdgeSourceType.USER_CONFIRMED),
                    edge("e3", "a", "b")
                )
            )
        )
        assertEquals(2.0 / 3.0, coverage.edgeCoverage, "2 of 3 edges covered")
    }

    @Test
    fun groundedRatioAndInferredCountAreTracked() {
        // e1 STATIC_IMPORT (grounded), e2 EXPLICIT_CALL (grounded), e3 LLM_INFERRED (not grounded).
        val coverage = EvidenceCoverageScorer.score(
            ir(
                nodes = listOf(node("a"), node("b")),
                edges = listOf(
                    edge("e1", "a", "b", sourceType = EdgeSourceType.STATIC_IMPORT),
                    edge("e2", "a", "b", sourceType = EdgeSourceType.EXPLICIT_CALL),
                    edge("e3", "a", "b", sourceType = EdgeSourceType.LLM_INFERRED)
                )
            )
        )
        assertEquals(2.0 / 3.0, coverage.groundedEdgeRatio, "2 of 3 edges grounded")
        assertEquals(1, coverage.inferredEdgeCount, "exactly one LLM-inferred edge")
    }

    @Test
    fun lowConfidenceNodesAndEdgesAreCounted() {
        // LOW_CONFIDENCE threshold is 0.7 (strictly less-than).
        val coverage = EvidenceCoverageScorer.score(
            ir(
                nodes = listOf(
                    node("a", confidence = 0.5), // low
                    node("b", confidence = 0.7), // boundary -> NOT low
                    node("c", confidence = 0.9) // high
                ),
                edges = listOf(
                    edge("e1", "a", "b", confidence = 0.3), // low
                    edge("e2", "a", "c", confidence = 1.0) // high
                )
            )
        )
        assertEquals(1, coverage.lowConfidenceNodeCount, "only confidence < 0.7 counts as low")
        assertEquals(1, coverage.lowConfidenceEdgeCount)
    }

    @Test
    fun emptyGraphScoresFullCoverageByConvention() {
        val coverage = EvidenceCoverageScorer.score(ir(nodes = emptyList(), edges = emptyList()))
        assertEquals(1.0, coverage.nodeCoverage, "0/0 nodes -> 1.0 by convention")
        assertEquals(1.0, coverage.edgeCoverage, "0/0 edges -> 1.0 by convention")
        assertEquals(1.0, coverage.groundedEdgeRatio)
        assertEquals(0, coverage.inferredEdgeCount)
    }

    @Test
    fun meetsThresholdGate() {
        // 1 of 2 nodes covered (0.5) fails the default 0.90 node-coverage gate.
        val failing = EvidenceCoverageScorer.score(
            ir(nodes = listOf(node("a", evidence = listOf(ev())), node("b")))
        )
        assertFalse(failing.meetsThreshold(), "0.5 node coverage must fail the 0.90 gate")

        // Fully grounded nodes + all edges covered passes.
        val passing = EvidenceCoverageScorer.score(
            ir(
                nodes = listOf(
                    node("a", evidence = listOf(ev())),
                    node("b", evidence = listOf(ev()))
                ),
                edges = listOf(edge("e1", "a", "b", sourceType = EdgeSourceType.USER_CONFIRMED))
            )
        )
        assertTrue(
            passing.meetsThreshold(),
            "full coverage with no critical claims must pass the gate"
        )
    }
}
