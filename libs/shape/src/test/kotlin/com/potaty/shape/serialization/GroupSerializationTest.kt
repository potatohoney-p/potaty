/*
 * Copyright (c) 2023, tuanchauict
 * Copyright (c) 2026, Potaty
 */

package com.potaty.shape.serialization

import com.potaty.graphics.geo.Rect
import com.potaty.shape.ShapeExtraManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class GroupSerializationTest {
    @Test
    fun test() {
        val rectangle = SerializableRectangle(
            id = null,
            isIdTemporary = true,
            versionCode = 0,
            bound = Rect.byLTRB(0, 0, 1, 1),
            extra = ShapeExtraManager.defaultRectangleExtra.toSerializableExtra()
        )
        val group = SerializableGroup(
            id = null,
            isIdTemporary = true,
            versionCode = 0,
            shapes = listOf(rectangle)
        )
        val string = Json.encodeToString(group)
        println(string)
        val g = Json.decodeFromString<SerializableGroup>(string)
        println(g)
        assertEquals(group, g)
    }
}
