/*
 * Copyright (c) 2023, tuanchauict
 * Copyright (c) 2026, Potaty
 */

package com.potaty.html.canvas

import com.potaty.common.MouseCursor
import com.potaty.graphics.board.PotatyBoard
import com.potaty.graphics.geo.MousePointer
import com.potaty.graphics.geo.Point
import com.potaty.graphics.geo.Rect
import com.potaty.graphics.geo.Size
import com.potaty.html.Canvas
import com.potaty.html.canvas.canvas.AxisCanvasViewController
import com.potaty.html.canvas.canvas.BoardCanvasViewController
import com.potaty.html.canvas.canvas.DrawingInfoController
import com.potaty.html.canvas.canvas.GridCanvasViewController
import com.potaty.html.canvas.canvas.InteractionCanvasViewController
import com.potaty.html.canvas.canvas.SelectionCanvasViewController
import com.potaty.html.canvas.mouse.MouseEventObserver
import com.potaty.lifecycle.LifecycleOwner
import com.potaty.livedata.LiveData
import com.potaty.livedata.MediatorLiveData
import com.potaty.livedata.distinctUntilChange
import com.potaty.shapebound.InteractionBound
import com.potaty.shapebound.InteractionPoint
import com.potaty.ui.appstate.state.ScrollMode
import kotlinx.dom.addClass
import org.w3c.dom.HTMLDivElement

/**
 * A view controller class which renders the board to user.
 */
class CanvasViewController(
    lifecycleOwner: LifecycleOwner,
    private val container: HTMLDivElement,
    axisContainer: HTMLDivElement,
    board: PotatyBoard,
    windowSizeLiveData: LiveData<Size>,
    shiftKeyStateLiveData: LiveData<Boolean>,
    scrollModeLiveData: LiveData<ScrollMode>
) {
    private val drawingInfoController = DrawingInfoController(container)

    private val gridCanvasViewController: GridCanvasViewController
    private val boardCanvasViewController: BoardCanvasViewController
    private val interactionCanvasViewController: InteractionCanvasViewController
    private val selectionCanvasViewController: SelectionCanvasViewController
    private val axisCanvasViewController: AxisCanvasViewController

    val windowBoundPx: Rect
        get() = gridCanvasViewController.drawingInfo.boundPx

    private val windowBoardBoundMediatorLiveData: MediatorLiveData<Rect> =
        MediatorLiveData(Rect.ZERO)
    val windowBoardBoundLiveData: LiveData<Rect> = windowBoardBoundMediatorLiveData

    private val drawingInfo: DrawingInfoController.DrawingInfo
        get() = drawingInfoController.drawingInfoLiveData.value

    private val mouseEventController = MouseEventObserver(
        lifecycleOwner,
        container,
        drawingInfoController.drawingInfoLiveData,
        shiftKeyStateLiveData,
        scrollModeLiveData
    )

    val mousePointerLiveData: LiveData<MousePointer> = mouseEventController.mousePointerLiveData
    val drawingOffsetPointPxLiveData: LiveData<Point> =
        mouseEventController.drawingOffsetPointPxLiveData

    init {
        val drawingInfoLiveData = drawingInfoController.drawingInfoLiveData

        mouseEventController.drawingOffsetPointPxLiveData.observe(
            lifecycleOwner,
            throttleDurationMillis = 0,
            listener = drawingInfoController::setOffset
        )

        container.addClass("top-divider")
        container.oncontextmenu = { false }

        axisCanvasViewController = AxisCanvasViewController(
            lifecycleOwner,
            axisContainer,
            drawingInfoLiveData
        ) {
            mouseEventController.forceUpdateOffset(Point.ZERO)
        }

        gridCanvasViewController = GridCanvasViewController(
            lifecycleOwner,
            Canvas(container, CLASS_NAME_GRID),
            drawingInfoLiveData
        )
        boardCanvasViewController = BoardCanvasViewController(
            lifecycleOwner,
            Canvas(container, CLASS_NAME_BOARD),
            board,
            drawingInfoLiveData
        )
        interactionCanvasViewController = InteractionCanvasViewController(
            lifecycleOwner,
            Canvas(container, CLASS_NAME_INTERACTION),
            drawingInfoLiveData,
            mouseEventController.mousePointerLiveData
        )
        selectionCanvasViewController = SelectionCanvasViewController(
            lifecycleOwner,
            Canvas(container, CLASS_NAME_SELECTION),
            drawingInfoLiveData
        )

        windowSizeLiveData.distinctUntilChange().observe(lifecycleOwner) {
            updateCanvasSize()
        }
        windowBoardBoundMediatorLiveData.add(drawingInfoLiveData) { value = it.boardBound }
    }

    /**
     * Redraws all content on the canvas.
     */
    fun fullyRedraw() {
        gridCanvasViewController.draw()
        axisCanvasViewController.draw()
        boardCanvasViewController.draw()
        interactionCanvasViewController.draw()
        selectionCanvasViewController.draw()
    }

    fun drawBoard() {
        boardCanvasViewController.draw()
        interactionCanvasViewController.draw()
        selectionCanvasViewController.draw()
    }

    fun drawInteractionBounds(interactionBounds: List<InteractionBound>) {
        interactionCanvasViewController.interactionBounds = interactionBounds
        interactionCanvasViewController.draw()
    }

    fun drawSelectionBound(bound: Rect?) {
        selectionCanvasViewController.selectingBound = bound
        selectionCanvasViewController.draw()
    }

    fun getInteractionPoint(pointPx: Point): InteractionPoint? =
        interactionCanvasViewController.getInteractionPoint(pointPx)

    fun setFont(fontSize: Int) {
        drawingInfoController.setFont(fontSize)
    }

    fun setOffset(offsetPx: Point) {
        mouseEventController.forceUpdateOffset(offsetPx)
    }

    private fun updateCanvasSize() {
        val widthPx = container.clientWidth
        val heightPx = container.clientHeight

        // Avoid layout mistake on Safari when height is set to 0 after being correct.
        if (widthPx == 0 || heightPx == 0) {
            return
        }
        drawingInfoController.setSize(widthPx, heightPx)
    }

    fun setMouseCursor(mouseCursor: MouseCursor) {
        container.style.cursor = mouseCursor.value
    }

    fun toXPx(column: Double): Double = drawingInfo.toXPx(column)

    fun toYPx(row: Double): Double = drawingInfo.toYPx(row)

    fun toWidthPx(width: Double) = drawingInfo.toWidthPx(width)

    fun toHeightPx(height: Double) = drawingInfo.toHeightPx(height)

    companion object {
        private const val CLASS_NAME_GRID = "grid-canvas absolute top-0 left-0"
        private const val CLASS_NAME_BOARD = "board-canvas absolute top-0 left-0"
        private const val CLASS_NAME_INTERACTION = "interaction-canvas absolute top-0 left-0"
        private const val CLASS_NAME_SELECTION = "selection-canvas absolute top-0 left-0"
    }
}
