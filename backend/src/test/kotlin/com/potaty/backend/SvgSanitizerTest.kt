/*
 * Copyright (c) 2026, Potaty
 *
 * Verifies the SVG sanitizer removes the active-content vectors (script/style/event handlers and
 * javascript:/data: hrefs) so rendered SVG cannot execute (plan 17.2).
 */

package com.potaty.backend

import com.potaty.backend.security.SvgSanitizer
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SvgSanitizerTest {

    @Test
    fun stripsActiveContent() {
        val dirty = """
            <svg xmlns="http://www.w3.org/2000/svg">
              <style>rect{fill:expression(alert(1))}</style>
              <script>alert(1)</script>
              <rect width="10" height="10" onclick="steal()"/>
              <a href="javascript:alert(1)">x</a>
              <image href="data:text/html,<script>x</script>"/>
              <text>safe label</text>
            </svg>
        """.trimIndent()

        val result = SvgSanitizer.sanitize(dirty)

        assertFalse(
            result.svg.contains("<script", ignoreCase = true),
            "script removed: ${result.svg}"
        )
        assertFalse(
            result.svg.contains("<style", ignoreCase = true),
            "style removed: ${result.svg}"
        )
        assertFalse(
            result.svg.contains("onclick", ignoreCase = true),
            "event handler removed: ${result.svg}"
        )
        assertFalse(
            result.svg.contains("javascript:", ignoreCase = true),
            "js href removed: ${result.svg}"
        )
        assertFalse(
            result.svg.contains("data:text/html", ignoreCase = true),
            "data:text/html href removed"
        )
        assertTrue(result.removedCount >= 4, "removed several vectors, got ${result.removedCount}")
        assertTrue(result.svg.contains("safe label"), "benign content preserved")
    }
}
