/*
 * Copyright (c) 2026, Potaty
 *
 * StructuredCaller wraps generateStructured with a bounded repair-on-invalid-JSON loop
 * (plan 15.2 / 15.6). When the provider returns malformed or schema-invalid JSON, it
 * re-prompts the model with the parse/validation error appended, up to maxRepairAttempts.
 */

package com.potaty.backend.llm.provider

import kotlinx.serialization.json.JsonObject

class StructuredCaller(
    private val provider: LlmProvider,
    private val maxRepairAttempts: Int = 2
) {
    /**
     * Calls the provider for structured JSON, retrying on invalid output by appending the error as
     * an additional TASK_INSTRUCTIONS part. [validate] returns null on success or an error message
     * describing why the JSON is unacceptable (schema violation, etc.).
     */
    suspend fun call(
        input: StructuredGenerationInput,
        validate: (JsonObject) -> String? = { null }
    ): LlmResult<JsonObject> {
        var attemptInput = input
        var lastError: LlmError? = null
        var totalUsage = TokenUsage()

        for (attempt in 0..maxRepairAttempts) {
            when (val result = provider.generateStructured(attemptInput)) {
                is LlmResult.Success -> {
                    totalUsage += result.usage
                    val problem = validate(result.value)
                    if (problem == null) return LlmResult.Success(result.value, totalUsage)
                    // Schema-invalid: build a repair prompt and try again.
                    lastError = LlmError(LlmErrorKind.INVALID_OUTPUT, problem)
                    attemptInput =
                        attemptInput.copy(
                            parts =
                            attemptInput.parts +
                                PromptPart(
                                    role = PromptPartRole.TASK_INSTRUCTIONS,
                                    text = REPAIR_PREFIX + problem
                                )
                        )
                }
                is LlmResult.Failure -> {
                    totalUsage += result.usage
                    lastError = result.error
                    // Non-retryable provider errors should not loop.
                    if (
                        !result.error.retryable && result.error.kind != LlmErrorKind.INVALID_OUTPUT
                    ) {
                        return LlmResult.Failure(result.error, totalUsage)
                    }
                }
            }
        }
        return LlmResult.Failure(
            lastError
                ?: LlmError(
                    LlmErrorKind.INVALID_OUTPUT,
                    "structured generation failed after repairs"
                ),
            totalUsage
        )
    }

    companion object {
        private const val REPAIR_PREFIX =
            "Your previous response was not valid against the required JSON schema. " +
                "Return ONLY corrected JSON. Problem: "
    }
}
