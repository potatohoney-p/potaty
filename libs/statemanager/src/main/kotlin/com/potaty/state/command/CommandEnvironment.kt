/*
 * Copyright (c) 2023, tuanchauict
 * Copyright (c) 2026, Potaty
 */

package com.potaty.state.command

import com.potaty.graphics.geo.DirectedPoint
import com.potaty.graphics.geo.Point
import com.potaty.graphics.geo.Rect
import com.potaty.livedata.LiveData
import com.potaty.shape.ShapeManager
import com.potaty.shape.connector.ShapeConnector
import com.potaty.shape.selection.SelectedShapeManager
import com.potaty.shape.shape.AbstractShape
import com.potaty.shape.shape.Group
import com.potaty.shape.shape.RootGroup
import com.potaty.shapebound.InteractionPoint

/**
 * An interface defines apis for command to interact with the environment.
 */
internal interface CommandEnvironment {
    val shapeManager: ShapeManager

    val editingModeLiveData: LiveData<EditingMode>

    /**
     * The current working parent group, which is the group that is focused, shape actions will be
     * applied to this group.
     *
     * This is similar to the concept of "current directory" in file system.
     */
    val workingParentGroup: Group

    fun replaceRoot(newRoot: RootGroup, newShapeConnector: ShapeConnector)

    fun enterEditingMode()

    fun exitEditingMode(isNewStateAccepted: Boolean)

    fun addShape(shape: AbstractShape?)

    fun removeShape(shape: AbstractShape?)

    fun getShapes(point: Point): Sequence<AbstractShape>

    fun getAllShapesInZone(bound: Rect): Sequence<AbstractShape>

    fun getWindowBound(): Rect

    fun getInteractionPoint(pointPx: Point): InteractionPoint?

    fun updateInteractionBounds()

    fun isPointInInteractionBounds(point: Point): Boolean

    fun setSelectionBound(bound: Rect?)

    val selectedShapesLiveData: LiveData<Set<AbstractShape>>

    fun getSelectedShapes(): Set<AbstractShape>

    fun addSelectedShape(shape: AbstractShape?)

    fun toggleShapeSelection(shape: AbstractShape)

    fun setFocusingShape(shape: AbstractShape?, focusType: SelectedShapeManager.ShapeFocusType)

    fun getFocusingShape(): SelectedShapeManager.FocusingShape?

    fun selectAllShapes()

    fun clearSelectedShapes()

    fun getEdgeDirection(point: Point): DirectedPoint.Direction?

    fun toXPx(column: Double): Double
    fun toYPx(row: Double): Double
    fun toWidthPx(width: Double): Double
    fun toHeightPx(height: Double): Double

    class EditingMode private constructor(val isEditing: Boolean, val skippedVersion: Int?) {
        companion object {
            private val EDIT = EditingMode(true, null)
            private val IDLE = EditingMode(false, null)

            fun edit(): EditingMode = EDIT
            fun idle(skippedVersion: Int?): EditingMode =
                if (skippedVersion == null) IDLE else EditingMode(false, skippedVersion)
        }
    }
}
