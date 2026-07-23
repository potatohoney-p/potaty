/*
 * Copyright (c) 2023, tuanchauict
 * Copyright (c) 2026, Potaty
 */

package com.potaty.html

val Number.px: String
    get() = "${this}px"

fun styleOf(vararg attributes: Pair<String, String>): String =
    attributes.asSequence().map { "${it.first}: ${it.second}" }.joinToString(";")
