/*
 * Copyright (c) 2023, tuanchauict
 * Copyright (c) 2026, Potaty
 */

package com.potaty.shape.shape

import com.potaty.graphics.geo.Point
import com.potaty.graphics.geo.Rect
import com.potaty.shape.ShapeExtraManager
import com.potaty.shape.extra.RectangleExtra
import com.potaty.shape.extra.ShapeExtra
import com.potaty.shape.serialization.AbstractSerializableShape
import com.potaty.shape.serialization.SerializableRectangle

/**
 * A rectangle shape.
 */
class Rectangle(
    rect: Rect,
    id: String? = null,
    parentId: String? = null
) : AbstractShape(id, parentId = parentId) {

    override var bound: Rect = rect
        set(value) = update {
            val isUpdated = field != value
            field = value
            isUpdated
        }

    override var extra: RectangleExtra = ShapeExtraManager.defaultRectangleExtra
        private set

    /**
     * The content of this shape also includes all vertical/horizontal lines created by [startPoint]
     * and [endPoint].
     */
    constructor(
        startPoint: Point,
        endPoint: Point,
        id: String? = null,
        parentId: String? = null
    ) : this(
        Rect.byLTRB(startPoint.left, startPoint.top, endPoint.left, endPoint.top),
        id = id,
        parentId = parentId
    )

    internal constructor(
        serializableRectangle: SerializableRectangle,
        parentId: String? = null
    ) : this(
        serializableRectangle.bound,
        id = serializableRectangle.actualId,
        parentId = parentId
    ) {
        extra = RectangleExtra(serializableRectangle.extra)
        versionCode = serializableRectangle.versionCode
    }

    override fun toSerializableShape(isIdIncluded: Boolean): AbstractSerializableShape =
        SerializableRectangle(
            id,
            isIdTemporary = !isIdIncluded,
            versionCode,
            bound,
            extra = extra.toSerializableExtra()
        )

    override fun setBound(newBound: Rect) {
        bound = newBound
    }

    override fun setExtra(newExtra: ShapeExtra) {
        check(newExtra is RectangleExtra) {
            "New extra is not a RectangleExtra (${newExtra::class})"
        }
        if (newExtra == extra) {
            return
        }
        update {
            extra = newExtra
            true
        }
    }
}
