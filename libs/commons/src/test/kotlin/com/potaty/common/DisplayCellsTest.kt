/*
 * Copyright (c) 2026, Potaty
 */

package com.potaty.common

import kotlin.test.Test
import kotlin.test.assertEquals

class DisplayCellsTest {
    @Test
    fun countsHangulAndAsciiTerminalCells() {
        assertEquals(3, DisplayCells.width("API"))
        assertEquals(5, DisplayCells.width("웹 앱"))
        assertEquals(4, DisplayCells.width("가나"))
    }

    @Test
    fun chunksWithoutSplittingWideGlyphs() {
        assertEquals(listOf("가나", "다"), DisplayCells.chunks("가나다", 4))
        assertEquals(listOf("API", "게"), DisplayCells.chunks("API게", 3))
        assertEquals(listOf("?"), DisplayCells.chunks("가", 1))
        assertEquals(1, DisplayCells.width(DisplayCells.chunks("가", 1).single()))
    }

    @Test
    fun truncatesToDisplayCellBudget() {
        val result = DisplayCells.truncate("PostgreSQL에서 계정", 12)

        assertEquals("PostgreSQL…", result)
        assertEquals(11, DisplayCells.width(result))
    }
}
