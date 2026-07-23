/*
 * Copyright (c) 2026, Potaty
 *
 * Covers the group-label clipping fix in IrShapeMapper.toShapes: a title that fits its container is
 * rendered verbatim at the historical +2/+1 offset (behaviour-preserving for golden output), while
 * an over-wide title is clipped with an ellipsis so it can never spill past the container border.
 */

package com.potaty.render

import com.potaty.graphics.geo.Rect
import com.potaty.layout.GroupBox
import com.potaty.layout.LayoutResult
import com.potaty.shape.shape.Text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IrShapeMapperGroupLabelTest {

    private fun layoutWithGroup(label: String, width: Int): LayoutResult {
        val bounds = Rect.byLTWH(0, 0, width, 6)
        return LayoutResult(
            nodes = emptyList(),
            edges = emptyList(),
            groups = listOf(GroupBox(groupId = "g1", label = label, bounds = bounds)),
            canvas = bounds
        )
    }

    /** With no nodes/edges, the only Text shape produced is the group title. */
    private fun groupLabelShape(layout: LayoutResult): Text? =
        IrShapeMapper.toShapes(layout, StyleProfile.CLEAN).filterIsInstance<Text>().singleOrNull()

    @Test
    fun titleThatFitsIsRenderedVerbatimAtHistoricalOffset() {
        // width 51 leaves plenty of room for a 17-char title (matches the golden fixture case).
        val layout = layoutWithGroup("Network Interface", width = 51)
        val text = groupLabelShape(layout)!!
        assertEquals("Network Interface", text.text, "fitting title is unchanged")
        // Historical placement: left + 2, top + 1, exact label width.
        assertEquals(2, text.bound.left, "title keeps its +2 left inset")
        assertEquals(1, text.bound.top, "title keeps its +1 top offset")
        assertEquals("Network Interface".length, text.bound.width, "title width == label length")
    }

    @Test
    fun overWideTitleIsClippedWithEllipsisAndStaysInsideTheBorder() {
        // Interior available = width - leftInset(2) - rightMargin(1).
        val width = 14
        val available = width - 2 - 1 // == 11
        val layout = layoutWithGroup("Relational Engine Cluster", width = width)
        val text = groupLabelShape(layout)!!
        assertTrue(text.text.length <= available, "clipped title fits the available interior width")
        assertTrue(text.text.endsWith("…"), "clipped title is ellipsis-terminated")
        // Right edge must stay left of the container's right border.
        assertTrue(text.bound.right < width - 1, "title never reaches the right border column")
        // Prefix of the original (minus the ellipsis) is preserved.
        assertEquals("Relational", text.text.substring(0, text.text.length - 1).trimEnd())
    }

    @Test
    fun titleExactlyFillingInteriorIsNotClipped() {
        // available = 14 - 2 - 1 = 11; an 11-char label should render verbatim (boundary case).
        val layout = layoutWithGroup("Eleven Char", width = 14) // "Eleven Char" == 11 chars
        val text = groupLabelShape(layout)!!
        assertEquals("Eleven Char", text.text, "title exactly filling the interior is not clipped")
    }

    @Test
    fun blankTitleEmitsNoShape() {
        val layout = layoutWithGroup("   ", width = 40)
        assertNull(groupLabelShape(layout), "whitespace-only title produces no group label shape")
    }

    @Test
    fun veryNarrowContainerDropsTheTitleRatherThanOverflowing() {
        // width 2 -> available = 2 - 2 - 1 = -1, no room for any visible character.
        val layout = layoutWithGroup("X", width = 2)
        assertNull(
            groupLabelShape(layout),
            "no room for the title means no shape, never an overflow"
        )
    }
}
