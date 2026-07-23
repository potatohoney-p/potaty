/*
 * Copyright (c) 2026, Potaty
 */

package com.potaty.app.generate

import com.potaty.workbench.EvidenceCoverageDto
import com.potaty.workbench.ValidationReportDto

internal enum class ArtifactReviewStatus {
    BLOCKED,
    REVIEW,
    READY
}

internal fun ArtifactReviewStatus.workbenchLabel(): String =
    when (this) {
        ArtifactReviewStatus.BLOCKED -> "Blocked"
        ArtifactReviewStatus.REVIEW -> "Needs review"
        ArtifactReviewStatus.READY -> "Ready"
    }

internal fun ArtifactReviewStatus.completionMessage(): String =
    when (this) {
        ArtifactReviewStatus.BLOCKED ->
            "Diagram created, but validation issues block publishing."
        ArtifactReviewStatus.REVIEW ->
            "Diagram created. Review evidence gaps before publishing."
        ArtifactReviewStatus.READY ->
            "Diagram ready. Review the evidence before publishing."
    }

/** Mirrors the canonical publication threshold without allowing one metric to mask another. */
internal fun artifactReviewStatus(
    coverage: EvidenceCoverageDto,
    validation: ValidationReportDto
): ArtifactReviewStatus =
    when {
        !validation.valid ||
            validation.violations.isNotEmpty() ||
            coverage.unsupportedCriticalClaims > 0 -> ArtifactReviewStatus.BLOCKED
        coverage.nodeCoverage < MIN_NODE_COVERAGE ||
            coverage.edgeCoverage < MIN_EDGE_COVERAGE -> ArtifactReviewStatus.REVIEW
        else -> ArtifactReviewStatus.READY
    }

private const val MIN_NODE_COVERAGE = 0.90
private const val MIN_EDGE_COVERAGE = 0.80
