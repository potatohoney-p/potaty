/*
 * Copyright (c) 2023, tuanchauict
 * Copyright (c) 2026, Potaty
 */

package com.potaty.bitmap.manager

import com.potaty.bitmap.manager.factory.LineBitmapFactory
import com.potaty.bitmap.manager.factory.RectangleBitmapFactory
import com.potaty.bitmap.manager.factory.TextBitmapFactory
import com.potaty.graphics.bitmap.PotatyBitmap
import com.potaty.shape.shape.AbstractShape
import com.potaty.shape.shape.Group
import com.potaty.shape.shape.Line
import com.potaty.shape.shape.MockShape
import com.potaty.shape.shape.Rectangle
import com.potaty.shape.shape.Text

/**
 * A model class which manages and caches bitmap of shapes.
 * Cache-hit when both id and version of the shape valid in the cache, otherwise, cache-miss.
 */
class PotatyBitmapManager {
    private val idToBitmapMap: MutableMap<String, VersionizedBitmap> = mutableMapOf()

    fun getBitmap(shape: AbstractShape): PotatyBitmap? {
        val cachedBitmap = getCacheBitmap(shape)
        if (cachedBitmap != null) {
            return cachedBitmap
        }

        val bitmap = when (shape) {
            is Rectangle -> RectangleBitmapFactory.toBitmap(
                shape.bound.size,
                shape.extra
            )
            is Text -> TextBitmapFactory.toBitmap(
                shape.bound.size,
                shape.renderableText.getRenderableText(),
                shape.extra,
                shape.isTextEditing
            )
            is Line -> LineBitmapFactory.toBitmap(
                shape.reducedJoinPoints,
                shape.extra
            )

            is Group -> null // No draw group since it change very frequently.
            is MockShape -> null // Only for testing.
        } ?: return null
        idToBitmapMap[shape.id] = VersionizedBitmap(shape.versionCode, bitmap)
        return bitmap
    }

    private fun getCacheBitmap(shape: AbstractShape): PotatyBitmap? =
        idToBitmapMap[shape.id]?.takeIf { it.versionCode == shape.versionCode }?.bitmap

    private class VersionizedBitmap(val versionCode: Int, val bitmap: PotatyBitmap)
}
