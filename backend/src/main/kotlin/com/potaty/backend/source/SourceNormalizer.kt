/*
 * Copyright (c) 2026, Potaty
 *
 * Source normalizer (plan section 12.1). Converts raw input into a canonical text form with
 * stable line numbering so downstream evidence (start_line/end_line) is reproducible: CRLF/CR
 * are folded to LF and trailing whitespace is stripped per line. Content is otherwise preserved.
 */

package com.potaty.backend.source

data class NormalizedSource(
    val canonicalText: String,
    val lineCount: Int
) {
    val contentHash: String get() = sha256(canonicalText)
}

object SourceNormalizer {
    fun normalize(raw: String): NormalizedSource {
        val canonical = raw
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            // NUL cannot be stored in PostgreSQL text and terminal control characters can turn a
            // copied ASCII artifact into an ANSI/console injection. Preserve only line breaks and
            // tabs from the ISO control range.
            .filterNot {
                (it.isISOControl() && it != '\n' && it != '\t') || it.isBidiControl()
            }
            .split("\n")
            .joinToString("\n") { it.trimEnd() }
            .trimEnd('\n')
        val lines = if (canonical.isEmpty()) 0 else canonical.count { it == '\n' } + 1
        return NormalizedSource(canonical, lines)
    }
}

private fun Char.isBidiControl(): Boolean =
    this == '\u061C' ||
        this == '\u200E' ||
        this == '\u200F' ||
        this in '\u202A'..'\u202E' ||
        this in '\u2066'..'\u2069'

internal fun sha256(value: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    return digest.digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
