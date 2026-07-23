/*
 * Copyright (c) 2026, Potaty
 */

package com.potaty.layout

import com.potaty.common.DisplayCells
import com.potaty.graphics.geo.DirectedPoint
import com.potaty.graphics.geo.Point
import com.potaty.graphics.geo.Rect
import kotlin.math.abs

/** Renderer and layout share this cap so collision checks match the final ASCII cells exactly. */
const val MAX_EDGE_LABEL_CELLS: Int = 22

private const val EDGE_LABEL_HORIZONTAL_GAP = 1
private val EDGE_LABEL_ROW_OFFSETS =
    intArrayOf(0, 1, -1, 2, -2, 3, -3, 4, -4, 5, -5, 6, -6)

/**
 * Generic orthogonal edge routing for non-layered engines (grid/container, swimlane). Picks border
 * anchors on the dominant axis between two boxes, leaving the elbow to the engine's Line.
 */
internal fun routeBetween(
    edgeId: String,
    fromId: String,
    toId: String,
    a: Rect,
    b: Rect,
    label: String?,
    inferred: Boolean
): EdgeRoute {
    val h = DirectedPoint.Direction.HORIZONTAL
    val v = DirectedPoint.Direction.VERTICAL
    val dx = b.cx() - a.cx()
    val dy = b.cy() - a.cy()
    val verticalDominant = abs(dy) >= abs(dx)

    val (start, end) =
        if (verticalDominant) {
            if (dy >= 0) {
                DirectedPoint(v, a.cx(), a.bottom) to DirectedPoint(v, b.cx(), b.top)
            } else {
                DirectedPoint(v, a.cx(), a.top) to DirectedPoint(v, b.cx(), b.bottom)
            }
        } else {
            if (dx >= 0) {
                DirectedPoint(h, a.right, a.cy()) to DirectedPoint(h, b.left, b.cy())
            } else {
                DirectedPoint(h, a.left, a.cy()) to DirectedPoint(h, b.right, b.cy())
            }
        }
    val waypoints = orthogonalPath(start.point, end.point, !verticalDominant)
    return EdgeRoute(
        edgeId,
        fromId,
        toId,
        start,
        end,
        waypoints,
        label,
        labelPositionFor(waypoints),
        inferred
    )
}

/**
 * Forces a vertical-first route (exit the bottom/top border, travel in the inter-band channel).
 * Used for cross-group edges in container layouts so a line never cuts horizontally through a
 * sibling box's label row.
 */
internal fun routeVertical(
    edgeId: String,
    fromId: String,
    toId: String,
    a: Rect,
    b: Rect,
    label: String?,
    inferred: Boolean
): EdgeRoute {
    val v = DirectedPoint.Direction.VERTICAL
    val (start, end) =
        if (b.cy() >= a.cy()) {
            DirectedPoint(v, a.cx(), a.bottom) to DirectedPoint(v, b.cx(), b.top)
        } else {
            DirectedPoint(v, a.cx(), a.top) to DirectedPoint(v, b.cx(), b.bottom)
        }
    val waypoints = orthogonalPath(start.point, end.point, horizontal = false)
    return EdgeRoute(
        edgeId,
        fromId,
        toId,
        start,
        end,
        waypoints,
        label,
        labelPositionFor(waypoints),
        inferred
    )
}

internal fun orthogonalPath(start: Point, end: Point, horizontal: Boolean): List<Point> {
    if (start.left == end.left || start.top == end.top) return listOf(start, end)
    return if (horizontal) {
        val midX = (start.left + end.left) / 2
        listOf(start, Point(midX, start.top), Point(midX, end.top), end)
    } else {
        val midY = (start.top + end.top) / 2
        listOf(start, Point(start.left, midY), Point(end.left, midY), end)
    }
}

/** Midpoint of the longest routed segment; unlike a waypoint, this stays away from box borders. */
internal fun labelPositionFor(waypoints: List<Point>): Point? {
    val segment =
        waypoints.zipWithNext().maxByOrNull { (start, end) ->
            abs(end.left - start.left) + abs(end.top - start.top)
        } ?: return waypoints.firstOrNull()
    return Point(
        (segment.first.left + segment.second.left) / 2,
        (segment.first.top + segment.second.top) / 2
    )
}

/**
 * Staggers labels that would occupy the same terminal cells. Parallel branches frequently share an
 * inter-layer channel; drawing all labels on its first row concatenates otherwise correct facts.
 * Candidate rows are deterministic, stay clear of node boxes, and retain a one-cell horizontal
 * gutter between labels on the same row.
 */
internal fun resolveEdgeLabelCollisions(
    edges: List<EdgeRoute>,
    nodes: Collection<NodeBox>
): List<EdgeRoute> {
    val occupied = mutableListOf<Rect>()
    val nodeBounds = nodes.map { it.bounds }
    return edges.map { edge ->
        val base = edgeLabelBounds(edge) ?: return@map edge
        val chosen =
            EDGE_LABEL_ROW_OFFSETS
                .asSequence()
                .map { offset -> Rect.byLTWH(base.left, base.top + offset, base.width, 1) }
                .filter { it.top >= 0 }
                .firstOrNull { candidate ->
                    nodeBounds.none(candidate::isOverlapped) &&
                        occupied.none { existing -> labelsCollide(candidate, existing) }
                } ?: base
        occupied += chosen
        val original = edge.labelPosition ?: return@map edge
        edge.copy(labelPosition = Point(original.left, chosen.top + 1))
    }
}

/** Exact one-row rectangle used by [com.potaty.render.IrShapeMapper] for an edge label. */
internal fun edgeLabelBounds(edge: EdgeRoute): Rect? {
    val label =
        DisplayCells.truncate(
            edge.label?.trim().orEmpty(),
            MAX_EDGE_LABEL_CELLS,
            "…"
        )
    if (label.isEmpty()) return null
    val position = edge.labelPosition ?: return null
    val width = DisplayCells.width(label)
    return Rect.byLTWH(
        position.left - width / 2,
        (position.top - 1).coerceAtLeast(0),
        width,
        1
    )
}

internal fun labelsCollide(a: Rect, b: Rect): Boolean =
    a.top == b.top &&
        a.left <= b.right + EDGE_LABEL_HORIZONTAL_GAP &&
        b.left <= a.right + EDGE_LABEL_HORIZONTAL_GAP
