/*
 * Copyright (c) 2023, tuanchauict
 * Copyright (c) 2026, Potaty
 */

package com.potaty.shape.serialization

import com.potaty.graphics.geo.Point
import com.potaty.graphics.geo.PointF
import com.potaty.shape.shape.Line
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * A test for [SerializableLineConnector]
 */
class SerializableLineConnectorTest {
    @Test
    fun test() {
        val serializableLineConnector = SerializableLineConnector(
            "line",
            Line.Anchor.START,
            "target",
            PointF(1.2, 4.5),
            Point(5, 10)
        )

        val string = Json.encodeToString(serializableLineConnector)
        println(string)

        assertEquals(serializableLineConnector, Json.decodeFromString(string))
    }
}
