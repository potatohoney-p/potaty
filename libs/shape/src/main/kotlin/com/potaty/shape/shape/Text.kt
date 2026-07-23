/*
 * Copyright (c) 2023, tuanchauict
 * Copyright (c) 2026, Potaty
 */

package com.potaty.shape.shape

import com.potaty.common.DisplayCells
import com.potaty.graphics.geo.Point
import com.potaty.graphics.geo.Rect
import com.potaty.graphics.geo.Size
import com.potaty.shape.extra.ShapeExtra
import com.potaty.shape.extra.TextExtra
import com.potaty.shape.serialization.AbstractSerializableShape
import com.potaty.shape.serialization.SerializableText
import kotlin.math.max

/**
 * A text shape which contains a bound and a text.
 *
 * TODO: Resize bound by text
 */
class Text(
    rect: Rect,
    id: String? = null,
    parentId: String? = null,
    isTextEditable: Boolean = true
) : AbstractShape(id = id, parentId = parentId) {
    private var userSettingSize: Size = Size.ZERO
        set(value) {
            field = value.takeIf { it.width >= 3 && it.height >= 3 } ?: Size.ZERO
        }

    // Text can be auto resized by text
    override var bound: Rect = rect

    val contentBound: Rect
        get() = if (extra.boundExtra.isBorderEnabled) {
            Rect.byLTWH(bound.left + 1, bound.top + 1, bound.width - 2, bound.height - 2)
        } else {
            bound
        }

    var text: String = ""
        private set
    var isTextEditable: Boolean = isTextEditable
        private set

    var isTextEditing: Boolean = false
        private set

    override var extra: TextExtra = TextExtra.withDefault()
        private set

    var renderableText: RenderableText = RenderableText.EMPTY
        private set

    constructor(
        startPoint: Point,
        endPoint: Point,
        id: String? = null,
        parentId: String? = null,
        isTextEditable: Boolean
    ) : this(
        Rect.byLTRB(startPoint.left, startPoint.top, endPoint.left, endPoint.top),
        id = id,
        parentId = parentId,
        isTextEditable = isTextEditable
    )

    internal constructor(serializableText: SerializableText, parentId: String?) : this(
        serializableText.bound,
        id = serializableText.actualId,
        parentId = parentId,
        isTextEditable = serializableText.isTextEditable
    ) {
        extra = TextExtra(serializableText.extra)
        setText(serializableText.text)
        versionCode = serializableText.versionCode
    }

    init {
        userSettingSize = rect.size
        updateRenderableText()
    }

    override fun toSerializableShape(isIdIncluded: Boolean): AbstractSerializableShape =
        SerializableText(
            id,
            isIdTemporary = !isIdIncluded,
            versionCode,
            bound,
            text,
            extra.toSerializableExtra(),
            isTextEditable
        )

    override fun setBound(newBound: Rect) = update {
        val isUpdated = bound != newBound
        userSettingSize = newBound.size
        bound = newBound

        updateRenderableText()

        isUpdated
    }

    fun setText(newText: String) = update {
        val isTextChanged = newText != text
        text = newText
        updateRenderableText()
        isTextChanged
    }

    fun setTextEditingMode(isEditing: Boolean) = update {
        val isUpdated = isTextEditing != isEditing
        isTextEditing = isEditing
        isUpdated
    }

    override fun setExtra(newExtra: ShapeExtra) {
        check(newExtra is TextExtra) {
            "New extra is not a TextExtra (${newExtra::class})"
        }
        if (newExtra == extra) {
            return
        }
        update {
            extra = newExtra
            updateRenderableText()

            true
        }
    }

    private fun updateRenderableText() {
        val maxRowCharCount =
            if (extra.hasBorder()) bound.width - 2 else bound.width
        if (text != renderableText.text ||
            maxRowCharCount != renderableText.maxRowCharCount
        ) {
            renderableText = RenderableText(text, max(maxRowCharCount, 1))
        }
    }

    fun makeTextEditable() {
        if (isTextEditable) {
            return
        }
        update {
            isTextEditable = true
            true
        }
    }

    /**
     * A class to generate renderable text.
     */
    class RenderableText(val text: String, val maxRowCharCount: Int) {
        private var renderableText: List<String>? = null
        fun getRenderableText(): List<String> {
            val nonNullRenderableText = renderableText ?: createRenderableText()
            renderableText = nonNullRenderableText
            return nonNullRenderableText
        }

        private fun createRenderableText(): List<String> =
            standardizeLines(text.split("\n"))

        private fun standardizeLines(lines: List<String>): List<String> = lines
            .flatMap { line ->
                val adjustedLines = mutableListOf(StringBuilder())
                for (word in line.toStandardizedWords(maxRowCharCount)) {
                    val lastLine = adjustedLines.last()
                    val space = if (lastLine.isNotEmpty()) " " else ""
                    val newLineLength =
                        DisplayCells.width(lastLine.toString()) +
                            DisplayCells.width(space) +
                            DisplayCells.width(word)
                    if (newLineLength <= maxRowCharCount) {
                        lastLine.append(space).append(word)
                    } else {
                        adjustedLines.add(StringBuilder(word))
                    }
                }
                adjustedLines
            }
            .map { it.toString() }

        private fun String.toStandardizedWords(maxCharCount: Int): List<String> =
            split(" ")
                .flatMap { word ->
                    if (DisplayCells.width(word) <= maxCharCount) {
                        listOf(word)
                    } else {
                        DisplayCells.chunks(word, maxCharCount)
                    }
                }

        companion object {
            val EMPTY = RenderableText("", 0)
        }
    }
}
