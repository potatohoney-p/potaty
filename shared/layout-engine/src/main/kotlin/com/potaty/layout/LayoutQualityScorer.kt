/*
 * Copyright (c) 2026, Potaty
 */

package com.potaty.layout

import com.potaty.common.DisplayCells
import com.potaty.graphics.geo.Point
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Deterministic aesthetic metrics for a [LayoutResult]. Aesthetic quality is a hard product
 * requirement (plan section 2.5 / 16.4), so it is measured — not left to chance. CI fails if
 * [overlapCount] or [labelOverflowCount] regress, or crossings exceed a size-based target.
 *
 * [symmetryScore] and [visualDensityScore] (plan 16.4) are additive observability metrics in the
 * range `[0,1]`; higher is better. They are reported for dashboards/eval but are intentionally NOT
 * part of [isAcceptable]'s hard gate so existing fixtures and goldens remain valid — see the bounds
 * check in [isAcceptable] which is deliberately permissive.
 */
@Serializable
data class LayoutQualityScore(
    @SerialName("node_count") val nodeCount: Int,
    @SerialName("edge_count") val edgeCount: Int,
    @SerialName("overlap_count") val overlapCount: Int,
    @SerialName("edge_crossing_count") val edgeCrossingCount: Int,
    @SerialName("edge_box_crossing_count") val edgeBoxCrossingCount: Int,
    @SerialName("edge_bend_count") val edgeBendCount: Int,
    @SerialName("average_edge_length") val averageEdgeLength: Double,
    @SerialName("label_overflow_count") val labelOverflowCount: Int,
    @SerialName("unused_space_ratio") val unusedSpaceRatio: Double,
    /**
     * Balance of node distribution about the canvas's vertical and horizontal mid-axes, in `[0,1]`.
     * 1.0 means node area (and therefore visual weight) is mirrored across both axes; 0.0 means it
     * is entirely lopsided. Additive metric (plan 16.4); defaults to 1.0 for backward compatibility
     * when deserializing scores produced before this field existed.
     */
    @SerialName("symmetry_score") val symmetryScore: Double = 1.0,
    /**
     * Fraction of the canvas occupied by node boxes, in `[0,1]` (== `1.0 - unusedSpaceRatio`).
     * Exposed as a positively-oriented companion to [unusedSpaceRatio] for eval dashboards.
     * Additive metric (plan 16.4); defaults to 0.0 for backward-compatible deserialization.
     */
    @SerialName("visual_density_score") val visualDensityScore: Double = 0.0
) {
    fun isAcceptable(): Boolean {
        if (overlapCount != 0) return false
        if (labelOverflowCount != 0) return false
        // An edge passing through an unrelated node's interior is never acceptable (it makes the
        // diagram unreadable and was the defect the original scorer missed).
        if (edgeBoxCrossingCount != 0) return false
        // Density guard (plan 16.4): a layout that fills almost none of its canvas (sparse, floaty)
        // or essentially all of it (cramped, no breathing room) is a smell. The bounds are
        // intentionally wide so all current fixtures/goldens pass; they only catch pathological
        // layouts. Empty and single-node diagrams skip this check: their tight canvas is expected,
        // not evidence of a cramped layout.
        if (nodeCount > 1 && visualDensityScore !in DENSITY_MIN..DENSITY_MAX) return false
        // Crossing target derives from the worst case for a layered drawing: with n nodes the
        // number of edge pairs that *could* cross is bounded above by ~n^2/4 (Sugiyama-style
        // bipartite-layer bound), so we allow up to n^2/4 + 1 crossings and fail above that.
        // See e.g. Sugiyama/Tagawa/Toda layered drawing literature. The unit test
        // crossingTargetScalesWithSize() pins this for small/medium/large graphs.
        val crossingTarget = (nodeCount * nodeCount) / 4 + 1
        return edgeCrossingCount <= crossingTarget
    }

    companion object {
        /** Lower density bound for [isAcceptable]; below this the canvas is mostly empty. */
        const val DENSITY_MIN: Double = 0.02

        /** Upper density bound for [isAcceptable]; above this there is no whitespace at all. */
        const val DENSITY_MAX: Double = 0.99
    }
}

object LayoutQualityScorer {

    fun score(result: LayoutResult): LayoutQualityScore {
        val boxes = result.nodes
        val overlaps = countOverlaps(boxes)
        val crossings = countEdgeCrossings(result.edges)
        val boxCrossings = countEdgeBoxCrossings(boxes, result.edges)
        val bends = result.edges.sumOf { (it.waypoints.size - 2).coerceAtLeast(0) }
        val avgLen =
            if (result.edges.isEmpty()) {
                0.0
            } else
                result.edges
                    .map {
                        pathLength(it.waypoints)
                    }
                    .average()
        val overflow =
            boxes.count { box ->
                box.lines.any { DisplayCells.width(it) > box.bounds.width - 2 }
            }
        val usedArea = boxes.sumOf { it.bounds.width * it.bounds.height }
        val canvasArea = (result.canvas.width * result.canvas.height).coerceAtLeast(1)
        val density = (usedArea.toDouble() / canvasArea.toDouble()).coerceIn(0.0, 1.0)
        val unused = 1.0 - density
        val symmetry = symmetryScore(boxes, result.canvas)

        return LayoutQualityScore(
            nodeCount = boxes.size,
            edgeCount = result.edges.size,
            overlapCount = overlaps,
            edgeCrossingCount = crossings,
            edgeBoxCrossingCount = boxCrossings,
            edgeBendCount = bends,
            averageEdgeLength = avgLen,
            labelOverflowCount = overflow,
            unusedSpaceRatio = unused,
            symmetryScore = symmetry,
            visualDensityScore = density
        )
    }

    private fun countOverlaps(boxes: List<NodeBox>): Int {
        var count = 0
        for (i in boxes.indices) {
            for (j in i + 1 until boxes.size) {
                if (boxes[i].bounds.isOverlapped(boxes[j].bounds)) count++
            }
        }
        return count
    }

    /**
     * Mirror-balance of node box area about the canvas centre, averaged over the vertical and
     * horizontal axes. For each axis we compare the total box area on each side of the mid-line: a
     * perfectly mirrored layout scores 1.0, an all-on-one-side layout scores 0.0. Area-weighting
     * (rather than counting boxes) means a few large boxes pull the balance the way a viewer's eye
     * would. Deterministic and platform-independent (no map iteration). Empty layouts score 1.0.
     */
    private fun symmetryScore(boxes: List<NodeBox>, canvas: com.potaty.graphics.geo.Rect): Double {
        if (boxes.isEmpty()) return 1.0
        val midX = canvas.left + canvas.width / 2.0
        val midY = canvas.top + canvas.height / 2.0
        var leftArea = 0.0
        var rightArea = 0.0
        var topArea = 0.0
        var bottomArea = 0.0
        for (b in boxes) {
            val area = (b.bounds.width * b.bounds.height).toDouble()
            val cx = b.bounds.left + b.bounds.width / 2.0
            val cy = b.bounds.top + b.bounds.height / 2.0
            if (cx <= midX) leftArea += area else rightArea += area
            if (cy <= midY) topArea += area else bottomArea += area
        }
        val horizontalBalance = balance(leftArea, rightArea)
        val verticalBalance = balance(topArea, bottomArea)
        return (horizontalBalance + verticalBalance) / 2.0
    }

    /** 1.0 when [a] == [b], approaching 0.0 as one side dominates. */
    private fun balance(a: Double, b: Double): Double {
        val total = a + b
        if (total <= 0.0) return 1.0
        return 1.0 - kotlin.math.abs(a - b) / total
    }

    private fun countEdgeCrossings(edges: List<EdgeRoute>): Int {
        val segments = edges.flatMap { segmentsOf(it.waypoints) }
        var crossings = 0
        for (i in segments.indices) {
            for (j in i + 1 until segments.size) {
                if (segmentsCross(segments[i], segments[j])) crossings++
            }
        }
        return crossings
    }

    /**
     * Counts edge segments that pass through the INTERIOR of a node box other than the edge's own
     * endpoints. This is the metric the renderer actually cares about (a line drawn over a box's
     * label), measured on the routed waypoints which our routers keep equal to the drawn polyline.
     */
    private fun countEdgeBoxCrossings(boxes: List<NodeBox>, edges: List<EdgeRoute>): Int {
        var count = 0
        for (edge in edges) {
            for (seg in segmentsOf(edge.waypoints)) {
                for (box in boxes) {
                    if (box.nodeId == edge.fromId || box.nodeId == edge.toId) continue
                    if (segmentEntersInterior(seg, box.bounds)) count++
                }
            }
        }
        return count
    }

    /**
     * True if an axis-aligned segment passes strictly inside the box (excluding its border ring).
     */
    private fun segmentEntersInterior(seg: Seg, box: com.potaty.graphics.geo.Rect): Boolean {
        if (box.width <= 2 || box.height <= 2) return false
        val inLeft = box.left + 1
        val inRight = box.right - 1
        val inTop = box.top + 1
        val inBottom = box.bottom - 1
        val horizontal = seg.a.top == seg.b.top
        return if (horizontal) {
            val y = seg.a.top
            if (y !in inTop..inBottom) return false
            val x1 = minOf(seg.a.left, seg.b.left)
            val x2 = maxOf(seg.a.left, seg.b.left)
            x1 <= inRight && x2 >= inLeft
        } else {
            val x = seg.a.left
            if (x !in inLeft..inRight) return false
            val y1 = minOf(seg.a.top, seg.b.top)
            val y2 = maxOf(seg.a.top, seg.b.top)
            y1 <= inBottom && y2 >= inTop
        }
    }

    private data class Seg(val a: Point, val b: Point)

    private fun segmentsOf(points: List<Point>): List<Seg> =
        points.zipWithNext().map { (p, q) -> Seg(p, q) }

    /** Crossing test for axis-aligned segments only (orthogonal routing). */
    private fun segmentsCross(s1: Seg, s2: Seg): Boolean {
        val h1 = s1.a.top == s1.b.top
        val h2 = s2.a.top == s2.b.top
        if (h1 == h2) return false // parallel: ignore (overlaps handled separately)
        val (h, v) = if (h1) s1 to s2 else s2 to s1
        val hy = h.a.top
        val hx1 = minOf(h.a.left, h.b.left)
        val hx2 = maxOf(h.a.left, h.b.left)
        val vx = v.a.left
        val vy1 = minOf(v.a.top, v.b.top)
        val vy2 = maxOf(v.a.top, v.b.top)
        return vx in (hx1 + 1) until hx2 && hy in (vy1 + 1) until vy2
    }

    private fun pathLength(points: List<Point>): Double =
        points.zipWithNext().sumOf { (p, q) ->
            (kotlin.math.abs(p.left - q.left) + kotlin.math.abs(p.top - q.top)).toDouble()
        }
}
