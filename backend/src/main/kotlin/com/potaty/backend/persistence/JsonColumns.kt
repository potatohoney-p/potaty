/*
 * Copyright (c) 2026, Potaty
 *
 * Exposed 0.42's JSON column parameter binder casts null to String on H2. This small adapter keeps
 * production JSONB semantics while binding SQL NULL correctly for nullable JSONB columns in H2.
 */

package com.potaty.backend.persistence

import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.json.JsonBColumnType
import org.jetbrains.exposed.sql.json.JsonColumnType
import org.jetbrains.exposed.sql.statements.api.PreparedStatementApi

private class NullableSafeJsonBStringColumnType : JsonColumnType<String>({ it }, { it }) {
    override fun sqlType(): String = JsonBColumnType<String>({ it }, { it }).sqlType()

    override fun setParameter(stmt: PreparedStatementApi, index: Int, value: Any?) {
        if (value == null) {
            stmt.setNull(index, this)
        } else {
            super.setParameter(stmt, index, value)
        }
    }
}

internal fun Table.jsonbString(name: String): Column<String> =
    registerColumn(name, NullableSafeJsonBStringColumnType())

/**
 * PostgreSQL JSONB normalizes whitespace and object-key order when values are read back. Compare
 * documents structurally so an idempotency fence does not reject the same JSON after persistence.
 */
internal fun jsonDocumentsEqual(first: String?, second: String?): Boolean {
    if (first == null || second == null) return first == second
    return runCatching {
        jsonElementsEqual(
            Json.parseToJsonElement(first),
            Json.parseToJsonElement(second)
        )
    }.getOrDefault(false)
}

private fun jsonElementsEqual(first: JsonElement, second: JsonElement): Boolean =
    when {
        first is JsonObject && second is JsonObject ->
            first.keys == second.keys &&
                first.all { (key, value) ->
                    jsonElementsEqual(value, requireNotNull(second[key]))
                }
        first is JsonArray && second is JsonArray ->
            first.size == second.size &&
                first.indices.all { index ->
                    jsonElementsEqual(first[index], second[index])
                }
        first is JsonPrimitive && second is JsonPrimitive ->
            jsonPrimitivesEqual(first, second)
        else -> false
    }

private fun jsonPrimitivesEqual(first: JsonPrimitive, second: JsonPrimitive): Boolean {
    if (first.isString || second.isString) {
        return first.isString == second.isString && first.content == second.content
    }
    if (first.content == second.content) return true

    val firstNumber = first.content.toBigDecimalOrNull()
    val secondNumber = second.content.toBigDecimalOrNull()
    return firstNumber != null &&
        secondNumber != null &&
        firstNumber.compareTo(secondNumber) == 0
}

private fun String.toBigDecimalOrNull(): BigDecimal? =
    runCatching { BigDecimal(this) }.getOrNull()
