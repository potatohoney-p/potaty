/*
 * Copyright (c) 2026, Potaty
 *
 * GitHub webhook authenticity (plan section 18 "GitHub mock -> source sync", section 20 trust
 * boundary). GitHub signs each delivery with HMAC-SHA256 over the RAW request body keyed by the
 * webhook secret and sends it as `X-Hub-Signature-256: sha256=<hexdigest>`. We:
 *
 *   1. recompute the HMAC with javax.crypto.Mac and compare in CONSTANT TIME (no early-exit, so
 *      an attacker cannot time-probe the digest), and
 *   2. reject replays: the same `X-GitHub-Delivery` id is accepted at most once (an in-memory,
 *      bounded seen-set; sufficient for a single backend per plan 22.4 — a shared store would be
 *      swapped in for a multi-node deployment).
 *
 * Pure JDK crypto; no new dependency.
 */

package com.potaty.backend.github

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class WebhookVerifier(
    private val secret: String,
    /** Bound on the number of remembered delivery ids; oldest are evicted FIFO. */
    private val maxSeenDeliveries: Int = 10_000
) {
    // Insertion-ordered, access-synchronized set of delivery ids already processed.
    private val seenDeliveries = LinkedHashSet<String>()
    private val lock = Any()

    /**
     * True iff [signatureHeader] (the `X-Hub-Signature-256` value, e.g. "sha256=ab12...") is a
     * valid HMAC-SHA256 of [rawBody] under the configured secret. A blank/malformed header is
     * rejected. Comparison is constant time.
     */
    fun isSignatureValid(signatureHeader: String?, rawBody: ByteArray): Boolean {
        if (signatureHeader.isNullOrBlank()) return false
        val prefix = "sha256="
        if (!signatureHeader.startsWith(prefix)) return false
        val provided = signatureHeader.substring(prefix.length).trim()
        if (provided.isEmpty()) return false
        val expected = hexHmacSha256(rawBody)
        return constantTimeEquals(expected, provided)
    }

    /**
     * Marks [deliveryId] as seen and returns true if it is the FIRST time we have seen it (i.e. the
     * delivery should be processed). A repeated id returns false (replay). A null, blank, overly
     * long, or malformed id fails closed because replay protection cannot work without a stable id.
     */
    fun registerDelivery(deliveryId: String?): Boolean {
        if (!isDeliveryIdValid(deliveryId)) return false
        synchronized(lock) {
            checkNotNull(deliveryId)
            if (seenDeliveries.contains(deliveryId)) return false
            seenDeliveries.add(deliveryId)
            if (seenDeliveries.size > maxSeenDeliveries) {
                val oldest = seenDeliveries.iterator()
                if (oldest.hasNext()) {
                    oldest.next()
                    oldest.remove()
                }
            }
            return true
        }
    }

    /** Combined check used by the route: valid signature AND not a replay. */
    fun verify(signatureHeader: String?, deliveryId: String?, rawBody: ByteArray): Boolean =
        isSignatureValid(signatureHeader, rawBody) && registerDelivery(deliveryId)

    fun isDeliveryIdValid(deliveryId: String?): Boolean =
        deliveryId != null && DELIVERY_ID.matches(deliveryId)

    /** Lowercase hex HMAC-SHA256 of [data] under the secret. Exposed for tests / known vectors. */
    fun hexHmacSha256(data: ByteArray): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), HMAC_ALGORITHM))
        return mac.doFinal(data).joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val HMAC_ALGORITHM = "HmacSHA256"
        private val DELIVERY_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")

        /**
         * Length-aware, constant-time string compare. Runs over the longer of the two lengths so a
         * length mismatch does not short-circuit; the accumulated diff is nonzero unless every byte
         * (and the length) matches.
         */
        fun constantTimeEquals(a: String, b: String): Boolean {
            val aBytes = a.toByteArray(Charsets.UTF_8)
            val bBytes = b.toByteArray(Charsets.UTF_8)
            var diff = aBytes.size xor bBytes.size
            val n = maxOf(aBytes.size, bBytes.size)
            for (i in 0 until n) {
                val ai = if (i < aBytes.size) aBytes[i].toInt() else 0
                val bi = if (i < bBytes.size) bBytes[i].toInt() else 0
                diff = diff or (ai xor bi)
            }
            return diff == 0
        }
    }
}
