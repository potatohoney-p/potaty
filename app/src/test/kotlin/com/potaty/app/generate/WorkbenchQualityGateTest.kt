/*
 * Copyright (c) 2026, Potaty
 */

package com.potaty.app.generate

import com.potaty.workbench.EvidenceCoverageDto
import com.potaty.workbench.ValidationReportDto
import com.potaty.workbench.ValidationViolationDto
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkbenchQualityGateTest {
    @Test
    fun highNodeCoverageCannotMaskLowEdgeCoverage() {
        val coverage = EvidenceCoverageDto(nodeCoverage = 1.0, edgeCoverage = 0.5)
        assertEquals(
            ArtifactReviewStatus.REVIEW,
            artifactReviewStatus(coverage, ValidationReportDto(valid = true))
        )
    }

    @Test
    fun serverValidationViolationAlwaysBlocks() {
        val validation =
            ValidationReportDto(
                valid = false,
                violations =
                listOf(
                    ValidationViolationDto(
                        rule = "IR-R011",
                        severity = "error",
                        message = "cycle"
                    )
                )
            )
        assertEquals(
            ArtifactReviewStatus.BLOCKED,
            artifactReviewStatus(
                EvidenceCoverageDto(nodeCoverage = 1.0, edgeCoverage = 1.0),
                validation
            )
        )
    }

    @Test
    fun canonicalThresholdsProduceReady() {
        val status =
            artifactReviewStatus(
                EvidenceCoverageDto(nodeCoverage = 0.90, edgeCoverage = 0.80),
                ValidationReportDto(valid = true)
            )
        assertEquals(ArtifactReviewStatus.READY, status)
        assertEquals("Ready", status.workbenchLabel())
        assertEquals(
            "Diagram ready. Review the evidence before publishing.",
            status.completionMessage()
        )
    }

    @Test
    fun blockedAndReviewLabelsNeverClaimReady() {
        assertEquals("Blocked", ArtifactReviewStatus.BLOCKED.workbenchLabel())
        assertEquals("Needs review", ArtifactReviewStatus.REVIEW.workbenchLabel())
        assertEquals(
            "Diagram created, but validation issues block publishing.",
            ArtifactReviewStatus.BLOCKED.completionMessage()
        )
    }
}
