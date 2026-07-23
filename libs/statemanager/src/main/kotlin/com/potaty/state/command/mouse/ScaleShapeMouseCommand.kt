/*
 * Copyright (c) 2023, tuanchauict
 * Copyright (c) 2026, Potaty
 */

package com.potaty.state.command.mouse

import com.potaty.common.MouseCursor
import com.potaty.graphics.geo.MousePointer
import com.potaty.graphics.geo.PointF
import com.potaty.shape.command.ChangeBound
import com.potaty.shape.shape.AbstractShape
import com.potaty.shapebound.ScaleInteractionPoint
import com.potaty.state.command.CommandEnvironment
import com.potaty.state.utils.UpdateShapeBoundHelper

/**
 * A [MouseCommand] for scaling shape.
 */
internal class ScaleShapeMouseCommand(
    private val shape: AbstractShape,
    private val interactionPoint: ScaleInteractionPoint
) : MouseCommand {
    override val mouseCursor: MouseCursor? = null

    private val initialBound = shape.bound
    override fun execute(
        environment: CommandEnvironment,
        mousePointer: MousePointer
    ): MouseCommand.CommandResultType {
        when (mousePointer) {
            is MousePointer.Drag ->
                scale(environment, mousePointer.boardCoordinateF, isUpdateConfirmed = false)

            is MousePointer.Up ->
                scale(environment, mousePointer.boardCoordinateF, isUpdateConfirmed = true)

            is MousePointer.Down,
            is MousePointer.Click,
            is MousePointer.DoubleClick,
            is MousePointer.Move,
            MousePointer.Idle -> Unit
        }

        return if (mousePointer == MousePointer.Idle) {
            MouseCommand.CommandResultType.DONE
        } else {
            MouseCommand.CommandResultType.WORKING
        }
    }

    private fun scale(environment: CommandEnvironment, pointF: PointF, isUpdateConfirmed: Boolean) {
        val newBound = interactionPoint.createNewShapeBound(initialBound, pointF)
        environment.shapeManager.execute(ChangeBound(shape, newBound))

        UpdateShapeBoundHelper.updateConnectors(environment, shape, newBound, isUpdateConfirmed)
        environment.updateInteractionBounds()
    }
}
