/*
 * Copyright (c) 2026, Potaty
 *
 * WebhookVerifier tests. Pin the HMAC against a published HMAC-SHA256 test vector (key="key",
 * message="The quick brown fox jumps over the lazy dog"), then exercise the X-Hub-Signature-256
 * header parsing, the constant-time compare, and the delivery-id replay rejection. No network.
 */

package com.potaty.backend

import com.potaty.backend.github.WebhookVerifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebhookVerifierTest {

    // RFC-published HMAC-SHA256(key="key", "The quick brown fox jumps over the lazy dog")
    private val knownKey = "key"
    private val knownMessage = "The quick brown fox jumps over the lazy dog"
    private val knownHmac = "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8"

    @Test
    fun computesKnownHmacVector() {
        val verifier = WebhookVerifier(knownKey)
        assertEquals(knownHmac, verifier.hexHmacSha256(knownMessage.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun acceptsValidSignatureHeader() {
        val verifier = WebhookVerifier(knownKey)
        val header = "sha256=$knownHmac"
        assertTrue(verifier.isSignatureValid(header, knownMessage.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun rejectsTamperedBody() {
        val verifier = WebhookVerifier(knownKey)
        val header = "sha256=$knownHmac"
        assertFalse(verifier.isSignatureValid(header, "tampered".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun rejectsWrongSecret() {
        val verifier = WebhookVerifier("not-the-key")
        val header = "sha256=$knownHmac"
        assertFalse(verifier.isSignatureValid(header, knownMessage.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun rejectsMissingOrMalformedHeader() {
        val verifier = WebhookVerifier(knownKey)
        val body = knownMessage.toByteArray(Charsets.UTF_8)
        assertFalse(verifier.isSignatureValid(null, body))
        assertFalse(verifier.isSignatureValid("", body))
        assertFalse(verifier.isSignatureValid(knownHmac, body)) // missing "sha256=" prefix
        assertFalse(verifier.isSignatureValid("sha256=", body)) // empty digest
        assertFalse(verifier.isSignatureValid("sha1=$knownHmac", body))
    }

    @Test
    fun constantTimeEqualsMatchesOnlyIdenticalStrings() {
        assertTrue(WebhookVerifier.constantTimeEquals("abc123", "abc123"))
        assertFalse(WebhookVerifier.constantTimeEquals("abc123", "abc124"))
        assertFalse(WebhookVerifier.constantTimeEquals("abc", "abc123")) // length mismatch
    }

    @Test
    fun registerDeliveryRejectsReplay() {
        val verifier = WebhookVerifier(knownKey)
        assertFalse(verifier.registerDelivery(null), "missing delivery id must fail closed")
        assertFalse(verifier.registerDelivery(""), "blank delivery id must fail closed")
        assertFalse(verifier.registerDelivery("contains a space"), "malformed id must fail closed")
        assertFalse(verifier.registerDelivery("x".repeat(129)), "oversized id must fail closed")
        assertTrue(verifier.registerDelivery("delivery-1"), "first delivery is accepted")
        assertFalse(verifier.registerDelivery("delivery-1"), "replayed delivery is rejected")
        assertTrue(verifier.registerDelivery("delivery-2"), "a different delivery is accepted")
    }

    @Test
    fun verifyCombinesSignatureAndReplayChecks() {
        val verifier = WebhookVerifier(knownKey)
        val body = knownMessage.toByteArray(Charsets.UTF_8)
        val header = "sha256=$knownHmac"
        assertTrue(verifier.verify(header, "d-1", body), "valid + first delivery passes")
        assertFalse(
            verifier.verify(header, "d-1", body),
            "replay fails even with a valid signature"
        )
        assertFalse(verifier.verify("sha256=deadbeef", "d-2", body), "bad signature fails")
    }

    @Test
    fun replaySetIsBounded() {
        val verifier = WebhookVerifier(knownKey, maxSeenDeliveries = 2)
        assertTrue(verifier.registerDelivery("a"))
        assertTrue(verifier.registerDelivery("b"))
        assertTrue(verifier.registerDelivery("c")) // evicts "a"
        // "a" was evicted, so it is accepted again; "b"/"c" are still remembered.
        assertTrue(verifier.registerDelivery("a"))
        assertFalse(verifier.registerDelivery("c"))
    }
}
