/*
 * Copyright (c) 2023, tuanchauict
 * Copyright (c) 2026, Potaty
 */

package com.potaty.common

import kotlinx.browser.window
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.MouseEvent

val KeyboardEvent.commandKey: Boolean
    get() = if (window.isCommandKeySupported()) metaKey else ctrlKey

val MouseEvent.commandKey: Boolean
    get() = if (window.isCommandKeySupported()) metaKey else ctrlKey
