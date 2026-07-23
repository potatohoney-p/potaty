/*
 * Copyright (c) 2023, tuanchauict
 * Copyright (c) 2026, Potaty
 */

package com.potaty.actionmanager

import com.potaty.common.MouseCursor

/**
 * An enum class which defines all action types which repeatedly have effects after triggered.
 */
enum class RetainableActionType(val mouseCursor: MouseCursor) {
    IDLE(MouseCursor.DEFAULT),
    ADD_RECTANGLE(MouseCursor.CROSSHAIR),
    ADD_TEXT(MouseCursor.TEXT),
    ADD_LINE(MouseCursor.CROSSHAIR)
}
