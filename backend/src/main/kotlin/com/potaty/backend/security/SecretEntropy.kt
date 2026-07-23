/*
 * Copyright (c) 2026, Potaty
 *
 * Entropy-based secret detection (plan section 20.2). Layered on top of the provider-specific
 * regex rules in [Redactor]: catches credentials that don't match a known token prefix (random
 * API keys, base64 blobs, hex digests) by their statistical signature — long, high-entropy,
 * non-dictionary tokens.
 *
 * Pure, dependency-free, and deterministic so it can be unit-tested and optionally consulted by
 * [Redactor]. It is intentionally CONSERVATIVE: short tokens, low-entropy tokens, and ordinary
 * prose words never trip it, so wiring it into redaction does not over-redact natural language.
 */

package com.potaty.backend.security

import kotlin.math.ln

/**
 * A token flagged by [SecretEntropy] as a probable secret, with its position in the scanned text.
 * [start] is inclusive, [end] is exclusive (matching [RedactionFinding]'s half-open convention).
 */
data class EntropyFinding(
    val token: String,
    val entropyBits: Double,
    val start: Int,
    val end: Int
)

object SecretEntropy {

    /**
     * Minimum token length to even consider. Real credentials are long; short alphanumerics
     * ("HTTP", "v2", "abc123") would otherwise generate noise.
     */
    const val DEFAULT_MIN_LENGTH: Int = 20

    /**
     * Minimum Shannon entropy (bits per character) for a token to be treated as a probable
     * secret. English prose sits well below this; random base64/hex credentials sit above it.
     */
    const val DEFAULT_MIN_ENTROPY_BITS: Double = 3.5

    /**
     * Characters that may appear inside a "token". Whitespace and most punctuation are boundaries,
     * so URLs / words split into pieces. The set is the common secret alphabet: base64url + a few
     * separators that frequently sit inside keys (':', '.', '/', '=', '_', '-', '+').
     */
    private fun isTokenChar(c: Char): Boolean =
        c.isLetterOrDigit() || c == '_' || c == '-' || c == '+' || c == '/' ||
            c == '.' || c == ':'
    // '=' is a BOUNDARY (not a token char) so config-style `key=secret` splits the key from the
    // value; base64 trailing-'=' padding is immaterial to the entropy decision.

    /**
     * Shannon entropy in bits per character of [s]. Empty strings have 0 entropy. A string with
     * a single repeated character is 0; a string of N distinct, equally-likely characters is
     * log2(N).
     */
    fun shannonEntropyBits(s: String): Double {
        if (s.isEmpty()) return 0.0
        val counts = HashMap<Char, Int>(s.length)
        for (c in s) counts[c] = (counts[c] ?: 0) + 1
        val len = s.length.toDouble()
        var bits = 0.0
        for (count in counts.values) {
            val p = count / len
            // entropy in bits: -sum(p * log2(p)); log2(x) = ln(x) / ln(2)
            bits -= p * (ln(p) / LN2)
        }
        return bits
    }

    /**
     * True if [token] looks like a probable secret: long enough and high-entropy enough. Pure
     * predicate so callers (tests, [Redactor]) can reuse the exact decision.
     */
    fun isProbableSecret(
        token: String,
        minLength: Int = DEFAULT_MIN_LENGTH,
        minEntropyBits: Double = DEFAULT_MIN_ENTROPY_BITS
    ): Boolean {
        if (token.length < minLength) return false
        // A token that is mostly one repeated character is low-entropy regardless of length;
        // the entropy check below already rejects it, but guarding the distinct-char count keeps
        // pathological inputs (e.g. "aaaa...") cheap and unambiguous.
        if (token.toSet().size < 8) return false
        return shannonEntropyBits(token) >= minEntropyBits
    }

    /**
     * Scans [text] for probable-secret tokens. Tokens are maximal runs of [isTokenChar]; each is
     * tested with [isProbableSecret]. Findings are returned in order of appearance and never
     * overlap (tokenisation is non-overlapping by construction).
     */
    fun scan(
        text: String,
        minLength: Int = DEFAULT_MIN_LENGTH,
        minEntropyBits: Double = DEFAULT_MIN_ENTROPY_BITS
    ): List<EntropyFinding> {
        val findings = ArrayList<EntropyFinding>()
        var i = 0
        val n = text.length
        while (i < n) {
            if (!isTokenChar(text[i])) {
                i++
                continue
            }
            val start = i
            while (i < n && isTokenChar(text[i])) i++
            val token = text.substring(start, i)
            if (isProbableSecret(token, minLength, minEntropyBits)) {
                findings += EntropyFinding(
                    token = token,
                    entropyBits = shannonEntropyBits(token),
                    start = start,
                    end = i
                )
            }
        }
        return findings
    }

    private val LN2 = ln(2.0)
}
