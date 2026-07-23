/*
 * Copyright (c) 2026, Potaty
 *
 * Result wrapper for provider calls. Errors are classified so the job layer can decide
 * retry vs fatal (plan 11.4) without provider-specific knowledge.
 */

package com.potaty.backend.llm.provider

sealed interface LlmResult<out T> {
    data class Success<T>(val value: T, val usage: TokenUsage = TokenUsage()) : LlmResult<T>

    /**
     * [usage] is non-zero when a provider accepted and billed a request but the returned payload
     * could not be used (for example, all structured-output repair attempts were exhausted).
     */
    data class Failure(
        val error: LlmError,
        val usage: TokenUsage = TokenUsage()
    ) : LlmResult<Nothing>
}

data class LlmError(
    val kind: LlmErrorKind,
    val message: String,
    val httpStatus: Int? = null,
    val retryable: Boolean = kind.retryableByDefault
)

enum class LlmErrorKind(val retryableByDefault: Boolean) {
    RATE_LIMITED(true),
    SERVER_ERROR(true),
    TIMEOUT(true),
    NETWORK(true),
    INVALID_REQUEST(false),
    AUTHENTICATION(false),
    QUOTA_EXCEEDED(false),
    INVALID_OUTPUT(false),
    UNSUPPORTED(false)
}

/** Maps provider HTTP status to an error kind (plan: ProviderErrorMapper). */
object ProviderErrorMapper {
    fun fromHttpStatus(status: Int, message: String): LlmError = when (status) {
        429 -> LlmError(LlmErrorKind.RATE_LIMITED, message, status)
        in 500..599 -> LlmError(LlmErrorKind.SERVER_ERROR, message, status)
        401, 403 -> LlmError(LlmErrorKind.AUTHENTICATION, message, status)
        400, 422 -> LlmError(LlmErrorKind.INVALID_REQUEST, message, status)
        else -> LlmError(LlmErrorKind.INVALID_REQUEST, message, status)
    }
}
