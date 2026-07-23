/*
 * Copyright (c) 2023, tuanchauict
 * Copyright (c) 2026, Potaty
 */

package com.potaty.shape.extra.style

import com.potaty.graphics.bitmap.drawable.Drawable

/**
 * A class for defining a border style for rectangle.
 *
 * @param id is the key for retrieving predefined [RectangleBorderStyle] when serialization.
 * @param displayName is the text visible on the UI tool for selection.
 */
class RectangleBorderStyle(
    val id: String,
    val displayName: String,
    val drawable: Drawable
)
