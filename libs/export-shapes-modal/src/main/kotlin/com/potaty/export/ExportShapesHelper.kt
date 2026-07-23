/*
 * Copyright (c) 2023, tuanchauict
 * Copyright (c) 2026, Potaty
 */

package com.potaty.export

import com.potaty.graphics.bitmap.PotatyBitmap
import com.potaty.graphics.board.Highlight
import com.potaty.graphics.board.PotatyBoard
import com.potaty.graphics.geo.Rect
import com.potaty.shape.shape.AbstractShape
import com.potaty.shape.shape.Group

/** A helper class for exporting selected shapes. */
class ExportShapesHelper(
    private val getBitmap: (AbstractShape) -> PotatyBitmap?,
    private val setClipboardText: (String) -> Unit
) {

    fun exportText(shapes: List<AbstractShape>, isModalRequired: Boolean) {
        if (shapes.isEmpty()) {
            return
        }

        val left = shapes.minOf { it.bound.left }
        val right = shapes.maxOf { it.bound.right }
        val top = shapes.minOf { it.bound.top }
        val bottom = shapes.maxOf { it.bound.bottom }
        val window = Rect.byLTRB(left, top, right, bottom)

        val exportingBoard = PotatyBoard().apply { clearAndSetWindow(window) }
        drawShapesOntoExportingBoard(exportingBoard, shapes)

        val text = exportingBoard.toStringInBound(window)
        if (isModalRequired) {
            ExportShapesModal().show(text)
        } else {
            setClipboardText(text)
        }
    }

    private fun drawShapesOntoExportingBoard(
        board: PotatyBoard,
        shapes: Collection<AbstractShape>
    ) {
        for (shape in shapes) {
            if (shape is Group) {
                drawShapesOntoExportingBoard(board, shape.items)
                continue
            }
            val bitmap = getBitmap(shape) ?: continue
            board.fill(shape.bound.position, bitmap, Highlight.NO)
        }
    }
}
