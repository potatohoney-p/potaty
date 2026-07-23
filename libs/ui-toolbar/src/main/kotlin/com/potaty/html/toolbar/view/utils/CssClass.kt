/*
 * Copyright (c) 2023, tuanchauict
 * Copyright (c) 2026, Potaty
 */

package com.potaty.html.toolbar.view.utils

import com.potaty.html.bindClass
import kotlinx.dom.hasClass
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement

internal enum class CssClass(val value: String) {
    DISABLED("disabled"),
    HIDE("hidden"),
    SELECTED("selected")
}

internal fun HTMLElement.hasClass(cls: CssClass) = hasClass(cls.value)

internal fun Element.bindClass(cssClass: CssClass, isApplicable: Boolean) =
    bindClass(cssClass.value, isApplicable)
