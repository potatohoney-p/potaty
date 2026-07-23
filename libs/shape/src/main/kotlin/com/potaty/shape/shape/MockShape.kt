/*
 * Copyright (c) 2023, tuanchauict
 * Copyright (c) 2026, Potaty
 */

package com.potaty.shape.shape

import com.potaty.graphics.geo.Rect
import com.potaty.shape.serialization.AbstractSerializableShape

/**
 * A simple shape for testing purpose
 */
class MockShape(
    rect: Rect,
    parentId: String? = null
) : AbstractShape(id = null, parentId = parentId) {

    override fun toSerializableShape(isIdIncluded: Boolean): AbstractSerializableShape {
        TODO("Not yet implemented")
    }

    override var bound: Rect = rect
        set(value) = update {
            val isUpdated = field != value
            field = value
            isUpdated
        }
}
