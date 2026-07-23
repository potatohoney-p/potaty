/*
 * Copyright (c) 2026, Potaty
 */

package com.potaty.layout

import com.potaty.graphics.geo.Rect
import com.potaty.ir.DiagramIR
import com.potaty.ir.IrNode
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Container layout for architecture / C4-container / deployment diagrams. Each IR group becomes a
 * dashed container whose member nodes are packed into a balanced grid; containers are stacked
 * vertically and centered. Ungrouped nodes form a trailing grid with no container. This reproduces
 * the look of the `output_sample/` system diagrams (labelled dashed regions of boxes).
 */
class GridContainerLayoutEngine(
    private val metrics: LayoutMetrics = LayoutMetrics.DEFAULT
) : LayoutEngine {

    override val version: String = "grid-container-1.0"

    private data class Measure(val lines: List<String>, val width: Int, val height: Int)

    override fun layout(ir: DiagramIR): LayoutResult {
        if (ir.nodes.isEmpty()) {
            return LayoutResult(emptyList(), emptyList(), emptyList(), Rect.byLTWH(0, 0, 1, 1))
        }
        val nodeById = ir.nodes.associateBy { it.id }
        val measures = ir.nodes.associate { it.id to measure(it) }

        val grouped = LinkedHashSet<String>()
        ir.groups.forEach { grouped.addAll(it.nodeIds.filter { id -> id in nodeById }) }
        val ungrouped = ir.nodes.map { it.id }.filter { it !in grouped }

        val boxes = LinkedHashMap<String, NodeBox>()
        val groupBoxes = ArrayList<GroupBox>()

        var cursorTop = 0
        // `maxNodesPerGroup` is interpreted here as the grid column cap (max boxes per row) for each
        // container, NOT a hard ceiling on group membership: this engine renders every member of a
        // group, wrapping into ceil(members/maxPerRow) rows. Enforcing an actual membership limit
        // (plan 12.2 layout-focused chunking) is an IR-layer concern — splitting an oversized group
        // into sub-groups must happen upstream so it is visible in the IR and stable across
        // renderers; doing it silently here would change the diagram's structure as an afterthought.
        val maxPerRow = ir.layoutHints.maxNodesPerGroup

        for (group in ir.groups) {
            val members = group.nodeIds.filter { it in nodeById }
            if (members.isEmpty()) continue
            val innerLeft = metrics.groupPadding + 1
            val innerTop = cursorTop + metrics.groupPadding + 1 + metrics.groupLabelInset
            val placed = placeGrid(members, measures, nodeById, innerLeft, innerTop, maxPerRow)
            placed.forEach { boxes[it.nodeId] = it }

            val memberRects = placed.map { it.bounds }
            val left = memberRects.minOf { it.left } - metrics.groupPadding
            val top = memberRects.minOf { it.top } - metrics.groupPadding - metrics.groupLabelInset
            val right = memberRects.maxOf { it.right } + metrics.groupPadding
            val bottom = memberRects.maxOf { it.bottom } + metrics.groupPadding
            groupBoxes += GroupBox(group.id, group.label, Rect.byLTRB(left, top, right, bottom))
            cursorTop = bottom + metrics.layerGap + 1
        }

        if (ungrouped.isNotEmpty()) {
            val placed = placeGrid(ungrouped, measures, nodeById, 0, cursorTop, maxPerRow)
            placed.forEach { boxes[it.nodeId] = it }
        }

        // Center every row of containers/nodes horizontally around the widest extent.
        val routedEdges = ir.edges.filter { it.from != it.to }.mapNotNull { edge ->
            val a = boxes[edge.from] ?: return@mapNotNull null
            val b = boxes[edge.to] ?: return@mapNotNull null
            // Same-row (intra-band) edges may go horizontally; cross-band edges must go vertically
            // through the inter-group channel so they never cut through a sibling box's label.
            val sameRow = kotlin.math.abs(a.bounds.cy() - b.bounds.cy()) <= 1
            if (sameRow) {
                routeBetween(
                    edge.id,
                    edge.from,
                    edge.to,
                    a.bounds,
                    b.bounds,
                    edge.label,
                    edge.inferred()
                )
            } else {
                routeVertical(
                    edge.id,
                    edge.from,
                    edge.to,
                    a.bounds,
                    b.bounds,
                    edge.label,
                    edge.inferred()
                )
            }
        }
        val edges = resolveEdgeLabelCollisions(routedEdges, boxes.values)
        val canvas = canvasOf(boxes.values, groupBoxes)
        return LayoutResult(boxes.values.toList(), edges, groupBoxes, canvas)
    }

    private fun com.potaty.ir.IrEdge.inferred(): Boolean =
        edgeSourceType == com.potaty.ir.EdgeSourceType.LLM_INFERRED || confidence < 0.7

    private fun placeGrid(
        ids: List<String>,
        measures: Map<String, Measure>,
        nodeById: Map<String, IrNode>,
        originLeft: Int,
        originTop: Int,
        maxPerRow: Int?
    ): List<NodeBox> {
        val cols = (maxPerRow ?: ceil(sqrt(ids.size.toDouble())).toInt()).coerceAtLeast(1)
        val cellW = ids.maxOf { measures.getValue(it).width } + metrics.siblingGap
        val cellH = ids.maxOf { measures.getValue(it).height } + metrics.layerGap

        val result = ArrayList<NodeBox>()
        ids.forEachIndexed { index, id ->
            val row = index / cols
            val col = index % cols
            val m = measures.getValue(id)
            val node = nodeById.getValue(id)
            // center the box within its cell
            val cellLeft = originLeft + col * cellW
            val cellTop = originTop + row * cellH
            val left = cellLeft + (cellW - metrics.siblingGap - m.width) / 2
            val top = cellTop
            result += NodeBox(
                nodeId = id,
                bounds = Rect.byLTWH(left, top, m.width, m.height),
                label = node.label,
                lines = m.lines,
                type = node.type,
                instanceCount = node.instanceCount,
                confidence = node.confidence
            )
        }
        return result
    }

    private fun canvasOf(boxes: Collection<NodeBox>, groups: List<GroupBox>): Rect {
        val rects = boxes.map { it.bounds } + groups.map { it.bounds }
        if (rects.isEmpty()) return Rect.byLTWH(0, 0, 1, 1)
        return Rect.byLTRB(
            rects.minOf { it.left },
            rects.minOf { it.top },
            rects.maxOf { it.right },
            rects.maxOf { it.bottom }
        )
    }

    private fun measure(node: IrNode): Measure {
        val wrapped = LabelFormatter.wrap(node.label, metrics)
        return Measure(wrapped.lines, wrapped.innerWidth + 2, wrapped.lines.size + 2)
    }
}
