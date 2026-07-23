/*
 * Copyright (c) 2026, Potaty
 *
 * Shared syntax for user-supplied idempotency keys on mutation endpoints.
 */

package com.potaty.backend.api

internal const val MAX_MUTATION_IDEMPOTENCY_KEY_CHARS = 200
internal val MUTATION_IDEMPOTENCY_KEY_PATTERN =
    Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,199}$")

internal fun isValidMutationIdempotencyKey(value: String): Boolean =
    value.length <= MAX_MUTATION_IDEMPOTENCY_KEY_CHARS &&
        MUTATION_IDEMPOTENCY_KEY_PATTERN.matches(value)

internal const val MUTATION_IDEMPOTENCY_KEY_MESSAGE =
    "Idempotency-Key must use 1-200 letters, digits, period, underscore, colon, or hyphen"
