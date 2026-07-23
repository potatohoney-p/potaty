/*
 * Copyright (c) 2023, tuanchauict
 * Copyright (c) 2026, Potaty
 */

package com.potaty.bitmap.manager.factory

import com.potaty.graphics.geo.Rect
import com.potaty.shape.shape.Text
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A test for [TextBitmapFactory]
 */
class TextBitmapFactoryTest {
    @Test
    fun testToBitmap() {
        val text = Text(Rect.byLTWH(0, 0, 7, 5))
        text.setText("012345678\nabc")
        val bitmap = TextBitmapFactory.toBitmap(
            text.bound.size,
            text.renderableText.getRenderableText(),
            text.extra,
            isTextEditingMode = false
        )
        assertEquals(
            """
                |┌─────┐
                |│01234│
                |│5678 │
                |│ abc │
                |└─────┘
            """.trimMargin(),
            bitmap.toString()
        )
    }

    @Test
    fun koreanGlyphsReserveTwoTerminalCells() {
        val text = Text(Rect.byLTWH(0, 0, 8, 3))
        text.setText("웹 앱")
        val bitmap =
            TextBitmapFactory.toBitmap(
                text.bound.size,
                text.renderableText.getRenderableText(),
                text.extra,
                isTextEditingMode = false
            )

        assertEquals(
            """
                |┌──────┐
                |│웹 앱 │
                |└──────┘
            """.trimMargin(),
            bitmap.toString()
        )
    }
}
