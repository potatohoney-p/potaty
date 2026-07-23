/*
 * Copyright (c) 2023, tuanchauict
 * Copyright (c) 2026, Potaty
 */

package com.potaty.graphics.board

import com.potaty.graphics.geo.Point
import com.potaty.graphics.geo.Rect
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A test for [PotatyBoard]
 */
class PotatyBoardTest {
    private val target = PotatyBoard().apply {
        clearAndSetWindow(Rect.byLTWH(-100, -100, 200, 200))
    }

    @Test
    fun testGetSet() {
        val points = listOf(-48, -32, -18, -16, 0, 16, 18, 32, 48).map { Point(it, it) }

        points.forEach {
            assertEquals(Pixel.TRANSPARENT_PIXEL, target[it])
        }
        val chars = "012345678"
        chars.forEachIndexed { index, c -> target.set(points[index], c, Highlight.NO) }
        chars.forEachIndexed { index, c ->
            assertEquals(c, target[points[index]].visualChar)
        }

        assertEquals(7, target.boardCount)
    }

    @Test
    fun testFill() {
        target.fill(Rect.byLTWH(1, 1, 3, 3), 'A', Highlight.NO)
        assertEquals(
            """
                |                
                | AAA            
                | AAA            
                | AAA            
                |                
                |                
                |                
                |                
                |                
                |                
                |                
                |                
                |                
                |                
                |                
                |                
            """.trimMargin(),
            target.toString()
        )
        assertEquals(1, target.boardCount)

        target.fill(Rect.byLTWH(-3, -3, 3, 3), 'B', Highlight.NO)
        assertEquals(
            """
                |                                
                |                                
                |                                
                |                                
                |                                
                |                                
                |                                
                |                                
                |                                
                |                                
                |                                
                |                                
                |                                
                |             BBB                
                |             BBB                
                |             BBB                
                |                                
                |                 AAA            
                |                 AAA            
                |                 AAA            
                |                                
                |                                
                |                                
                |                                
                |                                
                |                                
                |                                
                |                                
                |                                
                |                                
                |                                
                |                                
            """.trimMargin(),
            target.toString()
        )
        assertEquals(2, target.boardCount)

        target.fill(Rect.byLTWH(-1, 0, 3, 1), 'C', Highlight.NO)
        assertEquals(
            """
                |                                
                |                                
                |                                
                |                                
                |                                
                |                                
                |                                
                |                                
                |                                
                |                                
                |                                
                |                                
                |                                
                |             BBB                
                |             BBB                
                |             BBB                
                |               CCC              
                |                 AAA            
                |                 AAA            
                |                 AAA            
                |                                
                |                                
                |                                
                |                                
                |                                
                |                                
                |                                
                |                                
                |                                
                |                                
                |                                
                |                                
            """.trimMargin(),
            target.toString()
        )
        assertEquals(3, target.boardCount)
    }
}
