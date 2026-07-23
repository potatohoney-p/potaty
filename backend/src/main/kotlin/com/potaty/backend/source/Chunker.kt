/*
 * Copyright (c) 2026, Potaty
 *
 * Chunker (plan section 12.2). Splits canonical text into evidence-preserving chunks: blocks are
 * separated by blank lines (paragraph / heading boundaries) and capped at [maxLinesPerChunk] so a
 * single block never grows unbounded. Every chunk keeps its 1-based [startLine]..[endLine] range
 * so IR evidence can point back at the exact source span.
 */

package com.potaty.backend.source

data class TextChunk(
    val chunkIndex: Int,
    val path: String?,
    val startLine: Int,
    val endLine: Int,
    val text: String,
    val tokenCount: Int
) {
    val textHash: String
        get() = sha256(text)
}

object Chunker {
    fun chunk(
        canonicalText: String,
        path: String? = null,
        maxLinesPerChunk: Int = 40
    ): List<TextChunk> {
        if (canonicalText.isBlank()) return emptyList()
        val lines = canonicalText.split("\n")
        val chunks = mutableListOf<TextChunk>()
        var index = 0
        var i = 0
        val n = lines.size
        while (i < n) {
            while (i < n && lines[i].isBlank()) i++ // skip blank separators
            if (i >= n) break
            val startZero = i
            val block = mutableListOf<String>()
            while (i < n && lines[i].isNotBlank() && block.size < maxLinesPerChunk) {
                block.add(lines[i])
                i++
            }
            val endZero = i - 1
            val text = block.joinToString("\n")
            chunks.add(
                TextChunk(
                    chunkIndex = index++,
                    path = path,
                    startLine = startZero + 1,
                    endLine = endZero + 1,
                    text = text,
                    tokenCount = (text.length / 4).coerceAtLeast(1)
                )
            )
        }
        return chunks
    }
}
