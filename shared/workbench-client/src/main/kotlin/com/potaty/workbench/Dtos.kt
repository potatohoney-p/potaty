/*
 * Copyright (c) 2026, Potaty
 *
 * @Serializable DTOs for the Potaty /api/v1 contract, mirroring the backend shapes (com.potaty.
 * backend.api.Dtos). The diagram-version `ir` field stays a JsonObject so this client does not
 * couple to the full IR decode; com.potaty.ir.DiagramIR can be decoded from it on demand.
 */

package com.potaty.workbench

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class CreateSourceRequest(
    @SerialName("sourceType") val sourceType: String,
    @SerialName("displayName") val displayName: String,
    val content: String? = null
)

@Serializable
data class CreateSourceResponse(
    val sourceId: String,
    val sourceVersionId: String,
    val contentHash: String,
    val status: String,
    val secretsRedacted: Int = 0,
    val piiWarnings: Int = 0
)

@Serializable
data class GitHubIndexUrlRequest(
    @SerialName("repoUrl") val repoUrl: String,
    @SerialName("ref") val ref: String? = null
)

@Serializable
data class GitHubIndexResponse(
    val sourceId: String,
    val sourceVersionId: String,
    val contentHash: String = "",
    val filesIndexed: Int = 0,
    val filesSkipped: Int = 0,
    val chunkCount: Int = 0,
    val treeTruncated: Boolean = false,
    val owner: String = "",
    val repo: String = "",
    val ref: String = ""
)

@Serializable
data class JobScope(
    val include: List<String> = emptyList(),
    val exclude: List<String> = emptyList(),
    val abstractionLevel: String = "medium"
)

@Serializable
data class DiagramJobRequest(
    val sourceVersionIds: List<String>,
    val diagramType: String,
    val objective: String? = null,
    val scope: JobScope = JobScope(),
    val outputFormats: List<String> = listOf("mermaid"),
    val qualityMode: String = "production"
)

@Serializable
data class CostRange(val lowUsd: Double, val highUsd: Double)

@Serializable
data class DiagramJobResponse(
    val jobId: String,
    val status: String,
    val estimatedCostRange: CostRange = CostRange(0.0, 0.0)
)

@Serializable
data class JobStatusResponse(
    val jobId: String,
    val status: String,
    val currentStage: String? = null,
    val progress: Double = 0.0,
    val reason: String? = null,
    val output: JsonObject? = null
)

@Serializable
data class CancelJobResponse(
    val jobId: String,
    val status: String,
    val cancelled: Boolean
)

@Serializable
data class RenderingDto(
    val format: String,
    val contentText: String? = null,
    val objectKey: String? = null
)

@Serializable
data class EvidenceCoverageDto(
    val nodeCoverage: Double = 0.0,
    val edgeCoverage: Double = 0.0,
    val unsupportedCriticalClaims: Int = 0
)

@Serializable
data class ValidationViolationDto(
    val rule: String,
    val severity: String,
    val message: String,
    @SerialName("target_id") val targetId: String? = null
)

@Serializable
data class ValidationReportDto(
    val valid: Boolean = false,
    val violations: List<ValidationViolationDto> = emptyList(),
    val warnings: List<ValidationViolationDto> = emptyList()
)

@Serializable
data class DiagramVersionResponse(
    val diagramId: String,
    val versionId: String,
    val status: String,
    val ir: JsonObject,
    val validationReport: ValidationReportDto = ValidationReportDto(),
    val evidenceCoverage: EvidenceCoverageDto = EvidenceCoverageDto(),
    val renderings: List<RenderingDto> = emptyList()
)
