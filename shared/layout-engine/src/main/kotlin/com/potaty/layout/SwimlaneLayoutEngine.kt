/*
 * Copyright (c) 2026, Potaty
 */

package com.potaty.layout

import com.potaty.graphics.geo.DirectedPoint
import com.potaty.graphics.geo.Point
import com.potaty.graphics.geo.Rect
import com.potaty.ir.DiagramIR

/**
 * Sequence-diagram layout: participants are boxes across the top, each with a vertical lifeline;
 * messages (edges, in IR order) are horizontal arrows at increasing rows. Produces the classic
 * `Client / Server` interaction look seen in `output_sample/`.
 */
class SwimlaneLayoutEngine(
    private val metrics: LayoutMetrics = LayoutMetrics.DEFAULT
) : LayoutEngine {

    override val version: String = "swimlane-1.0"

    private val laneGap = 12
    private val messageRowGap = 2

    override fun layout(ir: DiagramIR): LayoutResult {
        if (ir.nodes.isEmpty()) {
            return LayoutResult(emptyList(), emptyList(), emptyList(), Rect.byLTWH(0, 0, 1, 1))
        }

        // 1. Participant boxes across the top.
        val boxes = LinkedHashMap<String, NodeBox>()
        var x = 0
        var maxBoxBottom = 0
        for (node in ir.nodes) {
            val wrapped = LabelFormatter.wrap(node.label, metrics)
            val w = wrapped.innerWidth + 2
            val h = wrapped.lines.size + 2
            boxes[node.id] = NodeBox(
                nodeId = node.id,
                bounds = Rect.byLTWH(x, 0, w, h),
                label = node.label,
                lines = wrapped.lines,
                type = node.type,
                confidence = node.confidence
            )
            maxBoxBottom = maxOf(maxBoxBottom, h - 1)
            x += w + laneGap
        }

        // 2. Messages at increasing rows.
        // Ordering: messages are laid out in the exact order they appear in `ir.edges`. The IR's
        // edge list is the single source of truth for sequence ordering — there is no timestamp on
        // IrEdge to sort by — so a sequence diagram's message order is whatever order the producer
        // emitted edges in, and `filter` preserves it (stable). This makes layout deterministic for
        // a given IR (golden tests rely on it); if callers need a different message order they must
        // reorder `ir.edges` upstream before calling the engine.
        val messages = ir.edges.filter { it.from in boxes && it.to in boxes && it.from != it.to }
        val firstRow = maxBoxBottom + 2
        val lastRow = firstRow + (messages.size.coerceAtLeast(1) - 1) * messageRowGap
        val lifelineBottom = lastRow + 2

        val h = DirectedPoint.Direction.HORIZONTAL
        val v = DirectedPoint.Direction.VERTICAL
        val edges = ArrayList<EdgeRoute>()

        // 2a. Lifelines (dashed, no arrow) from each box bottom to lifelineBottom.
        for ((id, box) in boxes) {
            val lx = box.bounds.cx()
            edges += EdgeRoute(
                edgeId = "lifeline_$id",
                fromId = id,
                toId = id,
                start = DirectedPoint(v, lx, box.bounds.bottom),
                end = DirectedPoint(v, lx, lifelineBottom),
                waypoints = listOf(Point(lx, box.bounds.bottom), Point(lx, lifelineBottom)),
                arrow = false,
                dashed = true
            )
        }

        // 2b. Message arrows.
        messages.forEachIndexed { index, edge ->
            val a = boxes.getValue(edge.from).bounds.cx()
            val b = boxes.getValue(edge.to).bounds.cx()
            val row = firstRow + index * messageRowGap
            edges += EdgeRoute(
                edgeId = edge.id,
                fromId = edge.from,
                toId = edge.to,
                start = DirectedPoint(h, a, row),
                end = DirectedPoint(h, b, row),
                waypoints = listOf(Point(a, row), Point(b, row)),
                label = edge.label,
                labelPosition = Point((a + b) / 2, row),
                inferred = edge.confidence < 0.7,
                arrow = true,
                dashed = false
            )
        }

        val resolvedEdges = resolveEdgeLabelCollisions(edges, boxes.values)
        val canvas = canvasOf(boxes.values, lifelineBottom)
        return LayoutResult(boxes.values.toList(), resolvedEdges, emptyList(), canvas)
    }

    private fun canvasOf(boxes: Collection<NodeBox>, bottom: Int): Rect {
        if (boxes.isEmpty()) return Rect.byLTWH(0, 0, 1, 1)
        return Rect.byLTRB(
            boxes.minOf { it.bounds.left },
            boxes.minOf { it.bounds.top },
            boxes.maxOf { it.bounds.right },
            bottom
        )
    }
}
