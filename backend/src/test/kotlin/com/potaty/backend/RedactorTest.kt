/*
 * Copyright (c) 2026, Potaty
 */

package com.potaty.backend

import com.potaty.backend.security.RedactionFinding
import com.potaty.backend.security.Redactor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RedactorTest {

    @Test
    fun detects_and_redacts_openai_key() {
        val text = "here is a key sk-abcdefghijklmnopqrstuvwx and more"
        val result = Redactor.redact(text)
        assertTrue(result.hasFindings)
        assertTrue(result.redactedText.contains("[REDACTED:openai_api_key]"))
        assertFalse(result.redactedText.contains("sk-abcdefghijklmnopqrstuvwx"))
    }

    @Test
    fun detects_email_as_pii() {
        val findings = Redactor.scan("contact me at alice@example.com please")
        assertTrue(
            findings.any { it.category == RedactionFinding.Category.PII && it.label == "email" }
        )
    }

    @Test
    fun redactsEntirePemBlockAndHighEntropyCredential() {
        val privateKeyBody = "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQ"
        val unprefixedToken = "q7Yk2Pz9Lm4Vx8Nc6Rt1Wa5Hs3Jd0UfB"
        val text = """
            -----BEGIN PRIVATE KEY-----
            $privateKeyBody
            -----END PRIVATE KEY-----
            deployment_token=$unprefixedToken
        """.trimIndent()

        val result = Redactor.redact(text, setOf(RedactionFinding.Category.SECRET))

        assertTrue(result.findings.any { it.label == "private_key_block" })
        assertTrue(result.findings.any { it.label == "high_entropy_secret" })
        assertFalse(result.redactedText.contains(privateKeyBody))
        assertFalse(result.redactedText.contains("END PRIVATE KEY"))
        assertFalse(result.redactedText.contains(unprefixedToken))
    }

    @Test
    fun redactsCompleteQuotedCredentialValueIncludingSpaces() {
        val password = "correct horse battery staple"
        val result = Redactor.redact(
            """database_password="$password"""",
            setOf(RedactionFinding.Category.SECRET)
        )

        assertFalse(result.redactedText.contains(password))
        assertFalse(result.redactedText.contains("battery staple"))
        assertTrue(result.redactedText.contains("[REDACTED:credential_assignment]"))
    }

    @Test
    fun clean_text_has_no_findings() {
        assertEquals(0, Redactor.scan("a plain architecture description with no secrets").size)
    }
}
