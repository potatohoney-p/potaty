/*
 * Copyright (c) 2026, Potaty
 */

package com.potaty.layout

import com.potaty.graphics.geo.DirectedPoint
import com.potaty.graphics.geo.Point
import com.potaty.graphics.geo.Rect
import com.potaty.ir.NodeType

/**
 * A placed node: integer-cell bounds (1 cell == 1 monospace character) plus the wrapped label
 * lines so the renderer and the layout agree on size.
 */
data class NodeBox(
    val nodeId: String,
    val bounds: Rect,
    val label: String,
    val lines: List<String>,
    val type: NodeType,
    val instanceCount: Int = 1,
    val confidence: Double = 1.0,
    val inferred: Boolean = false
)

/**
 * A routed edge. [start]/[end] are directed anchor points on the source/target box borders; the
 * existing engine's [com.potaty.shape.shape.Line] turns these into an orthogonal elbow. [waypoints] is an
 * explicit orthogonal polyline used by the quality scorer and non-ASCII compilers.
 */
data class EdgeRoute(
    val edgeId: String,
    val fromId: String,
    val toId: String,
    val start: DirectedPoint,
    val end: DirectedPoint,
    val waypoints: List<Point>,
    val label: String? = null,
    val labelPosition: Point? = null,
    val inferred: Boolean = false,
    /** Whether to draw an arrow head at [end]. Lifelines and undirected edges set this false. */
    val arrow: Boolean = true,
    /** Whether to draw the stroke dashed (e.g. sequence lifelines, inferred edges). */
    val dashed: Boolean = false
)

/** A dashed container drawn behind its member nodes, with a top-left label. */
data class GroupBox(
    val groupId: String,
    val label: String,
    val bounds: Rect
)

data class LayoutResult(
    val nodes: List<NodeBox>,
    val edges: List<EdgeRoute>,
    val groups: List<GroupBox>,
    val canvas: Rect
)

/**
 * All aesthetic spacing constants in one place so the look can be tuned to match `output_sample/`.
 */
data class LayoutMetrics(
    /** Inner horizontal padding between the label and the box border. */
    val nodePaddingX: Int = 2,
    /** Minimum inner width of a node box (excluding borders). */
    val minInnerWidth: Int = 5,
    /** Maximum inner width before the label wraps to another line. */
    val maxInnerWidth: Int = 22,
    /** Gap between sibling nodes within the same layer. */
    val siblingGap: Int = 4,
    /** Gap between consecutive layers (leaves room for elbow connectors + labels). */
    val layerGap: Int = 5,
    /** Padding between a group's member bounds and the dashed container border. */
    val groupPadding: Int = 2,
    /** Extra top space inside a group to host its label row. */
    val groupLabelInset: Int = 1,
    /** Horizontal offset per stacked instance (for replica "stack" nodes). */
    val stackOffset: Int = 1
) {
    companion object {
        val DEFAULT = LayoutMetrics()
    }
}
