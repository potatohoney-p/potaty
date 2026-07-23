/*
 * Copyright (c) 2026, Potaty
 */

package com.potaty.codegen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Escaping rules per output format. The security-relevant guarantees are that no escaper lets a
 * double-quote, angle bracket, or newline survive in a form that could break out of its string
 * context and inject new diagram statements.
 */
class LabelEscaperTest {

    @Test
    fun mermaidNeutralisesQuotesAnglesAndNewlines() {
        val out = LabelEscaper.mermaid("a \"b\" <c>\nd")
        assertFalse(out.contains('"'), "raw double-quote removed: $out")
        assertFalse(out.contains('<'), "raw '<' removed: $out")
        assertFalse(out.contains('>'), "raw '>' removed: $out")
        assertFalse(out.contains('\n'), "newline flattened: $out")
        assertTrue(out.contains("#34;"), "quote encoded as numeric mermaid entity: $out")
    }

    @Test
    fun d2EscapesQuotesAndBackslashes() {
        assertEquals("a \\\"b\\\"", LabelEscaper.d2("a \"b\""))
        assertEquals("c\\\\d", LabelEscaper.d2("c\\d"))
    }

    @Test
    fun dotEscapesQuotesAndFlattensNewlines() {
        val out = LabelEscaper.dot("line1\nline2 \"q\"")
        assertFalse(out.contains('\n'), "newline flattened: $out")
        assertTrue(out.contains("\\\""), "quote escaped: $out")
    }

    @Test
    fun plantUmlStripsAnglesAndEscapesQuotes() {
        val out = LabelEscaper.plantUml("<b>\"x\"")
        assertFalse(out.contains('<'), out)
        assertFalse(out.contains('>'), out)
        assertTrue(out.contains("\\\""), out)
    }

    @Test
    fun sanitizeIdentProducesSafeBase() {
        val idRegex = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
        // The base sanitiser is deliberately many-to-one; it only guarantees a *syntactically* safe
        // identifier. (Uniqueness is IdentAllocator's job — see below.)
        assertEquals("keep_it", LabelEscaper.sanitizeIdent("keep_it"))
        assertEquals("n_", LabelEscaper.sanitizeIdent(""), "empty id is non-empty output")
        assertEquals("a_b_c", LabelEscaper.sanitizeIdent("a.b-c"))
        assertEquals("n_1foo", LabelEscaper.sanitizeIdent("1foo"), "leading digit gets a prefix")
        assertTrue(idRegex.matches(LabelEscaper.sanitizeIdent("@weird/.id")))
    }

    @Test
    fun identAllocatorIsInjectiveForCollidingIds() {
        val ids = IdentAllocator()
        // The classic collision: "svc.a" and "svc_a" sanitise to the same base "svc_a" but MUST get
        // distinct identifiers from the allocator.
        val a = ids.identify("svc.a")
        val b = ids.identify("svc_a")
        assertEquals("svc_a", a, "first claimant keeps the clean base")
        assertTrue(a != b, "distinct ids must not collide: $a vs $b")
        assertTrue(b.startsWith("svc_a_"), "collision disambiguated with a numeric suffix: $b")
    }

    @Test
    fun identAllocatorIsConsistentForSameId() {
        val ids = IdentAllocator()
        val first = ids.identify("svc.a")
        ids.identify("svc_a") // allocate a colliding id in between
        assertEquals(
            first,
            ids.identify("svc.a"),
            "same raw id always resolves to the same identifier"
        )
    }

    @Test
    fun identAllocatorResistsAdversarialCollision() {
        val ids = IdentAllocator()
        // An attacker supplies an id equal to the *disambiguated* form another id will receive.
        val a = ids.identify("x.y") // -> "x_y"
        val b = ids.identify("x_y") // -> "x_y_2"
        val c = ids.identify("x_y_2") // must NOT collide with b
        assertEquals(
            3,
            setOf(a, b, c).size,
            "all three distinct ids get distinct identifiers: $a / $b / $c"
        )
    }

    @Test
    fun identAllocatorLeavesCleanIdsUntouched() {
        val ids = IdentAllocator()
        // Common case: valid, non-colliding ids pass through unchanged (no suffix noise).
        assertEquals("web", ids.identify("web"))
        assertEquals("api_server", ids.identify("api_server"))
    }
}
