/*
 * Copyright (c) 2023, tuanchauict
 * Copyright (c) 2026, Potaty
 */

package com.potaty.bitmap.manager.factory

import com.potaty.graphics.bitmap.PotatyBitmap
import com.potaty.graphics.geo.Size
import com.potaty.shape.extra.RectangleExtra
import com.potaty.shape.extra.manager.predefined.PredefinedStraightStrokeStyle
import com.potaty.shape.extra.style.StraightStrokeDashPattern
import com.potaty.shape.extra.style.StraightStrokeStyle

object RectangleBitmapFactory {

    fun toBitmap(size: Size, extra: RectangleExtra): PotatyBitmap {
        val bitmapBuilder = PotatyBitmap.Builder(size.width, size.height)

        val fillDrawable = extra.fillStyle?.drawable
        val strokeStyle = extra.strokeStyle

        if (fillDrawable == null && strokeStyle == null) {
            // Draw a half transparent character for selection.
            // Half transparent is not displayed on the canvas but makes the shape selectable.
            bitmapBuilder.drawBorder(
                size,
                PredefinedStraightStrokeStyle.NO_STROKE,
                extra.dashPattern
            )
            return bitmapBuilder.toBitmap()
        }

        if (fillDrawable != null) {
            bitmapBuilder.fill(0, 0, fillDrawable.toBitmap(size.width, size.height))
        }

        val isStrokeAllowed = fillDrawable == null || size.width > 1 && size.height > 1
        if (isStrokeAllowed && strokeStyle != null) {
            bitmapBuilder.drawBorder(size, strokeStyle, extra.dashPattern)
        }

        return bitmapBuilder.toBitmap()
    }

    private fun PotatyBitmap.Builder.drawBorder(
        size: Size,
        strokeStyle: StraightStrokeStyle,
        dashPattern: StraightStrokeDashPattern
    ) {
        if (size.width == 1 && size.height == 1) {
            put(
                row = 0,
                column = 0,
                visualChar = '□',
                directionChar = '□'
            )
            return
        }

        val left = 0
        val top = 0
        val right = size.width - 1
        val bottom = size.height - 1

        val pointChars = when {
            size.width == 1 ->
                sequenceOf(
                    PointChar.verticalLine(left, top - 1, bottom, strokeStyle.vertical)
                )

            size.height == 1 ->
                sequenceOf(
                    PointChar.horizontalLine(left - 1, right, top, strokeStyle.horizontal)
                )

            else -> sequenceOf(
                PointChar.point(left, top, strokeStyle.upRight),
                PointChar.horizontalLine(left, right, top, strokeStyle.horizontal),
                PointChar.point(right, top, strokeStyle.downLeft),
                PointChar.verticalLine(right, top, bottom, strokeStyle.vertical),
                PointChar.point(right, bottom, strokeStyle.upLeft),
                PointChar.horizontalLine(right, left, bottom, strokeStyle.horizontal),
                PointChar.point(left, bottom, strokeStyle.downRight),
                PointChar.verticalLine(left, bottom, top, strokeStyle.vertical)
            )
        }

        pointChars
            .flatMap { it }
            .forEachIndexed { index, pointChar ->
                val visualChar = if (dashPattern.isGap(index)) ' ' else pointChar.char
                put(
                    row = pointChar.top,
                    column = pointChar.left,
                    visualChar = visualChar,
                    directionChar = pointChar.char
                )
            }
    }
}
