/*
 * Copyright (c) 2023, tuanchauict
 * Copyright (c) 2026, Potaty
 */

package com.potaty.shapebound

/**
 * A sealed class to define all possible interaction bound types.
 */
sealed class InteractionBound {
    abstract val interactionPoints: List<InteractionPoint>
}
