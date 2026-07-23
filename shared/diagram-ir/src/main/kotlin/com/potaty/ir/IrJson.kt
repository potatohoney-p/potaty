/*
 * Copyright (c) 2026, Potaty
 */

package com.potaty.ir

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Central JSON codec for the Diagram IR.
 *
 * - [PRETTY] is used for human-facing output / golden fixtures.
 * - [COMPACT] is used on the wire.
 *
 * `ignoreUnknownKeys = true` keeps forward compatibility when a newer producer adds fields;
 * `encodeDefaults = false` keeps payloads small and fixtures readable.
 */
object IrJson {
    @OptIn(ExperimentalSerializationApi::class)
    val PRETTY: Json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        ignoreUnknownKeys = true
        encodeDefaults = false
        classDiscriminator = "kind"
    }

    val COMPACT: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        classDiscriminator = "kind"
    }

    fun encode(ir: DiagramIR, pretty: Boolean = true): String =
        (if (pretty) PRETTY else COMPACT).encodeToString(ir)

    fun decode(json: String): DiagramIR = COMPACT.decodeFromString(DiagramIR.serializer(), json)
}
