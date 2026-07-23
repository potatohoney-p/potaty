/*
 * Copyright (c) 2026, Potaty
 *
 * Request/response DTOs for the /api/v1 contract (plan section 10). All @Serializable.
 */

package com.potaty.backend.api

import com.potaty.backend.ir.ValidationReport
import com.potaty.ir.DiagramType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// ---------- 10.1 Source creation ----------

@Serializable
data class CreateSourceRequest(
    @SerialName("sourceType")
    val sourceType: String,
    @SerialName("displayName")
    val displayName: String,
    val content: String? = null,
    val metadata: JsonObject? = null
)

@Serializable
data class CreateSourceResponse(
    val sourceId: String,
    val sourceVersionId: String,
    val contentHash: String,
    val status: String,
    /** Count of secrets stripped from the stored content by the SafetyPreScan (plan 20.2). */
    val secretsRedacted: Int = 0,
    /** Count of PII matches found (reported, not removed) so the caller can review (plan 20.3). */
    val piiWarnings: Int = 0
)

// ---------- 10.2 Diagram generation job ----------

@Serializable
data class DiagramJobRequest(
    val sourceVersionIds: List<String>,
    val diagramType: DiagramType,
    val objective: String? = null,
    val scope: JobScope = JobScope(),
    val outputFormats: List<String> = listOf("mermaid"),
    val qualityMode: String = "production",
    val llmProviderPreference: String = "auto"
)

@Serializable
data class JobScope(
    val include: List<String> = emptyList(),
    val exclude: List<String> = emptyList(),
    val abstractionLevel: String = "medium"
)

@Serializable
data class DiagramJobResponse(
    val jobId: String,
    val status: String,
    val estimatedCostRange: CostRange
)

@Serializable
data class CostRange(
    val lowUsd: Double,
    val highUsd: Double
)

// ---------- 10.3 Job polling ----------

@Serializable
data class JobStatusResponse(
    val jobId: String,
    val status: String,
    val currentStage: String? = null,
    val progress: Double = 0.0,
    val events: List<JobEventDto> = emptyList(),
    /** Safe, bounded explanation for failed / needs_input terminal states. */
    val reason: String? = null,
    /** Present once the job succeeds: carries diagramId / versionId so clients can fetch the result. */
    val output: JsonObject? = null
)

@Serializable
data class CancelJobResponse(
    val jobId: String,
    val status: String,
    /** True only when this request changed a queued/running job to cancelled. */
    val cancelled: Boolean
)

@Serializable
data class JobEventDto(
    val stage: String? = null,
    val message: String? = null,
    val createdAt: String
)

// ---------- 10.4 Get diagram version ----------

@Serializable
data class DiagramVersionResponse(
    val diagramId: String,
    val versionId: String,
    val status: String,
    val ir: JsonObject,
    val validationReport: ValidationReport,
    val evidenceCoverage: EvidenceCoverageDto,
    val renderings: List<RenderingDto> = emptyList()
)

@Serializable
data class EvidenceCoverageDto(
    val nodeCoverage: Double,
    val edgeCoverage: Double,
    val unsupportedCriticalClaims: Int
)

@Serializable
data class RenderingDto(
    val format: String,
    val contentText: String? = null,
    val objectKey: String? = null
)

// ---------- 10.5 Apply natural-language patch ----------

@Serializable
data class PatchRequest(
    val instruction: String,
    val preserveUserEdits: Boolean = true,
    val qualityMode: String = "production"
)

@Serializable
data class PatchResponse(
    val newVersionId: String,
    val diff: PatchDiffDto,
    val validationReport: ValidationReport
)

@Serializable
data class PatchDiffDto(
    val addedNodes: List<String> = emptyList(),
    val removedNodes: List<String> = emptyList(),
    val changedEdges: List<String> = emptyList()
)

// ---------- 10.6 Export ----------

@Serializable
data class ExportRequest(
    val formats: List<String>,
    val theme: String = "potaty-clean",
    val includeEvidenceSummary: Boolean = true
)

@Serializable
data class ExportResponse(
    val exports: List<RenderingDto>
)

// ---------- shared error envelope ----------

@Serializable
data class ApiError(
    val error: String,
    val message: String,
    val details: JsonObject? = null
)
