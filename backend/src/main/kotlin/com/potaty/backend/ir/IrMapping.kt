/*
 * Copyright (c) 2026, Potaty
 *
 * Maps the canonical diagram-ir validator output (com.potaty.ir.ValidationReport, an internal
 * non-serializable result) into the serializable API DTO served by the backend. Keeping the API
 * shape decoupled from the validator's internal model means rule-set changes don't break clients.
 */

package com.potaty.backend.ir

import com.potaty.ir.ValidationIssue
import com.potaty.ir.ValidationReport as IrValidationReport

fun IrValidationReport.toApiDto(): ValidationReport {
    fun map(issue: ValidationIssue, severity: Severity) =
        ValidationViolation(
            rule = issue.code,
            severity = severity,
            message = issue.message,
            targetId = issue.targetId
        )

    val violations = issues
        .filter { it.severity == ValidationIssue.Severity.ERROR }
        .map { map(it, Severity.ERROR) }
    val warnings = issues
        .filter { it.severity == ValidationIssue.Severity.WARNING }
        .map { map(it, Severity.WARNING) }

    return ValidationReport(valid = isValid, violations = violations, warnings = warnings)
}
