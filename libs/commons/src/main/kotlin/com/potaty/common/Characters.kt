/*
 * Copyright (c) 2023, tuanchauict
 * Copyright (c) 2026, Potaty
 */

package com.potaty.common

object Characters {
    // Transparent in both rendering and selection
    const val TRANSPARENT_CHAR: Char = 0.toChar()

    // Transparent in rendering but visible for selection
    const val HALF_TRANSPARENT_CHAR: Char = 1.toChar()

    // Reserves the second terminal cell occupied by a wide glyph. It is stored on the board so
    // connectors cannot overwrite the glyph, but serializers and the canvas never draw it.
    const val WIDE_CONTINUATION_CHAR: Char = 2.toChar()

    const val NBSP: Char = 0x00A0.toChar()

    /**
     * Copies [length] characters from [src] from [srcOffset] into [dest] from [destOffset].
     * If the character is [TRANSPARENT_CHAR], ignore.
     */
    fun copyChars(
        src: List<Char>,
        srcOffset: Int,
        dest: MutableList<Char>,
        destOffset: Int,
        length: Int
    ) {
        src.subList(srcOffset, srcOffset + length).forEachIndexed { index, char ->
            if (char != TRANSPARENT_CHAR) {
                dest[destOffset + index] = char
            }
        }
    }

    val Char.isTransparent: Boolean
        get() = this == TRANSPARENT_CHAR

    val Char.isHalfTransparent: Boolean
        get() = this == HALF_TRANSPARENT_CHAR

    val Char.isWideContinuation: Boolean
        get() = this == WIDE_CONTINUATION_CHAR
}
