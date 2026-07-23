/*
 * Copyright (c) 2026, Potaty
 */

package com.potaty.render

import com.potaty.common.DisplayCells
import com.potaty.graphics.geo.Rect
import com.potaty.layout.EdgeRoute
import com.potaty.layout.GroupBox
import com.potaty.layout.LayoutResult
import com.potaty.layout.MAX_EDGE_LABEL_CELLS
import com.potaty.layout.NodeBox
import com.potaty.shape.ShapeExtraManager
import com.potaty.shape.extra.LineExtra
import com.potaty.shape.extra.RectangleExtra
import com.potaty.shape.extra.TextExtra
import com.potaty.shape.extra.style.StraightStrokeDashPattern
import com.potaty.shape.extra.style.TextAlign
import com.potaty.shape.shape.AbstractShape
import com.potaty.shape.shape.Line
import com.potaty.shape.shape.Rectangle
import com.potaty.shape.shape.Text
import kotlin.math.min

/**
 * Converts a [LayoutResult] into the existing Potaty shape model, in correct z-order:
 * group containers (back) -> instance "stack" shadows -> node boxes -> edges -> edge labels ->
 * group labels (front).
 *
 * Group labels are emitted last (see step 6 in [toShapes]) so a connector that crosses a container
 * border to reach an inner box can never overwrite the container's title.
 *
 * The result is drawn by the ASCII renderer (and can be loaded straight into the Workbench).
 */
object IrShapeMapper {

    private const val MAX_STACK_SHADOWS = 2
    private const val STACK_OFFSET = 1

    /** Left inset (in cells) of a group title from the container's left border. */
    private const val GROUP_LABEL_LEFT_INSET = 2

    /** Cells kept clear between the title and the container's right border. */
    private const val GROUP_LABEL_RIGHT_MARGIN = 1

    /** Single-character ellipsis appended when a group title is clipped to fit its container. */
    private const val ELLIPSIS = "…" // …

    fun toShapes(layout: LayoutResult, profile: StyleProfile): List<AbstractShape> {
        val shapes = ArrayList<AbstractShape>()

        // 1. Group containers (drawn first / behind). Labels are drawn LAST (see step 6) so a
        //    connector entering a member box can never overwrite the container's title.
        for (group in layout.groups) {
            shapes += groupRect(group, profile)
        }

        // 2. Instance "stack" shadows (behind the front node box).
        for (box in layout.nodes) {
            shapes += stackShadows(box, profile)
        }

        // 3. Node boxes (label + border in one Text shape, like the editor).
        for (box in layout.nodes) {
            shapes += nodeBox(box, profile)
        }

        // 4. Edges.
        for (edge in layout.edges) {
            edgeLine(edge, profile)?.let { shapes += it }
        }

        // 5. Edge labels on top.
        for (edge in layout.edges) {
            edgeLabel(edge)?.let { shapes += it }
        }

        // 6. Group labels last, so the container title always stays readable over any connector
        //    that crosses the container border to reach an inner box.
        for (group in layout.groups) {
            groupLabel(group)?.let { shapes += it }
        }

        return shapes
    }

    // --- Nodes -----------------------------------------------------------

    private fun nodeBox(box: NodeBox, profile: StyleProfile): Text {
        val text = Text(box.bounds, isTextEditable = false)
        text.setText(box.label)
        text.setExtra(
            TextExtra(
                boundExtra = borderedRectExtra(
                    profile.nodeBorderStyleId,
                    profile.roundedNodeCorners
                ),
                textAlign = TextAlign(
                    TextAlign.HorizontalAlign.MIDDLE,
                    TextAlign.VerticalAlign.MIDDLE
                )
            )
        )
        return text
    }

    private fun stackShadows(box: NodeBox, profile: StyleProfile): List<AbstractShape> {
        if (box.instanceCount <= 1) return emptyList()
        val count = min(box.instanceCount - 1, MAX_STACK_SHADOWS)
        val extra = borderedRectExtra(profile.nodeBorderStyleId, profile.roundedNodeCorners)
        // Shadows peek up-and-to-the-right behind the front box.
        return (count downTo 1).map { k ->
            val r = Rect.byLTWH(
                box.bounds.left + k * STACK_OFFSET,
                box.bounds.top - k * STACK_OFFSET,
                box.bounds.width,
                box.bounds.height
            )
            Rectangle(r).also { it.setExtra(extra) }
        }
    }

    // --- Groups ----------------------------------------------------------

    private fun groupRect(group: GroupBox, profile: StyleProfile): Rectangle {
        val dash = if (profile.dashedGroups) {
            StraightStrokeDashPattern(profile.groupDashOn, profile.groupDashOff, 0)
        } else {
            StraightStrokeDashPattern.SOLID
        }
        val extra = RectangleExtra(
            isFillEnabled = false,
            userSelectedFillStyle = ShapeExtraManager.getRectangleFillStyle("F1"),
            isBorderEnabled = true,
            userSelectedBorderStyle = ShapeExtraManager.getRectangleBorderStyle(
                profile.groupBorderStyleId
            ),
            dashPattern = dash,
            isRoundedCorner = false
        )
        return Rectangle(group.bounds).also { it.setExtra(extra) }
    }

    private fun groupLabel(group: GroupBox): Text? {
        val label = group.label.trim()
        if (label.isEmpty()) return null
        // Available width for the title: container interior minus the left inset and a right margin
        // that keeps the title from touching the container's right border.
        val available = group.bounds.width - GROUP_LABEL_LEFT_INSET - GROUP_LABEL_RIGHT_MARGIN
        // When the title already fits (the common case) this is a no-op and the shape is identical
        // to before; only an overflowing title is clipped, so it can never spill past the border.
        val display = clipLabel(label, available)
        if (display.isEmpty()) return null
        val rect = Rect.byLTWH(
            group.bounds.left + GROUP_LABEL_LEFT_INSET,
            group.bounds.top + 1,
            DisplayCells.width(display),
            1
        )
        val text = Text(rect, isTextEditable = false)
        text.setText(display)
        text.setExtra(noBorderTextExtra(TextAlign.HorizontalAlign.LEFT))
        return text
    }

    /**
     * Clips [label] so it never exceeds [available] cells. Returns [label] unchanged when it fits
     * (so existing layouts render identically), an ellipsis-terminated prefix when it overflows but
     * there is room for at least one visible character, or an empty string when no room remains.
     */
    private fun clipLabel(label: String, available: Int): String {
        if (available <= 0) return ""
        return DisplayCells.truncate(label, available, ELLIPSIS)
    }

    // --- Edges -----------------------------------------------------------

    private fun edgeLine(route: EdgeRoute, profile: StyleProfile): Line? {
        if (route.start.point == route.end.point) return null
        val dashed = route.dashed || route.inferred
        val dash = if (dashed) {
            StraightStrokeDashPattern(profile.inferredDashOn, profile.inferredDashOff, 0)
        } else {
            StraightStrokeDashPattern.SOLID
        }
        val extra = LineExtra(
            isStrokeEnabled = true,
            userSelectedStrokeStyle = ShapeExtraManager.getLineStrokeStyle(
                profile.lineStrokeStyleId
            ),
            isStartAnchorEnabled = false,
            userSelectedStartAnchor = ShapeExtraManager.getStartHeadAnchorChar(profile.endAnchorId),
            isEndAnchorEnabled = route.arrow,
            userSelectedEndAnchor = ShapeExtraManager.getEndHeadAnchorChar(profile.endAnchorId),
            dashPattern = dash,
            isRoundedCorner = profile.roundedElbows
        )
        return Line(route.start, route.end).also { it.setExtra(extra) }
    }

    private fun edgeLabel(route: EdgeRoute): Text? {
        val label =
            DisplayCells.truncate(
                route.label?.trim().orEmpty(),
                MAX_EDGE_LABEL_CELLS,
                ELLIPSIS
            )
        if (label.isEmpty()) return null
        val pos = route.labelPosition ?: return null
        val labelWidth = DisplayCells.width(label)
        val left = pos.left - labelWidth / 2
        val top = (pos.top - 1).coerceAtLeast(0)
        val rect = Rect.byLTWH(left, top, labelWidth, 1)
        val text = Text(rect, isTextEditable = false)
        text.setText(label)
        // A normal ASCII space is deliberately used as an opaque one-cell background. Without it,
        // an elbow behind a multi-word label leaks through its spaces ("security╰event").
        text.setExtra(opaqueNoBorderTextExtra(TextAlign.HorizontalAlign.MIDDLE))
        return text
    }

    // --- Extra builders --------------------------------------------------

    private fun borderedRectExtra(borderStyleId: String, rounded: Boolean): RectangleExtra =
        RectangleExtra(
            isFillEnabled = false,
            userSelectedFillStyle = ShapeExtraManager.getRectangleFillStyle("F1"),
            isBorderEnabled = true,
            userSelectedBorderStyle = ShapeExtraManager.getRectangleBorderStyle(borderStyleId),
            dashPattern = StraightStrokeDashPattern.SOLID,
            isRoundedCorner = rounded
        )

    private fun noBorderTextExtra(hAlign: TextAlign.HorizontalAlign): TextExtra =
        TextExtra(
            boundExtra = RectangleExtra(
                isFillEnabled = false,
                userSelectedFillStyle = ShapeExtraManager.getRectangleFillStyle("F1"),
                isBorderEnabled = false,
                userSelectedBorderStyle = ShapeExtraManager.getRectangleBorderStyle("S1"),
                dashPattern = StraightStrokeDashPattern.SOLID,
                isRoundedCorner = false
            ),
            textAlign = TextAlign(hAlign, TextAlign.VerticalAlign.TOP)
        )

    private fun opaqueNoBorderTextExtra(hAlign: TextAlign.HorizontalAlign): TextExtra =
        TextExtra(
            boundExtra = RectangleExtra(
                isFillEnabled = true,
                userSelectedFillStyle = ShapeExtraManager.getRectangleFillStyle("F1"),
                isBorderEnabled = false,
                userSelectedBorderStyle = ShapeExtraManager.getRectangleBorderStyle("S1"),
                dashPattern = StraightStrokeDashPattern.SOLID,
                isRoundedCorner = false
            ),
            textAlign = TextAlign(hAlign, TextAlign.VerticalAlign.TOP)
        )
}
