/*
 * Copyright (c) 2023, tuanchauict
 * Copyright (c) 2026, Potaty
 */

package com.potaty.shape.extra

import com.potaty.shape.ShapeExtraManager
import com.potaty.shape.extra.style.TextAlign
import com.potaty.shape.serialization.SerializableText

/**
 * A [ShapeExtra] for [com.potaty.shape.shape.Text].
 */
data class TextExtra(
    val boundExtra: RectangleExtra,
    val textAlign: TextAlign
) : ShapeExtra() {

    constructor(serializableExtra: SerializableText.SerializableExtra) : this(
        RectangleExtra(serializableExtra.boundExtra),
        TextAlign(serializableExtra.textHorizontalAlign, serializableExtra.textVerticalAlign)
    )

    fun toSerializableExtra(): SerializableText.SerializableExtra =
        SerializableText.SerializableExtra(
            boundExtra = boundExtra.toSerializableExtra(),
            textHorizontalAlign = textAlign.horizontalAlign.ordinal,
            textVerticalAlign = textAlign.verticalAlign.ordinal
        )

    fun hasBorder(): Boolean = boundExtra.isBorderEnabled

    companion object {
        val NO_BOUND = TextExtra(
            boundExtra = ShapeExtraManager.defaultRectangleExtra.copy(
                isFillEnabled = false,
                isBorderEnabled = false
            ),
            textAlign = TextAlign(TextAlign.HorizontalAlign.LEFT, TextAlign.VerticalAlign.TOP)
        )

        fun withDefault(): TextExtra = TextExtra(
            boundExtra = ShapeExtraManager.defaultRectangleExtra,
            textAlign = ShapeExtraManager.defaultTextAlign
        )
    }
}
