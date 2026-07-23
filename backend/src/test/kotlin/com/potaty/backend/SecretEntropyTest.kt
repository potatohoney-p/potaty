/*
 * Copyright (c) 2026, Potaty
 *
 * Unit tests for the Shannon-entropy secret heuristic (plan 20.2). Deterministic, no I/O. Proves
 * that long high-entropy tokens are flagged, ordinary prose / short tokens are not, and that the
 * entropy math behaves at the known boundaries (empty, single char, uniform alphabet).
 */

package com.potaty.backend

import com.potaty.backend.security.SecretEntropy
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecretEntropyTest {

    @Test
    fun shannonEntropyKnownValues() {
        // empty -> 0
        assertEquals(0.0, SecretEntropy.shannonEntropyBits(""), 1e-9)
        // single repeated char -> 0 (no uncertainty)
        assertEquals(0.0, SecretEntropy.shannonEntropyBits("aaaaaaaa"), 1e-9)
        // two equally-likely symbols -> exactly 1 bit
        assertEquals(1.0, SecretEntropy.shannonEntropyBits("abababab"), 1e-9)
        // four equally-likely symbols -> exactly 2 bits
        assertEquals(2.0, SecretEntropy.shannonEntropyBits("abcdabcd"), 1e-9)
        // sixteen distinct hex symbols (each once) -> 4 bits
        assertTrue(abs(SecretEntropy.shannonEntropyBits("0123456789abcdef") - 4.0) < 1e-9)
    }

    @Test
    fun flagsLongHighEntropyToken() {
        // A random-looking base64url credential: long, many distinct chars, high entropy.
        val secret = "Zx9Kq2Lm7Pv4Tn1Rb8Wc5Yd0Hf3Gj6Aa"
        assertTrue(
            SecretEntropy.isProbableSecret(secret),
            "a 32-char mixed-case+digit token should look like a secret"
        )
        val findings = SecretEntropy.scan("api_secret = $secret in config")
        assertEquals(1, findings.size, "exactly one token should be flagged: $findings")
        assertEquals(secret, findings.single().token)
        // position is correctly reported (half-open [start, end))
        val f = findings.single()
        assertEquals(secret, "api_secret = $secret in config".substring(f.start, f.end))
        assertTrue(f.entropyBits >= SecretEntropy.DEFAULT_MIN_ENTROPY_BITS)
    }

    @Test
    fun doesNotFlagOrdinaryProse() {
        val prose =
            "The quick brown fox jumps over the lazy dog and then the diagram pipeline runs again"
        assertTrue(
            SecretEntropy.scan(prose).isEmpty(),
            "natural-language prose must not be flagged as a secret: ${SecretEntropy.scan(prose)}"
        )
    }

    @Test
    fun doesNotFlagShortOrLowEntropyTokens() {
        // short token, even if random-looking
        assertFalse(SecretEntropy.isProbableSecret("aB3xQ"))
        // long but low-entropy (few distinct chars / repetitive)
        assertFalse(
            SecretEntropy.isProbableSecret("aaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
            "a long repeated-character run is low entropy, not a secret"
        )
        assertFalse(
            SecretEntropy.isProbableSecret("abababababababababababab"),
            "a 2-symbol repeating pattern is low entropy, not a secret"
        )
    }

    @Test
    fun scanFindsMultipleTokensInOrder() {
        val a = "Q7wE2rT9yU4iO1pA6sD3fG8hJ5kL0zX"
        val b = "M3nB7vC2xZ9lK4jH1gF6dS8aP0oI5uY"
        val text = "first=$a; second=$b;"
        val findings = SecretEntropy.scan(text)
        assertEquals(2, findings.size, "both high-entropy tokens should be flagged: $findings")
        assertEquals(a, findings[0].token)
        assertEquals(b, findings[1].token)
        assertTrue(
            findings[0].start < findings[1].start,
            "findings are returned in appearance order"
        )
    }
}
