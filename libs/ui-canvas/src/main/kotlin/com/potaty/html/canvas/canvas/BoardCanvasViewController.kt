/*
 * Copyright (c) 2023, tuanchauict
 * Copyright (c) 2026, Potaty
 */

package com.potaty.html.canvas.canvas

import com.potaty.common.Characters.isWideContinuation
import com.potaty.graphics.board.Highlight
import com.potaty.graphics.board.Pixel
import com.potaty.graphics.board.PotatyBoard
import com.potaty.lifecycle.LifecycleOwner
import com.potaty.livedata.LiveData
import com.potaty.ui.theme.ThemeColor
import org.w3c.dom.HTMLCanvasElement

internal class BoardCanvasViewController(
    lifecycleOwner: LifecycleOwner,
    canvas: HTMLCanvasElement,
    private val board: PotatyBoard,
    drawingInfoLiveData: LiveData<DrawingInfoController.DrawingInfo>
) : BaseCanvasViewController(canvas) {

    init {
        drawingInfoLiveData.observe(lifecycleOwner, listener = ::setDrawingInfo)
    }

    override fun drawInternal() {
        context.font = drawingInfo.font
        for (row in drawingInfo.boardRowRange) {
            for (col in drawingInfo.boardColumnRange) {
                drawPixel(board.get(col, row), row, col)
            }
        }
    }

    private fun drawPixel(pixel: Pixel, row: Int, column: Int) {
        if (!pixel.isTransparent && !pixel.visualChar.isWideContinuation) {
            val color = when (pixel.highlight) {
                Highlight.NO -> ThemeColor.Shape
                Highlight.SELECTED -> ThemeColor.ShapeSelected
                Highlight.TEXT_EDITING -> ThemeColor.ShapeTextEditing
                Highlight.LINE_CONNECT_FOCUSING -> ThemeColor.ShapeLineConnectTarget
            }
            context.fillStyle = color.colorCode
            drawText(pixel.visualChar.toString(), row, column)
        }
    }
}
