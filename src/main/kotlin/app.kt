/*
 * Copyright (c) 2023, tuanchauict
 * Copyright (c) 2026, Potaty
 */

@file:Suppress("ktlint:filename")

import com.potaty.app.PotatyApplication
import kotlinx.browser.window

fun main() {
    val application = PotatyApplication()
    window.onload = {
        application.onStart()
    }
    window.onresize = {
        application.onResize()
    }
}
