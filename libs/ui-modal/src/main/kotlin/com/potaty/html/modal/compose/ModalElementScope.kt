/*
 * Copyright (c) 2023, tuanchauict
 * Copyright (c) 2026, Potaty
 */

package com.potaty.html.modal.compose

import org.jetbrains.compose.web.dom.ElementScope
import org.w3c.dom.HTMLDivElement

/**
 * A [ElementScope] for modal, which has extra APIs for communicating with content of the modal.
 */
class ModalElementScope(
    private val elementScope: ElementScope<HTMLDivElement>,
    val dismiss: () -> Unit
) : ElementScope<HTMLDivElement> by elementScope
