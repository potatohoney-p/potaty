/*
 * Copyright (c) 2023, tuanchauict
 * Copyright (c) 2026, Potaty
 */

package com.potaty.graphics.geo

/**
 * A data class that represents a point in 2D space whose values are in float number.
 * This class is only used for calculation, should not use for serialization or storage.
 */
data class PointF(val left: Double, val top: Double) {
    val row: Double get() = top
    val column: Double get() = left
}
