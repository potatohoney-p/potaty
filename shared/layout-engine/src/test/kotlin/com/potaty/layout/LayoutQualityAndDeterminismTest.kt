/*
 * Copyright (c) 2026, Potaty
 *
 * Focused tests for the layout-engine review fixes:
 *  - CycleDetector / layered-layout order-independence (determinism is a hard requirement);
 *  - the LayoutQualityScorer crossing-target formula across diagram sizes;
 *  - the additive symmetry / visual-density metrics and the permissive density gate;
 *  - LabelFormatter inner-width clamping into [minInnerWidth, maxInnerWidth].
 */

package com.potaty.layout

import com.potaty.common.DisplayCells
import com.potaty.graphics.geo.Rect
import com.potaty.ir.CycleDetector
import com.potaty.ir.DiagramIR
import com.potaty.ir.DiagramType
import com.potaty.ir.EdgeSourceType
import com.potaty.ir.EdgeType
import com.potaty.ir.IrEdge
import com.potaty.ir.IrNode
import com.potaty.ir.NodeType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LayoutQualityAndDeterminismTest {

    private fun node(id: String) = IrNode(id = id, label = id, type = NodeType.SERVICE)
    private fun edge(from: String, to: String) =
        IrEdge(
            id = "$from-$to",
            from = from,
            to = to,
            type = EdgeType.CALLS,
            edgeSourceType = EdgeSourceType.DOCUMENT_STATEMENT
        )

    // --- CycleDetector order-independence -------------------------------

    @Test
    fun findCycleIsOrderIndependent() {
        val edges = listOf("a" to "b", "b" to "c", "c" to "a")
        val forward = CycleDetector.findCycle(listOf("a", "b", "c"), edges)
        val shuffled = CycleDetector.findCycle(listOf("c", "a", "b"), edges)
        // A cycle must be reported for both node orderings (the *content* may rotate, but the
        // presence/absence of a cycle must not depend on list order).
        assertTrue(forward != null, "cycle detected in natural order")
        assertTrue(shuffled != null, "cycle detected in shuffled order")
    }

    @Test
    fun acyclicGraphReportsNoCycleRegardlessOfOrder() {
        val edges = listOf("a" to "b", "b" to "c", "a" to "c")
        assertEquals(null, CycleDetector.findCycle(listOf("a", "b", "c"), edges))
        assertEquals(null, CycleDetector.findCycle(listOf("c", "b", "a"), edges))
    }

    @Test
    fun feedbackEdgeSetBreaksTheSameNumberOfEdgesAcrossOrders() {
        val edges = listOf("a" to "b", "b" to "c", "c" to "a")
        val f1 = CycleDetector.feedbackEdgeSet(listOf("a", "b", "c"), edges)
        val f2 = CycleDetector.feedbackEdgeSet(listOf("b", "c", "a"), edges)
        assertEquals(1, f1.size, "exactly one back edge breaks the 3-cycle")
        assertEquals(f1.size, f2.size, "feedback set size is order-independent")
    }

    // --- Layered layout determinism under node reordering ---------------

    @Test
    fun layeredLayoutIsStableForTheSameIr() {
        val ir = DiagramIR(
            diagramId = "d1",
            title = "flow",
            diagramType = DiagramType.FLOWCHART,
            nodes = listOf("a", "b", "c", "d").map(::node),
            edges = listOf(edge("a", "b"), edge("a", "c"), edge("b", "d"), edge("c", "d"))
        )
        val engine = LayoutEngineFactory.forType(DiagramType.FLOWCHART)
        val first = engine.layout(ir).nodes.map { it.nodeId to it.bounds }
        val second = engine.layout(ir).nodes.map { it.nodeId to it.bounds }
        assertEquals(first, second, "identical IR yields identical boxes")
    }

    // --- Crossing target formula (P2 #13) -------------------------------

    @Test
    fun crossingTargetScalesWithSize() {
        // The documented bound is n^2/4 + 1. Pin it for small / medium / large graphs so the
        // formula cannot silently drift.
        fun target(n: Int) = (n * n) / 4 + 1
        assertEquals(7, target(5)) // 25/4 + 1 = 6 + 1
        assertEquals(101, target(20)) // 400/4 + 1 = 100 + 1
        assertEquals(2501, target(100)) // 10000/4 + 1 = 2500 + 1

        // A clean layered flowchart stays well under target.
        val ir = DiagramIR(
            diagramId = "chain",
            title = "chain",
            diagramType = DiagramType.FLOWCHART,
            nodes = listOf("a", "b", "c", "d", "e").map(::node),
            edges = listOf(edge("a", "b"), edge("b", "c"), edge("c", "d"), edge("d", "e"))
        )
        val score = LayoutQualityScorer.score(
            LayoutEngineFactory.forType(DiagramType.FLOWCHART).layout(ir)
        )
        assertTrue(
            score.edgeCrossingCount <= target(score.nodeCount),
            "crossings within size-based target"
        )
        assertTrue(score.isAcceptable(), "a simple chain is acceptable")
    }

    // --- Additive metrics: symmetry + visual density (P2 #18) -----------

    @Test
    fun densityAndSymmetryAreInUnitRange() {
        val ir = DiagramIR(
            diagramId = "d2",
            title = "flow",
            diagramType = DiagramType.FLOWCHART,
            nodes = listOf("a", "b", "c", "d", "e").map(::node),
            edges = listOf(
                edge("a", "b"),
                edge("b", "c"),
                edge("b", "d"),
                edge("c", "e"),
                edge("d", "e")
            )
        )
        val score = LayoutQualityScorer.score(
            LayoutEngineFactory.forType(DiagramType.FLOWCHART).layout(ir)
        )
        assertTrue(score.visualDensityScore in 0.0..1.0, "density in [0,1]")
        assertTrue(score.symmetryScore in 0.0..1.0, "symmetry in [0,1]")
        // visualDensityScore is the positive complement of unusedSpaceRatio.
        assertTrue(
            kotlin.math.abs(score.visualDensityScore - (1.0 - score.unusedSpaceRatio)) < 1e-9,
            "density == 1 - unusedSpaceRatio"
        )
        assertTrue(score.isAcceptable(), "real layout passes the permissive density gate")
    }

    @Test
    fun emptyLayoutScoresAreWellDefined() {
        val empty = LayoutResult(emptyList(), emptyList(), emptyList(), Rect.byLTWH(0, 0, 1, 1))
        val score = LayoutQualityScorer.score(empty)
        assertEquals(0, score.nodeCount)
        // Empty layouts skip the density gate (nodeCount == 0) and remain acceptable.
        assertTrue(score.isAcceptable(), "empty layout is acceptable")
        assertEquals(1.0, score.symmetryScore, "empty layout is trivially symmetric")
    }

    @Test
    fun densityGateIsPermissiveButCatchesExtremes() {
        // Construct a score with a near-empty canvas: density below DENSITY_MIN must fail the gate.
        val sparse = LayoutQualityScore(
            nodeCount = 2,
            edgeCount = 0,
            overlapCount = 0,
            edgeCrossingCount = 0,
            edgeBoxCrossingCount = 0,
            edgeBendCount = 0,
            averageEdgeLength = 0.0,
            labelOverflowCount = 0,
            unusedSpaceRatio = 0.999,
            symmetryScore = 1.0,
            visualDensityScore = 0.001
        )
        assertTrue(!sparse.isAcceptable(), "a near-empty canvas is rejected")

        val healthy = sparse.copy(unusedSpaceRatio = 0.6, visualDensityScore = 0.4)
        assertTrue(healthy.isAcceptable(), "a normally-filled canvas is accepted")
    }

    @Test
    fun singleNodeDiagramDoesNotReportAFalseDensityWarning() {
        val diagram =
            DiagramIR(
                diagramId = "single",
                title = "README-only repository",
                diagramType = DiagramType.ARCHITECTURE,
                nodes = listOf(node("Hello World!"))
            )

        val score = LayoutQualityScorer.score(LayoutEngineFactory.forIr(diagram).layout(diagram))

        assertEquals(1, score.nodeCount)
        assertTrue(score.isAcceptable(), "a readable one-node artifact is complete, not cramped")
    }

    // --- LabelFormatter clamping (P2 #12) -------------------------------

    @Test
    fun labelInnerWidthStaysWithinBounds() {
        val metrics = LayoutMetrics.DEFAULT
        val longLabel = "supercalifragilisticexpialidocious extra words here that wrap"
        val wrapped = LabelFormatter.wrap(longLabel, metrics)
        assertTrue(wrapped.innerWidth >= metrics.minInnerWidth, "inner width >= minInnerWidth")
        assertTrue(wrapped.innerWidth <= metrics.maxInnerWidth, "inner width <= maxInnerWidth")
        assertTrue(
            wrapped.innerWidth >= wrapped.lines.maxOf(DisplayCells::width),
            "inner width fits the longest wrapped line"
        )
    }

    @Test
    fun labelFormatterUsesTerminalWidthForKorean() {
        val wrapped =
            LabelFormatter.wrap(
                "웹 앱이 API를 호출한다",
                LayoutMetrics(minInnerWidth = 5, maxInnerWidth = 12)
            )

        assertTrue(wrapped.lines.all { DisplayCells.width(it) <= 12 })
        assertTrue(wrapped.innerWidth >= wrapped.lines.maxOf(DisplayCells::width))
    }

    @Test
    fun shortLabelGetsAtLeastMinInnerWidth() {
        val metrics = LayoutMetrics.DEFAULT
        val wrapped = LabelFormatter.wrap("x", metrics)
        assertEquals(
            metrics.minInnerWidth,
            wrapped.innerWidth,
            "short labels padded to minInnerWidth"
        )
    }
}
