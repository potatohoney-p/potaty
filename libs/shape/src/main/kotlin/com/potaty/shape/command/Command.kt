/*
 * Copyright (c) 2023, tuanchauict
 * Copyright (c) 2026, Potaty
 */

package com.potaty.shape.command

import com.potaty.shape.ShapeManager
import com.potaty.shape.shape.Group

/**
 * A sealed class which defines common apis for a command. A command must determine direct affected
 * parent group via [getDirectAffectedParent]. If [getDirectAffectedParent] returns null, the
 * command won't be executed.
 */
sealed class Command {
    internal abstract fun getDirectAffectedParent(shapeManager: ShapeManager): Group?

    internal abstract fun execute(shapeManager: ShapeManager, parent: Group)
}
