/*
 * Copyright (c) 2023, tuanchauict
 * Copyright (c) 2026, Potaty
 */

package com.potaty.graphics.bitmap.drawable

import com.potaty.graphics.bitmap.PotatyBitmap

/**
 * An interface for drawable which is the minimal version of a bitmap. A drawable contains enough
 * information for generating any-size bitmap.
 */
interface Drawable {
    fun toBitmap(width: Int, height: Int): PotatyBitmap
}
