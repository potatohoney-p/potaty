/*
 * Copyright (c) 2026, Potaty
 *
 * The single boundary for all source content that may be persisted or sent to a provider.
 */

package com.potaty.backend.source

import com.potaty.backend.security.RedactionFinding
import com.potaty.backend.security.Redactor

data class SafeSourceContent(
    val canonicalText: String,
    val contentHash: String,
    val lineCount: Int,
    val secretFindings: List<RedactionFinding>,
    val piiFindings: List<RedactionFinding>
)

object SourceSafetyGateway {

    /**
     * Normalizes [raw], removes secrets, then normalizes once more so both the persisted text and
     * its hash describe the exact safe content downstream consumers receive.
     */
    fun process(raw: String): SafeSourceContent {
        val canonical = SourceNormalizer.normalize(raw)
        val redaction = Redactor.redact(
            canonical.canonicalText,
            setOf(RedactionFinding.Category.SECRET)
        )
        val safe = SourceNormalizer.normalize(redaction.redactedText)
        val pii = Redactor.scan(safe.canonicalText)
            .filter { it.category == RedactionFinding.Category.PII }

        return SafeSourceContent(
            canonicalText = safe.canonicalText,
            contentHash = safe.contentHash,
            lineCount = safe.lineCount,
            secretFindings = redaction.findings,
            piiFindings = pii
        )
    }
}
