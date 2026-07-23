/*
 * Copyright (c) 2023, tuanchauict
 * Copyright (c) 2026, Potaty
 */

package com.potaty.bitmap.manager.factory

import com.potaty.common.Characters.WIDE_CONTINUATION_CHAR
import com.potaty.common.DisplayCells
import com.potaty.graphics.bitmap.PotatyBitmap
import com.potaty.graphics.geo.Size
import com.potaty.shape.extra.TextExtra
import com.potaty.shape.extra.style.TextAlign

object TextBitmapFactory {
    fun toBitmap(
        boundSize: Size,
        renderableText: List<String>,
        extra: TextExtra,
        isTextEditingMode: Boolean
    ): PotatyBitmap {
        val bgBitmap =
            RectangleBitmapFactory.toBitmap(boundSize, extra.boundExtra)
        val bitmapBuilder = PotatyBitmap.Builder(boundSize.width, boundSize.height)
        if (!(boundSize.width == 1 && boundSize.height == 1 && isTextEditingMode)) {
            bitmapBuilder.fill(0, 0, bgBitmap)
        }

        val adjustedRenderableText = if (!isTextEditingMode) renderableText else emptyList()
        bitmapBuilder.fillText(adjustedRenderableText, boundSize, extra)
        return bitmapBuilder.toBitmap()
    }

    private fun PotatyBitmap.Builder.fillText(
        renderableText: List<String>,
        boundSize: Size,
        extra: TextExtra
    ) {
        val hasBorder = extra.hasBorder()
        val rowOffset = if (hasBorder) 1 else 0
        val colOffset = if (hasBorder) 1 else 0

        val maxTextWidth = boundSize.width - colOffset * 2
        val maxTextHeight = (boundSize.height - rowOffset * 2).coerceAtLeast(0)

        val row0 = when (extra.textAlign.verticalAlign) {
            TextAlign.VerticalAlign.TOP -> rowOffset
            TextAlign.VerticalAlign.MIDDLE ->
                if (maxTextHeight < renderableText.size) {
                    rowOffset
                } else {
                    (maxTextHeight - renderableText.size) / 2 + rowOffset
                }

            TextAlign.VerticalAlign.BOTTOM ->
                if (maxTextHeight < renderableText.size) {
                    rowOffset
                } else {
                    maxTextHeight - renderableText.size + rowOffset
                }
        }

        val horizontalAlign = extra.textAlign.horizontalAlign
        for (rowIndex in renderableText.indices.take(maxTextHeight)) {
            val row = renderableText[rowIndex]
            val rowWidth = DisplayCells.width(row)
            val col0 = when (horizontalAlign) {
                TextAlign.HorizontalAlign.LEFT -> colOffset
                TextAlign.HorizontalAlign.MIDDLE -> (maxTextWidth - rowWidth) / 2 + colOffset
                TextAlign.HorizontalAlign.RIGHT -> maxTextWidth - rowWidth + colOffset
            }
            var column = col0
            for (char in row) {
                val charWidth = DisplayCells.charWidth(char)
                if (charWidth == 0) continue
                if (char != ' ') {
                    put(
                        row = row0 + rowIndex,
                        column = column,
                        visualChar = char,
                        directionChar = char
                    )
                    if (charWidth == 2) {
                        put(
                            row = row0 + rowIndex,
                            column = column + 1,
                            visualChar = WIDE_CONTINUATION_CHAR,
                            directionChar = WIDE_CONTINUATION_CHAR
                        )
                    }
                }
                column += charWidth
            }
        }
    }
}
