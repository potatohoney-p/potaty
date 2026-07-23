/*
 * Copyright (c) 2024, tuanchauict
 * Copyright (c) 2026, Potaty
 */

package com.potaty.graphics.board

import com.potaty.graphics.board.CrossingResources.createExcludeMask
import com.potaty.graphics.board.CrossingResources.getCharMask
import com.potaty.graphics.board.CrossingResources.maskToString
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A test for [CrossingResources].
 */
class CrossingResourcesTest {
    @Test
    fun testExcludeMask() {
        console.log(maskToString(getCharMask('║', CrossingResources.MASK_CROSS)))
        console.log(maskToString(createExcludeMask(getCharMask('║', CrossingResources.MASK_CROSS))))
        assertEquals(
            0b001100110011,
            createExcludeMask(getCharMask('│', CrossingResources.MASK_CROSS))
        )
        assertEquals(
            0b001100110011,
            createExcludeMask(getCharMask('┃', CrossingResources.MASK_CROSS))
        )
        assertEquals(
            0b001100110011,
            createExcludeMask(getCharMask('║', CrossingResources.MASK_CROSS))
        )

        assertEquals(
            0b0110011001100,
            createExcludeMask(getCharMask('─', CrossingResources.MASK_CROSS))
        )
        assertEquals(
            0b0110011001100,
            createExcludeMask(getCharMask('━', CrossingResources.MASK_CROSS))
        )
        assertEquals(
            0b0110011001100,
            createExcludeMask(getCharMask('═', CrossingResources.MASK_CROSS))
        )
    }
}
