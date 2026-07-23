/*
 * Copyright (c) 2026, Potaty
 *
 * Thin, testable environment-variable accessor. Wraps System.getenv so config loading
 * can be unit-tested with an injected map.
 */

package com.potaty.backend.config

class EnvConfig(private val raw: (String) -> String?) {

    fun string(key: String, default: String): String =
        raw(key)?.takeIf { it.isNotBlank() } ?: default

    fun stringOrNull(key: String): String? = raw(key)?.takeIf { it.isNotBlank() }

    fun int(key: String, default: Int): Int = raw(key)?.toIntOrNull() ?: default

    fun bool(key: String, default: Boolean): Boolean =
        raw(key)?.let { it.equals("true", ignoreCase = true) || it == "1" } ?: default

    fun list(key: String, default: List<String>): List<String> =
        raw(key)?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: default

    companion object {
        fun system(): EnvConfig = EnvConfig { System.getenv(it) }

        fun of(map: Map<String, String>): EnvConfig = EnvConfig { map[it] }
    }
}
