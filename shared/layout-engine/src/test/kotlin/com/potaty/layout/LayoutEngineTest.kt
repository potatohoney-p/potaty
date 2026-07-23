/*
 * Copyright (c) 2026, Potaty
 *
 * Layout-engine tests (previously zero coverage). Asserts the two production invariants the
 * quality scorer enforces — no overlapping node boxes, no label overflow — and that layout is
 * deterministic (same IR -> identical boxes), which the renderer/golden tests rely on.
 */

package com.potaty.layout

import com.potaty.graphics.geo.Point
import com.potaty.graphics.geo.Rect
import com.potaty.ir.DiagramIR
import com.potaty.ir.DiagramType
import com.potaty.ir.EdgeSourceType
import com.potaty.ir.EdgeType
import com.potaty.ir.IrEdge
import com.potaty.ir.IrNode
import com.potaty.ir.NodeType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LayoutEngineTest {

    private fun node(id: String) = IrNode(id = id, label = id, type = NodeType.SERVICE)
    private fun edge(from: String, to: String) =
        IrEdge(
            id = "$from-$to",
            from = from,
            to = to,
            type = EdgeType.CALLS,
            edgeSourceType = EdgeSourceType.DOCUMENT_STATEMENT
        )

    private val ir = DiagramIR(
        diagramId = "d1",
        title = "Test flow",
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

    @Test
    fun layeredLayoutPlacesAllNodesWithoutOverlap() {
        val result = LayoutEngineFactory.forType(DiagramType.FLOWCHART).layout(ir)
        assertEquals(ir.nodes.size, result.nodes.size, "every node is placed")
        val score = LayoutQualityScorer.score(result)
        assertEquals(0, score.overlapCount, "no overlapping node boxes")
        assertEquals(0, score.labelOverflowCount, "no label overflow")
    }

    @Test
    fun layoutIsDeterministic() {
        val engine = LayoutEngineFactory.forType(DiagramType.FLOWCHART)
        val a = engine.layout(ir)
        val b = engine.layout(ir)
        assertEquals(a.canvas, b.canvas, "canvas is stable")
        assertEquals(
            a.nodes.map { it.nodeId to it.bounds },
            b.nodes.map { it.nodeId to it.bounds },
            "node boxes are stable across runs"
        )
    }

    @Test
    fun parallelKoreanEdgeLabelsAreStaggeredWithoutConcatenation() {
        val nodes =
            listOf("웹 앱", "감사 서비스", "운영팀", "API 게이트웨이", "Kafka", "대시보드")
                .mapIndexed { index, label ->
                    IrNode(id = "n$index", label = label, type = NodeType.SERVICE)
                }
        val labels = listOf("로그인 요청 보냄", "보안 이벤트 발행", "실패율과 처리 지연 확인")
        val edges =
            labels.mapIndexed { index, label ->
                IrEdge(
                    id = "e$index",
                    from = "n$index",
                    to = "n${index + 3}",
                    type = EdgeType.RELATES_TO,
                    label = label,
                    edgeSourceType = EdgeSourceType.TRANSCRIPT_STATEMENT
                )
            }
        val diagram =
            DiagramIR(
                diagramId = "parallel-labels",
                title = "병렬 흐름",
                diagramType = DiagramType.ARCHITECTURE,
                nodes = nodes,
                edges = edges
            )

        val layout = LayoutEngineFactory.forIr(diagram).layout(diagram)
        val bounds = layout.edges.mapNotNull(::edgeLabelBounds)

        assertEquals(3, bounds.size)
        for (i in bounds.indices) {
            for (j in i + 1 until bounds.size) {
                assertFalse(labelsCollide(bounds[i], bounds[j]), "edge labels must keep a gutter")
            }
        }
    }

    @Test
    fun straightRouteLabelUsesTheChannelMidpointNotTheTargetBorder() {
        val route =
            routeBetween(
                edgeId = "straight",
                fromId = "a",
                toId = "b",
                a = Rect.byLTWH(0, 0, 7, 3),
                b = Rect.byLTWH(0, 10, 7, 3),
                label = "request",
                inferred = false
            )

        assertEquals(Point(3, 6), route.labelPosition)
    }
}
