/*
 * Copyright (c) 2026, Potaty
 */

package com.potaty.render

import com.potaty.graphics.geo.DirectedPoint
import com.potaty.graphics.geo.Point
import com.potaty.graphics.geo.Rect
import com.potaty.layout.EdgeRoute
import com.potaty.layout.LayoutResult
import com.potaty.shape.shape.Text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IrShapeMapperEdgeLabelTest {
    @Test
    fun edgeLabelHasAnOpaqueSpaceBackground() {
        val route =
            EdgeRoute(
                edgeId = "e1",
                fromId = "a",
                toId = "b",
                start = DirectedPoint(DirectedPoint.Direction.HORIZONTAL, 0, 2),
                end = DirectedPoint(DirectedPoint.Direction.HORIZONTAL, 12, 2),
                waypoints = listOf(Point(0, 2), Point(12, 2)),
                label = "security event",
                labelPosition = Point(6, 2)
            )
        val layout =
            LayoutResult(
                nodes = emptyList(),
                edges = listOf(route),
                groups = emptyList(),
                canvas = Rect.byLTWH(0, 0, 13, 3)
            )

        val label =
            IrShapeMapper.toShapes(layout, StyleProfile.CLEAN)
                .filterIsInstance<Text>()
                .single()

        assertEquals("security event", label.text)
        assertTrue(label.extra.boundExtra.isFillEnabled)
        assertEquals("F1", label.extra.boundExtra.userSelectedFillStyle.id)
    }
}
