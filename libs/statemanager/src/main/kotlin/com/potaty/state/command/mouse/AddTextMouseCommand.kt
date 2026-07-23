/*
 * Copyright (c) 2023, tuanchauict
 * Copyright (c) 2026, Potaty
 */

package com.potaty.state.command.mouse

import com.potaty.common.MouseCursor
import com.potaty.common.exhaustive
import com.potaty.graphics.geo.MousePointer
import com.potaty.graphics.geo.Point
import com.potaty.graphics.geo.Rect
import com.potaty.graphics.geo.Size
import com.potaty.shape.command.ChangeBound
import com.potaty.shape.command.ChangeExtra
import com.potaty.shape.command.UpdateTextEditingMode
import com.potaty.shape.extra.TextExtra
import com.potaty.shape.extra.style.TextAlign
import com.potaty.shape.shape.Text
import com.potaty.state.command.CommandEnvironment
import com.potaty.state.command.text.EditTextShapeHelper

/**
 * A [MouseCommand] to add new text shape.
 * This command does two jobs:
 * 1. Identify the initial bound for the text shape
 * 2. Open a modal for entering text content when mouse up.
 */
internal class AddTextMouseCommand(private val isTextEditable: Boolean) : MouseCommand {
    override val mouseCursor: MouseCursor = MouseCursor.CROSSHAIR

    private var workingShape: Text? = null
    override fun execute(
        environment: CommandEnvironment,
        mousePointer: MousePointer
    ): MouseCommand.CommandResultType =
        when (mousePointer) {
            is MousePointer.Down -> {
                val shape = Text(
                    mousePointer.boardCoordinate,
                    mousePointer.boardCoordinate,
                    parentId = environment.workingParentGroup.id,
                    isTextEditable = isTextEditable
                )
                workingShape = shape
                environment.addShape(shape)
                environment.clearSelectedShapes()
                MouseCommand.CommandResultType.WORKING
            }

            is MousePointer.Drag -> {
                environment.changeShapeBound(
                    mousePointer.mouseDownPoint,
                    mousePointer.boardCoordinate
                )
                MouseCommand.CommandResultType.WORKING
            }

            is MousePointer.Up -> {
                onMouseUp(environment, mousePointer)
                MouseCommand.CommandResultType.WORKING_PHASE2
            }

            is MousePointer.Move,
            is MousePointer.Click,
            is MousePointer.DoubleClick,
            MousePointer.Idle -> MouseCommand.CommandResultType.UNKNOWN
        }.exhaustive

    private fun onMouseUp(environment: CommandEnvironment, mousePointer: MousePointer.Up) {
        val text = workingShape ?: return
        environment.changeShapeBound(mousePointer.mouseDownPoint, mousePointer.boardCoordinate)

        val isFreeText = text.isFreeText()
        if (isFreeText) {
            environment.makeTextFree(text)
        } else {
            environment.addSelectedShape(text)
        }

        if (isTextEditable) {
            enterTextTypeMode(environment, text, isFreeText)
        }
    }

    private fun Text.isFreeText(): Boolean =
        isTextEditable && bound.width == 1 && bound.height == 1

    private fun CommandEnvironment.makeTextFree(text: Text) {
        val boundExtra =
            text.extra.boundExtra.copy(isFillEnabled = false, isBorderEnabled = false)
        val textAlignExtra =
            text.extra.textAlign.copy(
                horizontalAlign = TextAlign.HorizontalAlign.LEFT,
                verticalAlign = TextAlign.VerticalAlign.TOP
            )
        val changeExtraCommand = ChangeExtra(text, TextExtra(boundExtra, textAlignExtra))
        shapeManager.execute(changeExtraCommand)
    }

    private fun enterTextTypeMode(
        environment: CommandEnvironment,
        text: Text,
        isFreeText: Boolean
    ) {
        environment.enterEditingMode()
        environment.shapeManager.execute(UpdateTextEditingMode(text, true))
        EditTextShapeHelper.showEditTextDialog(environment, text, isFreeText) {
            environment.shapeManager.execute(UpdateTextEditingMode(text, false))
            environment.adjustTextShape(text, isFreeText)

            environment.exitEditingMode(it.isNotEmpty())
            workingShape = null
        }
    }

    private fun CommandEnvironment.adjustTextShape(text: Text, isFreeText: Boolean) {
        if (isFreeText && text.text.isEmpty()) {
            removeShape(text)
            return
        }

        val newSize = if (isFreeText) {
            text.getFreeTextActualSize()
        } else {
            text.getNormalTextActualSize()
        }
        val newBound = text.bound.copy(size = newSize)
        shapeManager.execute(ChangeBound(text, newBound))
        addSelectedShape(text)
    }

    private fun Text.getFreeTextActualSize(): Size {
        val lines = text.split('\n')
        val maxWidth = lines.maxOf { it.length }
        return Size(maxWidth, lines.size)
    }

    private fun Text.getNormalTextActualSize(): Size {
        val height = if (extra.hasBorder()) {
            renderableText.getRenderableText().size + 2
        } else {
            renderableText.getRenderableText().size
        }
        val newHeight = if (height > bound.height) height else bound.height
        return Size(bound.width, newHeight)
    }

    private fun CommandEnvironment.changeShapeBound(point1: Point, point2: Point) {
        val currentShape = workingShape ?: return
        val rect = Rect.byLTRB(
            left = point1.left,
            top = point1.top,
            right = point2.left,
            bottom = point2.top
        )

        shapeManager.execute(ChangeBound(currentShape, rect))
    }
}
