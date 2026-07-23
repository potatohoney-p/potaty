/*
 * Copyright (c) 2026, Potaty
 *
 * Secret + PII redaction (plan sections 20.2, 20.3). Runs before content is stored, before
 * it is sent to an LLM, and before export. Layered regex detection with provider-specific
 * token patterns plus a conservative entropy layer for secret-like assignment contexts.
 *
 * Patterns use RegexOption.IGNORE_CASE rather than inline (?i) flags to stay portable
 * (the IR/shared modules are Kotlin/JS where inline flags are unsupported; keeping the same
 * habit here avoids surprises if any of this is later shared).
 */

package com.potaty.backend.security

data class RedactionFinding(
    val category: Category,
    val label: String,
    val start: Int,
    val end: Int
) {
    enum class Category {
        SECRET,
        PII
    }
}

data class RedactionResult(
    val redactedText: String,
    val findings: List<RedactionFinding>
) {
    val hasFindings: Boolean
        get() = findings.isNotEmpty()
}

object Redactor {

    private data class Rule(
        val label: String,
        val category: RedactionFinding.Category,
        val regex: Regex
    )

    private val rules: List<Rule> =
        listOf(
            // --- secrets ---
            rule("openai_api_key", RedactionFinding.Category.SECRET, """sk-[A-Za-z0-9]{20,}"""),
            rule(
                "anthropic_api_key",
                RedactionFinding.Category.SECRET,
                """sk-ant-[A-Za-z0-9_-]{20,}"""
            ),
            rule(
                "github_token",
                RedactionFinding.Category.SECRET,
                """gh[pousr]_[A-Za-z0-9]{20,}"""
            ),
            rule("aws_access_key", RedactionFinding.Category.SECRET, """AKIA[0-9A-Z]{16}"""),
            rule("google_api_key", RedactionFinding.Category.SECRET, """AIza[0-9A-Za-z_-]{35}"""),
            rule(
                "slack_token",
                RedactionFinding.Category.SECRET,
                """xox[baprs]-[0-9A-Za-z-]{10,}"""
            ),
            // Match and remove the complete PEM payload, not merely its BEGIN marker.
            rule(
                "private_key_block",
                RedactionFinding.Category.SECRET,
                """-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----""" +
                    """[\s\S]*?-----END [A-Z0-9 ]*PRIVATE KEY-----"""
            ),
            rule(
                "jwt",
                RedactionFinding.Category.SECRET,
                """eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}"""
            ),
            rule(
                "db_url",
                RedactionFinding.Category.SECRET,
                """(?:postgres|postgresql|mysql|mongodb)(?:\+\w+)?://[^\s'"]+:[^\s'"]+@[^\s'"]+"""
            ),
            rule(
                "credential_assignment",
                RedactionFinding.Category.SECRET,
                """\b[A-Za-z0-9_.-]*(?:password|passwd|pwd|api[_-]?key|token|""" +
                    """secret|credential)\b\s*[:=]\s*(?:"[^"\r\n]{1,512}"|'[^'\r\n]{1,512}')"""
            ),
            rule(
                "credential_assignment",
                RedactionFinding.Category.SECRET,
                """\b[A-Za-z0-9_.-]*(?:password|passwd|pwd|api[_-]?key|token|""" +
                    """secret|credential)\b\s*[:=]\s*[^\s"'#,;]{4,}"""
            ),
            // --- PII ---
            rule(
                "email",
                RedactionFinding.Category.PII,
                """[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}"""
            ),
            rule("phone", RedactionFinding.Category.PII, """\+?\d[\d\s().-]{7,}\d"""),
            rule("credit_card", RedactionFinding.Category.PII, """\b(?:\d[ -]*?){13,16}\b""")
        )

    private fun rule(label: String, category: RedactionFinding.Category, pattern: String) =
        Rule(label, category, Regex(pattern, RegexOption.IGNORE_CASE))

    private val secretAssignmentContext =
        Regex(
            """(?:password|passwd|pwd|api[_-]?key|token|secret|credential)\b\s*[:=]\s*["']?\s*$""",
            RegexOption.IGNORE_CASE
        )

    /**
     * Scans without modifying. Entropy detection is deliberately limited to values immediately
     * following a secret-like assignment key; this catches unprefixed random credentials without
     * treating ordinary source identifiers, hashes, or prose as secrets.
     */
    fun scan(text: String): List<RedactionFinding> {
        val candidates =
            rules
                .flatMap { rule ->
                    rule.regex.findAll(text).map { match ->
                        RedactionFinding(
                            rule.category,
                            rule.label,
                            match.range.first,
                            match.range.last + 1
                        )
                    }
                }
                .toMutableList()

        SecretEntropy.scan(text).forEach { finding ->
            val contextStart = (finding.start - 96).coerceAtLeast(0)
            val context = text.substring(contextStart, finding.start)
            if (secretAssignmentContext.containsMatchIn(context)) {
                candidates +=
                    RedactionFinding(
                        category = RedactionFinding.Category.SECRET,
                        label = "high_entropy_secret",
                        start = finding.start,
                        end = finding.end
                    )
            }
        }

        // A provider token may also sit inside a generic credential assignment. Replace the
        // widest match once so placeholders never overlap or leak a suffix.
        val entropyCandidates = candidates.filter { it.label == "high_entropy_secret" }
        val preferredCandidates = candidates.filterNot { candidate ->
            candidate.label == "credential_assignment" &&
                entropyCandidates.any { it.start >= candidate.start && it.end <= candidate.end }
        }
        return preferredCandidates
            .sortedWith(
                compareBy<RedactionFinding> { it.start }.thenByDescending { it.end - it.start }
            )
            .fold(mutableListOf()) { accepted, candidate ->
                val overlaps = accepted.any { candidate.start < it.end && candidate.end > it.start }
                if (!overlaps) accepted += candidate
                accepted
            }
    }

    /**
     * Replaces detected items in [categories] with a category placeholder. Defaults to both
     * categories; the ingestion SafetyPreScan passes only SECRET so credentials are stripped from
     * stored content while PII is preserved-but-reported (workspace policy may tighten this).
     */
    fun redact(
        text: String,
        categories: Set<RedactionFinding.Category> =
            setOf(
                RedactionFinding.Category.SECRET,
                RedactionFinding.Category.PII
            )
    ): RedactionResult {
        val findings = scan(text).filter { it.category in categories }
        var result = text
        for (finding in findings.asReversed()) {
            result =
                result.replaceRange(
                    finding.start,
                    finding.end,
                    "[REDACTED:${finding.label}]"
                )
        }
        return RedactionResult(result, findings)
    }
}
