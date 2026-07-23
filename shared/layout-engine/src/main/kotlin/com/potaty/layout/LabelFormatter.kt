/*
 * Copyright (c) 2026, Potaty
 */

package com.potaty.layout

import com.potaty.common.DisplayCells
import kotlin.math.max

/**
 * Wraps node labels into lines using the SAME greedy algorithm as the engine's
 * `Text.RenderableText`, so the box height computed during layout exactly matches what the ASCII
 * renderer draws. Returns the chosen inner width and the wrapped lines.
 */
object LabelFormatter {

    data class Wrapped(val innerWidth: Int, val lines: List<String>)

    fun wrap(label: String, metrics: LayoutMetrics): Wrapped {
        val trimmed = label.trim().ifEmpty { " " }

        // Choose the narrowest width (within bounds) that keeps the label on as few lines as
        // possible without exceeding maxInnerWidth — this yields compact, balanced boxes.
        val naturalWidth = trimmed.split("\n").maxOf(DisplayCells::width)
        val targetInner = naturalWidth
            .coerceAtLeast(metrics.minInnerWidth)
            .coerceAtMost(metrics.maxInnerWidth)

        val lines = wrapLines(trimmed, targetInner)
        // Inner width is the longest wrapped line, clamped into [minInnerWidth, maxInnerWidth].
        // wrapLines chunks over-long words to <= targetInner (<= maxInnerWidth), so the longest line
        // is normally already within bounds and this clamp is a no-op for existing fixtures; it only
        // engages defensively if a line somehow exceeds maxInnerWidth. (`max` of the bounds avoids a
        // coerceIn IllegalArgumentException should a caller pass minInnerWidth > maxInnerWidth.)
        val longest = lines.maxOf(DisplayCells::width)
        val lo = metrics.minInnerWidth
        val hi = max(metrics.minInnerWidth, metrics.maxInnerWidth)
        val actualInner = longest.coerceIn(lo, hi)
        return Wrapped(actualInner, lines)
    }

    /** Mirror of Text.RenderableText.standardizeLines (greedy fill, long-word chunking). */
    private fun wrapLines(text: String, maxRowCharCount: Int): List<String> {
        if (maxRowCharCount <= 1) return text.map { it.toString() }
        return text.split("\n").flatMap { line ->
            val out = mutableListOf(StringBuilder())
            for (word in standardizeWords(line, maxRowCharCount)) {
                val lastLine = out.last()
                val space = if (lastLine.isNotEmpty()) " " else ""
                if (
                    DisplayCells.width(lastLine.toString()) +
                    DisplayCells.width(space) +
                    DisplayCells.width(word) <= maxRowCharCount
                ) {
                    lastLine.append(space).append(word)
                } else {
                    out.add(StringBuilder(word))
                }
            }
            out.map { it.toString() }
        }
    }

    private fun standardizeWords(line: String, maxCharCount: Int): List<String> =
        line.split(" ").flatMap { word ->
            if (DisplayCells.width(word) <= maxCharCount) {
                listOf(word)
            } else {
                DisplayCells.chunks(word, maxCharCount)
            }
        }
}
