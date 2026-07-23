/*
 * Copyright (c) 2026, Potaty
 */

package com.potaty.layout

import com.potaty.graphics.geo.DirectedPoint
import com.potaty.graphics.geo.Point
import com.potaty.graphics.geo.Rect
import com.potaty.ir.CycleDetector
import com.potaty.ir.DiagramIR
import com.potaty.ir.IrNode
import com.potaty.ir.LayoutDirection
import kotlin.math.max

/**
 * A Sugiyama-style layered layout for flowcharts, dependency graphs, data flow, state, decision and
 * ER diagrams. Pipeline: cycle-break -> longest-path layering -> barycenter crossing reduction ->
 * coordinate assignment -> orthogonal edge anchoring. Aesthetic quality (few crossings, balanced
 * spacing, centered layers) is produced here; edges become elbow connectors via the engine's Line.
 */
class LayeredLayoutEngine(private val metrics: LayoutMetrics = LayoutMetrics.DEFAULT) :
    LayoutEngine {

    override val version: String = "layered-1.0"

    private data class Measure(
        val innerWidth: Int,
        val lines: List<String>,
        val width: Int,
        val height: Int
    )

    override fun layout(ir: DiagramIR): LayoutResult {
        if (ir.nodes.isEmpty()) {
            return LayoutResult(emptyList(), emptyList(), emptyList(), Rect.byLTWH(0, 0, 1, 1))
        }

        val horizontal =
            ir.layoutHints.direction == LayoutDirection.LR ||
                ir.layoutHints.direction == LayoutDirection.RL
        val reverse =
            ir.layoutHints.direction == LayoutDirection.BT ||
                ir.layoutHints.direction == LayoutDirection.RL

        val nodeIds = ir.nodes.map { it.id }
        val nodeById = ir.nodes.associateBy { it.id }
        // Self-loops (from == to) are excluded from layout/routing here regardless of diagram type:
        // an orthogonal elbow from a box back to itself would draw a degenerate line through the
        // box's own label. The IR layer is responsible for the *policy* on self-loops — for acyclic
        // diagram types ([DiagramType.isAcyclic]) IrValidator rejects them as a cycle (its cycle
        // message reads "cycle: A -> A", which a reader should understand as a self-loop); for
        // cyclic types it warns. Either way the layout engine just drops them so the drawing stays
        // clean. Edges to/from unknown node ids are likewise dropped (validation catches those).
        val routableEdges =
            ir.edges.filter { it.from in nodeById && it.to in nodeById && it.from != it.to }
        val edgePairs = routableEdges.map { it.from to it.to }

        val layerOf = assignLayers(nodeIds, edgePairs)
        var layers = buildLayers(ir.nodes, layerOf)
        layers = reduceCrossings(layers, edgePairs)
        if (reverse) layers = layers.reversed()

        val measures = ir.nodes.associate { it.id to measure(it) }
        val metricsLayerGap = ir.layoutHints.layerGap ?: metrics.layerGap
        val metricsSiblingGap = ir.layoutHints.siblingGap ?: metrics.siblingGap

        val boxes =
            if (horizontal) {
                placeHorizontal(layers, measures, nodeById, metricsLayerGap, metricsSiblingGap)
            } else {
                placeVertical(layers, measures, nodeById, metricsLayerGap, metricsSiblingGap)
            }

        val routedEdges = routableEdges.mapNotNull { edge ->
            val a = boxes[edge.from] ?: return@mapNotNull null
            val b = boxes[edge.to] ?: return@mapNotNull null
            route(
                edge.id,
                edge.from,
                edge.to,
                a.bounds,
                b.bounds,
                horizontal,
                edge.label,
                edge.inferred()
            )
        }
        val edges = resolveEdgeLabelCollisions(routedEdges, boxes.values)

        val groups = computeGroupBoxes(ir, boxes)
        val canvas = canvasOf(boxes.values, groups)
        return LayoutResult(boxes.values.toList(), edges, groups, canvas)
    }

    private fun com.potaty.ir.IrEdge.inferred(): Boolean =
        edgeSourceType == com.potaty.ir.EdgeSourceType.LLM_INFERRED || confidence < 0.7

    // --- Layering --------------------------------------------------------

    private fun assignLayers(
        nodeIds: List<String>,
        edges: List<Pair<String, String>>
    ): Map<String, Int> {
        val feedback = CycleDetector.feedbackEdgeSet(nodeIds, edges)
        val dagEdges = edges.filterIndexed { index, _ -> index !in feedback }

        val incoming = HashMap<String, MutableList<String>>()
        val outgoing = HashMap<String, MutableList<String>>()
        nodeIds.forEach {
            incoming[it] = mutableListOf()
            outgoing[it] = mutableListOf()
        }
        for ((from, to) in dagEdges) {
            outgoing.getOrPut(from) { mutableListOf() }.add(to)
            incoming.getOrPut(to) { mutableListOf() }.add(from)
        }

        // Kahn topological order, then longest-path layering.
        val indeg =
            HashMap<String, Int>().apply {
                nodeIds.forEach {
                    put(
                        it,
                        incoming[it]?.size ?: 0
                    )
                }
            }
        val queue = ArrayDeque(nodeIds.filter { indeg[it] == 0 })
        val order = ArrayList<String>()
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            order.add(n)
            for (m in outgoing[n] ?: emptyList()) {
                indeg[m] = (indeg[m] ?: 1) - 1
                if (indeg[m] == 0) queue.addLast(m)
            }
        }
        // Any nodes left (shouldn't happen after cycle break) get appended.
        nodeIds.filter { it !in order }.forEach { order.add(it) }

        val layer = HashMap<String, Int>().apply { nodeIds.forEach { put(it, 0) } }
        for (n in order) {
            for (m in outgoing[n] ?: emptyList()) {
                layer[m] = max(layer[m] ?: 0, (layer[n] ?: 0) + 1)
            }
        }
        return layer
    }

    private fun buildLayers(
        nodes: List<IrNode>,
        layerOf: Map<String, Int>
    ): List<MutableList<String>> {
        val maxLayer = nodes.maxOf { layerOf[it.id] ?: 0 }
        val layers = MutableList(maxLayer + 1) { mutableListOf<String>() }
        for (node in nodes) {
            layers[layerOf[node.id] ?: 0].add(node.id)
        }
        return layers
    }

    // --- Crossing reduction ---------------------------------------------

    private fun reduceCrossings(
        layers: List<MutableList<String>>,
        edges: List<Pair<String, String>>
    ): List<MutableList<String>> {
        if (layers.size < 2) return layers
        val succ = HashMap<String, MutableList<String>>()
        val pred = HashMap<String, MutableList<String>>()
        for ((from, to) in edges) {
            succ.getOrPut(from) { mutableListOf() }.add(to)
            pred.getOrPut(to) { mutableListOf() }.add(from)
        }
        val working = layers.map { it.toMutableList() }
        repeat(4) { sweep ->
            val downward = sweep % 2 == 0
            if (downward) {
                for (i in 1 until working.size) {
                    sortByBarycenter(working[i], working[i - 1], pred)
                }
            } else {
                for (i in working.size - 2 downTo 0) {
                    sortByBarycenter(working[i], working[i + 1], succ)
                }
            }
        }
        return working
    }

    private fun sortByBarycenter(
        layer: MutableList<String>,
        reference: List<String>,
        neighbors: Map<String, List<String>>
    ) {
        val refIndex = reference.withIndex().associate { (idx, id) -> id to idx }
        val originalIndex = layer.withIndex().associate { (idx, id) -> id to idx }
        val bary = layer.associateWith { id ->
            val ns = neighbors[id]?.mapNotNull { refIndex[it] } ?: emptyList()
            if (ns.isEmpty()) (originalIndex[id] ?: 0).toDouble() else ns.average()
        }
        layer.sortWith(compareBy({ bary[it] }, { originalIndex[it] }))
    }

    // --- Coordinate assignment ------------------------------------------

    private fun placeVertical(
        layers: List<List<String>>,
        measures: Map<String, Measure>,
        nodeById: Map<String, IrNode>,
        layerGap: Int,
        siblingGap: Int
    ): LinkedHashMap<String, NodeBox> {
        val rowHeights = layers.map { layer ->
            layer.maxOfOrNull {
                footprint(measures.getValue(it), nodeById.getValue(it)).second
            } ?: 0
        }
        val layerWidths = layers.map { layer ->
            if (layer.isEmpty()) {
                0
            } else
                layer.sumOf {
                    footprint(measures.getValue(it), nodeById.getValue(it)).first
                } + siblingGap * (layer.size - 1)
        }
        val maxWidth = (layerWidths.maxOrNull() ?: 0).coerceAtLeast(1)

        val boxes = LinkedHashMap<String, NodeBox>()
        var y = 0
        for ((li, layer) in layers.withIndex()) {
            var x = (maxWidth - layerWidths[li]) / 2
            for (id in layer) {
                val m = measures.getValue(id)
                val node = nodeById.getValue(id)
                val (fw, fh) = footprint(m, node)
                val extra = stackExtra(node)
                // Front box sits at the bottom-left of its footprint; stack shadows go up-right.
                val frontLeft = x
                val frontTop = y + (rowHeights[li] - fh) / 2 + extra
                boxes[id] =
                    NodeBox(
                        nodeId = id,
                        bounds = Rect.byLTWH(frontLeft, frontTop, m.width, m.height),
                        label = node.label,
                        lines = m.lines,
                        type = node.type,
                        instanceCount = node.instanceCount,
                        confidence = node.confidence
                    )
                x += fw + siblingGap
            }
            y += rowHeights[li] + layerGap
        }
        return boxes
    }

    private fun placeHorizontal(
        layers: List<List<String>>,
        measures: Map<String, Measure>,
        nodeById: Map<String, IrNode>,
        layerGap: Int,
        siblingGap: Int
    ): LinkedHashMap<String, NodeBox> {
        val colWidths = layers.map { layer ->
            layer.maxOfOrNull {
                footprint(measures.getValue(it), nodeById.getValue(it)).first
            } ?: 0
        }
        val layerHeights = layers.map { layer ->
            if (layer.isEmpty()) {
                0
            } else
                layer.sumOf {
                    footprint(measures.getValue(it), nodeById.getValue(it)).second
                } + siblingGap * (layer.size - 1)
        }
        val maxHeight = (layerHeights.maxOrNull() ?: 0).coerceAtLeast(1)

        val boxes = LinkedHashMap<String, NodeBox>()
        var x = 0
        for ((li, layer) in layers.withIndex()) {
            var y = (maxHeight - layerHeights[li]) / 2
            for (id in layer) {
                val m = measures.getValue(id)
                val node = nodeById.getValue(id)
                val fh = footprint(m, node).second
                val extra = stackExtra(node)
                boxes[id] =
                    NodeBox(
                        nodeId = id,
                        bounds = Rect.byLTWH(x, y + extra, m.width, m.height),
                        label = node.label,
                        lines = m.lines,
                        type = node.type,
                        instanceCount = node.instanceCount,
                        confidence = node.confidence
                    )
                y += fh + siblingGap
            }
            x += colWidths[li] + layerGap
        }
        return boxes
    }

    private fun footprint(m: Measure, node: IrNode): Pair<Int, Int> {
        val extra = stackExtra(node)
        return (m.width + extra) to (m.height + extra)
    }

    private fun stackExtra(node: IrNode): Int =
        if (node.instanceCount > 1) metrics.stackOffset * minOf(node.instanceCount - 1, 2) else 0

    // --- Edge routing ----------------------------------------------------

    private fun route(
        edgeId: String,
        fromId: String,
        toId: String,
        a: Rect,
        b: Rect,
        horizontal: Boolean,
        label: String?,
        inferred: Boolean
    ): EdgeRoute {
        val h = DirectedPoint.Direction.HORIZONTAL
        val v = DirectedPoint.Direction.VERTICAL
        val (start, end) =
            if (horizontal) {
                if (a.left <= b.left) {
                    DirectedPoint(h, a.right, a.cy()) to DirectedPoint(h, b.left, b.cy())
                } else {
                    DirectedPoint(h, a.left, a.cy()) to DirectedPoint(h, b.right, b.cy())
                }
            } else {
                if (a.top <= b.top) {
                    DirectedPoint(v, a.cx(), a.bottom) to DirectedPoint(v, b.cx(), b.top)
                } else {
                    DirectedPoint(v, a.cx(), a.top) to DirectedPoint(v, b.cx(), b.bottom)
                }
            }
        val waypoints = orthogonalWaypoints(start.point, end.point, horizontal)
        val labelPos = labelPositionFor(waypoints)
        return EdgeRoute(edgeId, fromId, toId, start, end, waypoints, label, labelPos, inferred)
    }

    private fun orthogonalWaypoints(start: Point, end: Point, horizontal: Boolean): List<Point> {
        if (start.left == end.left || start.top == end.top) return listOf(start, end)
        return if (horizontal) {
            val midX = (start.left + end.left) / 2
            listOf(start, Point(midX, start.top), Point(midX, end.top), end)
        } else {
            val midY = (start.top + end.top) / 2
            listOf(start, Point(start.left, midY), Point(end.left, midY), end)
        }
    }

    // --- Groups & canvas -------------------------------------------------

    private fun computeGroupBoxes(ir: DiagramIR, boxes: Map<String, NodeBox>): List<GroupBox> =
        ir.groups.mapNotNull { group ->
            val memberBounds = group.nodeIds.mapNotNull { boxes[it]?.bounds }
            if (memberBounds.isEmpty()) return@mapNotNull null
            val left = memberBounds.minOf { it.left } - metrics.groupPadding
            val top = memberBounds.minOf { it.top } - metrics.groupPadding - metrics.groupLabelInset
            val right = memberBounds.maxOf { it.right } + metrics.groupPadding
            val bottom = memberBounds.maxOf { it.bottom } + metrics.groupPadding
            GroupBox(group.id, group.label, Rect.byLTRB(left, top, right, bottom))
        }

    private fun canvasOf(boxes: Collection<NodeBox>, groups: List<GroupBox>): Rect {
        val rects = boxes.map { it.bounds } + groups.map { it.bounds }
        if (rects.isEmpty()) return Rect.byLTWH(0, 0, 1, 1)
        val left = rects.minOf { it.left }
        val top = rects.minOf { it.top }
        val right = rects.maxOf { it.right }
        val bottom = rects.maxOf { it.bottom }
        return Rect.byLTRB(left, top, right, bottom)
    }

    private fun measure(node: IrNode): Measure {
        val wrapped = LabelFormatter.wrap(node.label, metrics)
        val inner = wrapped.innerWidth
        return Measure(
            innerWidth = inner,
            lines = wrapped.lines,
            width = inner + 2,
            height = wrapped.lines.size + 2
        )
    }
}

internal fun Rect.cx(): Int = left + width / 2

internal fun Rect.cy(): Int = top + height / 2
