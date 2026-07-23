/*
 * Copyright (c) 2023, tuanchauict
 * Copyright (c) 2026, Potaty
 */

package com.potaty.shape.serialization

import com.potaty.graphics.geo.Point
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * A util object for serializing shape to Json and load shape from Json
 */
object ShapeSerializationUtil {
    fun toShapeJson(serializableShape: AbstractSerializableShape): String =
        Json.encodeToString(serializableShape)

    fun fromShapeJson(jsonString: String): AbstractSerializableShape? = try {
        Json.decodeFromString(jsonString)
    } catch (e: Exception) {
        console.error("Error while restoring shapes")
        console.error(e)
        null
    }

    fun toConnectorsJson(connectors: List<SerializableLineConnector>): String =
        Json.encodeToString(connectors)

    fun fromConnectorsJson(jsonString: String): List<SerializableLineConnector> = try {
        Json.decodeFromString(jsonString)
    } catch (e: Exception) {
        console.error("Error while restoring connectors")
        console.error(e)
        emptyList()
    }

    fun toPotatyFileJson(
        name: String,
        serializableShape: SerializableGroup,
        connectors: List<SerializableLineConnector>,
        offset: Point
    ): String {
        val extra = Extra(name, offset)
        val potatyFile = PotatyFile(serializableShape, connectors, extra)
        return Json.encodeToString(potatyFile)
    }

    fun fromPotatyFileJson(jsonString: String): PotatyFile? = try {
        Json.decodeFromString<PotatyFile>(jsonString)
    } catch (e: Exception) {
        // Fallback to version 0
        val shape = fromShapeJson(jsonString) as? SerializableGroup
        if (shape != null) {
            PotatyFile(
                root = shape,
                connectors = emptyList(),
                extra = Extra("", Point.ZERO)
            )
        } else {
            null
        }
    }
}
