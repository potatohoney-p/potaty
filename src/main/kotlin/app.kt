/*
 * Copyright (c) 2023, tuanchauict
 */

@file:Suppress("ktlint:filename")

import kotlinx.browser.window
import mono.app.PotatyApplication

fun main() {
    val application = PotatyApplication()
    window.onload = {
        application.onStart()
    }
    window.onresize = {
        application.onResize()
    }
}
