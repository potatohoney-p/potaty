/*
 * Copyright (c) 2026, Potaty
 */

package com.potaty.common

/**
 * Terminal-style display-cell accounting for deterministic text diagrams.
 *
 * Potaty's board coordinates are monospace cells, not UTF-16 code units. Hangul, CJK, and
 * full-width glyphs occupy two cells in terminals and in the bundled D2Coding output font. Keeping
 * this rule in one place prevents labels, borders, and connector annotations from drifting apart.
 */
object DisplayCells {
    fun width(text: String): Int = glyphs(text).sumOf(Glyph::width)

    fun charWidth(char: Char): Int {
        val code = char.code
        return when {
            code < 0x20 || code in 0x7f..0x9f -> 0
            isWideCodeUnit(code) -> 2
            else -> 1
        }
    }

    fun chunks(text: String, maxWidth: Int): List<String> {
        require(maxWidth > 0) { "maxWidth must be positive" }
        if (text.isEmpty()) return listOf("")

        val chunks = mutableListOf<String>()
        var current = StringBuilder()
        var currentWidth = 0
        for (glyph in glyphs(text)) {
            if (glyph.width > maxWidth) {
                if (current.isNotEmpty()) {
                    chunks += current.toString()
                    current = StringBuilder()
                    currentWidth = 0
                }
                // A two-cell glyph cannot be represented inside a one-cell label. Use a visible,
                // deterministic one-cell replacement instead of overwriting the right border.
                chunks += NARROW_REPLACEMENT
                continue
            }
            if (
                glyph.width > 0 &&
                current.isNotEmpty() &&
                currentWidth + glyph.width > maxWidth
            ) {
                chunks += current.toString()
                current = StringBuilder()
                currentWidth = 0
            }
            current.append(glyph.value)
            currentWidth += glyph.width
        }
        if (current.isNotEmpty()) chunks += current.toString()
        return chunks.ifEmpty { listOf("") }
    }

    fun truncate(text: String, maxWidth: Int, ellipsis: String = "…"): String {
        if (maxWidth <= 0) return ""
        if (width(text) <= maxWidth) return text

        val ellipsisWidth = width(ellipsis)
        if (ellipsisWidth > maxWidth) return chunks(ellipsis, maxWidth).first()
        val contentWidth = maxWidth - ellipsisWidth
        val prefix = StringBuilder()
        var used = 0
        for (glyph in glyphs(text)) {
            if (used + glyph.width > contentWidth) break
            prefix.append(glyph.value)
            used += glyph.width
        }
        return prefix.append(ellipsis).toString()
    }

    private data class Glyph(val value: String, val width: Int)

    private fun glyphs(text: String): List<Glyph> {
        val result = ArrayList<Glyph>(text.length)
        var index = 0
        while (index < text.length) {
            val first = text[index]
            val firstCode = first.code
            if (
                firstCode in HIGH_SURROGATE_RANGE &&
                index + 1 < text.length &&
                text[index + 1].code in LOW_SURROGATE_RANGE
            ) {
                result += Glyph(text.substring(index, index + 2), 2)
                index += 2
            } else {
                result += Glyph(first.toString(), charWidth(first))
                index += 1
            }
        }
        return result
    }

    private fun isWideCodeUnit(code: Int): Boolean =
        code in 0x1100..0x115f ||
            code == 0x2329 ||
            code == 0x232a ||
            code in 0x2e80..0xa4cf ||
            code in 0xac00..0xd7a3 ||
            code in 0xf900..0xfaff ||
            code in 0xfe10..0xfe19 ||
            code in 0xfe30..0xfe6f ||
            code in 0xff00..0xff60 ||
            code in 0xffe0..0xffe6

    private const val NARROW_REPLACEMENT = "?"

    private val HIGH_SURROGATE_RANGE = 0xd800..0xdbff
    private val LOW_SURROGATE_RANGE = 0xdc00..0xdfff
}
