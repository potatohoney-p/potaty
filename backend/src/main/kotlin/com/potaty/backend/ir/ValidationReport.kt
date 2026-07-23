/*
 * Copyright (c) 2026, Potaty
 *
 * JVM-side validation report DTO. Mirrors the IrValidator output of shared/diagram-ir
 * (rules IR-R001..R017). The backend does not re-run the JS validator; it persists/serves
 * the report produced by the pipeline's IrValidator stage. A failed report surfaces as
 * HTTP 422 via StatusPages (see Application.kt).
 */

package com.potaty.backend.ir

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ValidationReport(
    val valid: Boolean,
    val violations: List<ValidationViolation> = emptyList(),
    val warnings: List<ValidationViolation> = emptyList()
) {
    companion object {
        fun ok(): ValidationReport = ValidationReport(valid = true)
    }
}

@Serializable
data class ValidationViolation(
    /** Rule id, e.g. "IR-R009". */
    val rule: String,
    val severity: Severity,
    val message: String,
    @SerialName("target_id")
    val targetId: String? = null
)

/** Severity of a validation finding as surfaced in the API. */
@Serializable
enum class Severity {
    @SerialName("error")
    ERROR,

    @SerialName("warning")
    WARNING,

    @SerialName("info")
    INFO
}

/**
 * Thrown when an IR fails publish-time validation. Mapped to HTTP 422 by StatusPages.
 */
class ValidationException(val report: ValidationReport) :
    RuntimeException("IR validation failed with ${report.violations.size} violation(s)")
