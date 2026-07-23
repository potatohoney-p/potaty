/*
 * Copyright (c) 2023, tuanchauict
 * Copyright (c) 2026, Potaty
 */

package com.potaty.graphics.bitmap.drawable

import com.potaty.graphics.bitmap.PotatyBitmap

/**
 * A drawable which simplify fills with [char].
 */
class CharDrawable(private val char: Char) : Drawable {
    override fun toBitmap(width: Int, height: Int): PotatyBitmap {
        val builder = PotatyBitmap.Builder(width, height)
        builder.fill(char)
        return builder.toBitmap()
    }
}
