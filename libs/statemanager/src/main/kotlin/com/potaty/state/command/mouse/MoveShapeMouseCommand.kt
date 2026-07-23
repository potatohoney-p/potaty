/*
 * Copyright (c) 2023, tuanchauict
 * Copyright (c) 2026, Potaty
 */

package com.potaty.state.command.mouse

import com.potaty.common.MouseCursor
import com.potaty.graphics.geo.MousePointer
import com.potaty.graphics.geo.Point
import com.potaty.shape.shape.AbstractShape
import com.potaty.state.command.CommandEnvironment
import com.potaty.state.command.mouse.MouseCommand.CommandResultType
import com.potaty.state.utils.UpdateShapeBoundHelper

/**
 * A [MouseCommand] for moving selected shapes.
 */
internal class MoveShapeMouseCommand(
    private val shapes: Set<AbstractShape>,
    relatedShapes: Sequence<AbstractShape>
) : MouseCommand {
    override val mouseCursor: MouseCursor = MouseCursor.MOVE

    private val initialPositions = shapes.associate { it.id to it.bound.position } +
        relatedShapes.associate { it.id to it.bound.position }

    override fun execute(
        environment: CommandEnvironment,
        mousePointer: MousePointer
    ): CommandResultType {
        val offset = when (mousePointer) {
            is MousePointer.Drag -> mousePointer.boardCoordinate - mousePointer.mouseDownPoint
            is MousePointer.Up -> mousePointer.boardCoordinate - mousePointer.mouseDownPoint
            is MousePointer.Down,
            is MousePointer.Click,
            is MousePointer.DoubleClick,
            is MousePointer.Move,
            MousePointer.Idle -> Point.ZERO
        }

        UpdateShapeBoundHelper.moveShapes(
            environment,
            shapes,
            isUpdateConfirmed = mousePointer is MousePointer.Up
        ) { shape -> initialPositions[shape.id]?.let { it + offset } }

        environment.updateInteractionBounds()

        val isDone = mousePointer is MousePointer.Up || mousePointer == MousePointer.Idle
        return if (isDone) CommandResultType.DONE else CommandResultType.WORKING
    }
}
