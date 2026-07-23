/*
 * Copyright (c) 2026, Potaty
 *
 * Retry/backoff policy (plan 11.4). Classifies failures and computes backoff with jitter.
 */

package com.potaty.backend.jobs

import kotlin.random.Random

object RetryPolicy {

    /** Backoff schedule per attempt (1-based): immediate, 15s, 60s, 5m, then capped. */
    private val baseDelaysSeconds = listOf(0L, 15L, 60L, 300L)

    /** Returns the backoff in seconds for [attempt] (1-based), with +/-20% jitter. */
    fun backoffSeconds(attempt: Int): Long {
        val idx = (attempt - 1).coerceIn(0, baseDelaysSeconds.lastIndex)
        val base = baseDelaysSeconds[idx]
        if (base == 0L) return 0L
        val jitter = (base * 0.2).toLong()
        return base + Random.nextLong(-jitter, jitter + 1)
    }

    fun hasAttemptsLeft(attempt: Int, maxAttempts: Int): Boolean = attempt < maxAttempts
}

/**
 * Classifies pipeline/provider failures. Retryable failures get backoff; fatal ones fail the job
 * immediately (plan 11.4).
 */
enum class FailureClass {
    RETRYABLE,
    FATAL
}

object FailureClassifier {

    private val retryableSignals = listOf("429", "5xx", "timeout", "serialization", "temporar")

    /** Best-effort classification by message. Provider error mappers should set this explicitly. */
    fun classify(reason: String): FailureClass {
        val lower = reason.lowercase()
        return if (
            retryableSignals.any {
                lower.contains(it)
            }
        ) {
            FailureClass.RETRYABLE
        } else FailureClass.FATAL
    }
}
