/*
 * Copyright (c) 2026, Potaty
 */

package com.potaty.backend

import com.potaty.backend.source.SourceSafetyGateway
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SourceSafetyGatewayTest {

    @Test
    fun normalizesAndRedactsBeforeReturningPersistableContent() {
        val secret = "sk-" + "Z".repeat(40)
        val raw = "User -> API: call\r\napi_key=$secret   \r\nowner@example.com\r\n"

        val safe = SourceSafetyGateway.process(raw)

        assertFalse(safe.canonicalText.contains(secret))
        assertTrue(safe.canonicalText.contains("[REDACTED:"))
        assertEquals(1, safe.secretFindings.size)
        assertEquals(1, safe.piiFindings.size)
        assertFalse(safe.canonicalText.contains("\r"))
        assertTrue(safe.contentHash.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun stripsDatabaseAndTerminalControlCharactersButPreservesTabsAndLines() {
        val safe = SourceSafetyGateway.process("API\u0000 -> DB\u001B[31m\u202E\n\tkept")

        assertFalse(safe.canonicalText.contains('\u0000'))
        assertFalse(safe.canonicalText.contains('\u001B'))
        assertFalse(safe.canonicalText.contains('\u202E'))
        assertTrue(safe.canonicalText.contains('\n'))
        assertTrue(safe.canonicalText.contains('\t'))
    }
}
