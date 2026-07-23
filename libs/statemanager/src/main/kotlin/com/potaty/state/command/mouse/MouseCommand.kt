/*
 * Copyright (c) 2023, tuanchauict
 * Copyright (c) 2026, Potaty
 */

package com.potaty.state.command.mouse

import com.potaty.common.MouseCursor
import com.potaty.graphics.geo.MousePointer
import com.potaty.state.MainStateManager
import com.potaty.state.command.CommandEnvironment

/**
 * A strategy interface for mouse interaction command happens on [MainStateManager]
 */
internal sealed interface MouseCommand {
    /**
     * CSS mouse cursor value to show during command execution.
     */
    val mouseCursor: MouseCursor?

    /**
     * Handles mouse events.
     * Returns true when the action finishes.
     */
    fun execute(environment: CommandEnvironment, mousePointer: MousePointer): CommandResultType

    enum class CommandResultType {
        WORKING,
        WORKING_PHASE2,
        DONE,
        UNKNOWN
    }
}
