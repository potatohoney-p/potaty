/*
 * Copyright (c) 2026, Potaty
 *
 * PostgreSQL JSONB normalization parity for durable idempotency comparisons.
 */

package com.potaty.backend.persistence

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JsonColumnsTest {

    @Test
    fun jsonDocumentsCompareByMeaningInsteadOfSerializedLayout() {
        assertTrue(
            jsonDocumentsEqual(
                """{"projectId":"p","nested":{"count":1,"ready":true}}""",
                """{ "nested": { "ready": true, "count": 1 }, "projectId": "p" }"""
            )
        )
        assertFalse(jsonDocumentsEqual("""{"count":1}""", """{"count":2}"""))
        assertTrue(jsonDocumentsEqual("""{"count":1}""", """{"count":1.0}"""))
        assertTrue(
            jsonDocumentsEqual(
                """{"rate":1.230e-5}""",
                """{"rate":0.00001230}"""
            )
        )
        assertFalse(jsonDocumentsEqual("""{"count":1}""", """{"count":"1"}"""))
        assertFalse(jsonDocumentsEqual("""[1,2]""", """[2,1]"""))
        assertFalse(jsonDocumentsEqual("""{"count":1}""", "not-json"))
        assertTrue(jsonDocumentsEqual(null, null))
        assertFalse(jsonDocumentsEqual(null, "{}"))
    }
}
