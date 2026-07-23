/*
 * Copyright (c) 2023, tuanchauict
 * Copyright (c) 2026, Potaty
 */

package com.potaty.shape.serialization

import com.potaty.common.currentTimeMillis
import com.potaty.graphics.geo.Point
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Version of the mono file
 *
 * # Version 0
 * No mono file format. It was just a serialization of the root group
 *
 * # Version 1
 * The first version of PotatyFile.
 * - root: root group content
 * - extra:
 *     - name
 *     - offset
 * - version: 1
 * - modified_timestamp_millis: timestamp in millisecond (local time)
 *
 * # Version 2
 * Include `connectors`
 * - connectors: list of serialization of line connectors
 */
const val MONO_FILE_VERSION = 2

/**
 * A data class for serializing shape to Json and load shape from Json.
 */
@Serializable
data class PotatyFile internal constructor(
    @SerialName("root")
    val root: SerializableGroup,
    @SerialName("extra")
    val extra: Extra,
    @SerialName("version")
    val version: Int,
    @SerialName("modified_timestamp_millis")
    val modifiedTimestampMillis: Long,
    @SerialName("connectors")
    val connectors: List<SerializableLineConnector> = emptyList()
) {
    constructor(
        root: SerializableGroup,
        connectors: List<SerializableLineConnector>,
        extra: Extra
    ) : this(
        root = root,
        connectors = connectors,
        extra = extra,
        version = MONO_FILE_VERSION,
        modifiedTimestampMillis = currentTimeMillis().toLong()
    )
}

@Serializable
data class Extra(
    @SerialName("name")
    val name: String,
    @SerialName("offset")
    val offset: Point = Point.ZERO
)
