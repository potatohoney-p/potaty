/*
 * Copyright (c) 2023, tuanchauict
 * Copyright (c) 2026, Potaty
 */

package com.potaty.shape.extra.manager.predefined

import com.potaty.common.Characters
import com.potaty.graphics.bitmap.drawable.CharDrawable
import com.potaty.shape.extra.style.RectangleFillStyle

/**
 * An object for listing all predefined rectangle fill styles.
 */
internal object PredefinedRectangleFillStyle {
    val NOFILLED_STYLE = RectangleFillStyle(
        id = "F0",
        displayName = "No Fill",
        CharDrawable(Characters.TRANSPARENT_CHAR)
    )

    val PREDEFINED_STYLES = listOf(
        RectangleFillStyle(
            id = "F1",
            displayName = "${Characters.NBSP}",
            CharDrawable(' ')
        ),
        RectangleFillStyle(
            id = "F2",
            displayName = "█",
            CharDrawable('█')
        ),
        RectangleFillStyle(
            id = "F3",
            displayName = "▒",
            CharDrawable('▒')
        ),
        RectangleFillStyle(
            id = "F4",
            displayName = "░",
            CharDrawable('░')
        ),
        RectangleFillStyle(
            id = "F5",
            displayName = "▚",
            CharDrawable('▚')
        )
    )

    val PREDEFINED_STYLE_MAP = PREDEFINED_STYLES.associateBy { it.id }
}
