/*
 * Copyright (c) 2023, tuanchauict
 * Copyright (c) 2026, Potaty
 */

package com.potaty.shape.command

import com.potaty.graphics.geo.Rect
import com.potaty.shape.ShapeManager
import com.potaty.shape.extra.ShapeExtra
import com.potaty.shape.shape.AbstractShape
import com.potaty.shape.shape.Group

class ChangeBound(private val target: AbstractShape, private val newBound: Rect) : Command() {
    override fun getDirectAffectedParent(shapeManager: ShapeManager): Group? =
        shapeManager.getGroup(target.parentId)

    override fun execute(shapeManager: ShapeManager, parent: Group) {
        val currentVersion = target.versionCode
        target.setBound(newBound)
        if (currentVersion == target.versionCode) {
            return
        }

        parent.update { true }
    }
}

class ChangeExtra(
    private val target: AbstractShape,
    private val newExtra: ShapeExtra
) : Command() {
    override fun getDirectAffectedParent(shapeManager: ShapeManager): Group? =
        shapeManager.getGroup(target.parentId)

    override fun execute(shapeManager: ShapeManager, parent: Group) {
        val currentVersion = target.versionCode
        target.setExtra(newExtra)
        if (currentVersion != target.versionCode) {
            parent.update { true }
        }
    }
}
