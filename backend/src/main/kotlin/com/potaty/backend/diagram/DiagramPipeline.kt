/*
 * Copyright (c) 2026, Potaty
 *
 * The text/document -> DiagramIR pipeline (plan sections 7 + 11). Runs the grounded path:
 *
 *   load source chunks -> deterministic entity/relation extraction -> assemble canonical IR
 *   -> validate (IrValidator R001..R017) -> score evidence coverage -> compile code renderings
 *   -> persist an immutable diagram_version.
 *
 * The result is a fully validated, evidence-linked diagram WITHOUT requiring a live LLM, which is
 * what makes the whole flow testable. An optional LLM enrichment hook (grouping / inferred edges)
 * layers on top via StructuredCaller; it never weakens the grounded baseline.
 */

@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.potaty.backend.diagram

import com.potaty.backend.api.DiagramJobRequest
import com.potaty.backend.api.EvidenceCoverageDto
import com.potaty.backend.api.RenderingDto
import com.potaty.backend.config.ModelTier
import com.potaty.backend.cost.CostEstimator
import com.potaty.backend.extraction.CodeStructureExtractor
import com.potaty.backend.extraction.EntityRelationExtractor
import com.potaty.backend.extraction.ExtractionMerger
import com.potaty.backend.extraction.ExtractionScopeReducer
import com.potaty.backend.ir.ValidationReport
import com.potaty.backend.ir.toApiDto
import com.potaty.backend.jobs.JobContext
import com.potaty.backend.jobs.JobPipeline
import com.potaty.backend.jobs.PipelineStep
import com.potaty.backend.jobs.StageName
import com.potaty.backend.jobs.StageResult
import com.potaty.backend.llm.LlmDiagramEnricher
import com.potaty.backend.persistence.IdentityRepository
import com.potaty.backend.persistence.TenantIntegrityException
import com.potaty.backend.persistence.repositories.DiagramRepository
import com.potaty.backend.persistence.repositories.GeneratedDiagramArtifact
import com.potaty.backend.persistence.repositories.SourceRepository
import com.potaty.backend.source.SourceSafetyGateway
import com.potaty.backend.usage.UsageRecorder
import com.potaty.codegen.CodegenFacade
import com.potaty.codegen.CodegenFormat
import com.potaty.ir.DiagramIR
import com.potaty.ir.EvidenceCoverage
import com.potaty.ir.EvidenceCoverageScorer
import com.potaty.ir.IrJson
import com.potaty.ir.IrValidator
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class DiagramResult(
    val diagramId: UUID,
    val versionId: UUID,
    val ir: DiagramIR,
    val irJson: String,
    val validation: ValidationReport,
    val coverage: EvidenceCoverageDto,
    val renderings: List<RenderingDto>
)

class InsufficientDiagramStructureException(message: String) : RuntimeException(message)

private const val RENDERER_VERSION = "renderer-codegen-1.1"
private const val LAYOUT_ENGINE_VERSION = "jvm-codegen-no-layout"
private const val MAX_SOURCE_VERSIONS_PER_JOB = 100

class DiagramPipeline(
    private val sources: SourceRepository,
    private val diagrams: DiagramRepository,
    private val identities: IdentityRepository,
    private val json: Json = Json {
        encodeDefaults = true
        explicitNulls = false
    },
    // Optional LLM summarisation (plan 2.3): present only when a provider credential is configured.
    private val enricher: LlmDiagramEnricher? = null,
    private val usageRecorder: UsageRecorder? = null,
    private val costEstimator: CostEstimator? = null
) {
    private val validator = IrValidator()

    init {
        require((usageRecorder == null) == (costEstimator == null)) {
            "usageRecorder and costEstimator must be configured together"
        }
    }

    suspend fun generate(
        workspaceId: UUID,
        projectId: UUID,
        createdBy: UUID?,
        request: DiagramJobRequest,
        jobId: UUID? = null,
        emit: suspend (StageName, String) -> Unit = { _, _ -> }
    ): DiagramResult {
        // Repeat the HTTP enqueue-boundary checks at the execution boundary. Queue rows can be
        // replayed, migrated, or written by an operator, so a worker must never trust stored IDs.
        if (
            request.sourceVersionIds.isEmpty() ||
            request.sourceVersionIds.size > MAX_SOURCE_VERSIONS_PER_JOB
        ) {
            throw TenantIntegrityException(
                "Diagram jobs require between 1 and $MAX_SOURCE_VERSIONS_PER_JOB source versions"
            )
        }
        val sourceVersionIds =
            request.sourceVersionIds.map { rawId ->
                runCatching { UUID.fromString(rawId) }.getOrNull()
                    ?: throw TenantIntegrityException(
                        "Diagram job contains an invalid source version ID"
                    )
            }
        if (sourceVersionIds.toSet().size != sourceVersionIds.size) {
            throw TenantIntegrityException("Diagram job contains duplicate source versions")
        }
        identities.requireProject(workspaceId, projectId)
        identities.requireSourceVersionsInProject(workspaceId, projectId, sourceVersionIds)

        val outputFormats = canonicalFormats(request.outputFormats)
        jobId?.let { generationJobId ->
            diagrams.findGeneratedArtifact(workspaceId, generationJobId)?.let { artifact ->
                emit(
                    StageName.PERSIST_DIAGRAM_VERSION,
                    "reusing the artifact already committed for this job"
                )
                return resultFromArtifact(artifact, outputFormats)
            }
        }

        emit(StageName.SOURCE_NORMALIZER, "loading normalized source chunks")
        val chunks = sourceVersionIds.flatMap { sources.listChunks(workspaceId, it) }

        emit(
            StageName.ENTITY_RELATION_EXTRACTOR,
            "extracting entities & relations from ${chunks.size} chunk(s)"
        )
        // Static extraction before any LLM (plan 2.3). For indexed code repositories the import/
        // dependency graph (CodeStructureExtractor) is the meaningful "flow"; when it yields a real
        // graph we use it, otherwise fall back to the prose arrow/verb extractor (text, READMEs).
        val codeGraph = CodeStructureExtractor.extract(chunks)
        var extraction =
            if (codeGraph.entities.size >= 2 && codeGraph.relations.isNotEmpty()) {
                codeGraph
            } else {
                EntityRelationExtractor.extract(chunks)
            }

        // LLM enrichment (plan 2.3): only when configured AND the grounded graph is sparse
        // (free-form
        // prose with no explicit structure). Summarises the source into a node/edge graph; on any
        // failure we keep the deterministic result. Code/structured inputs keep their grounded
        // graph.
        var hasInferredAdditions = false
        var modelTraceJson = "[]"
        val activeEnricher = enricher
        if (activeEnricher != null && extraction.relations.size < 2) {
            emit(
                StageName.ENTITY_RELATION_EXTRACTOR,
                "LLM summarisation (sparse deterministic graph)"
            )
            val sourceText = chunks.joinToString("\n\n") { it.text }
            val enrichment =
                runCatching {
                    activeEnricher.enrich(
                        sourceText,
                        request.diagramType,
                        workspaceId.toString()
                    )
                }
                    .getOrNull()
            if (enrichment != null) {
                val usage = enrichment.usage
                usageRecorder?.record(
                    workspaceId = workspaceId,
                    jobId = jobId,
                    provider = enrichment.provider.name.lowercase(),
                    model = enrichment.model,
                    stage = LLM_ENRICHMENT_STAGE,
                    inputTokens = usage.inputTokens,
                    outputTokens = usage.outputTokens,
                    cachedInputTokens = usage.cachedInputTokens,
                    estimatedCostUsd =
                    requireNotNull(costEstimator).costOf(
                        ModelTier.CHEAP_STRUCTURED,
                        usage.inputTokens,
                        usage.outputTokens
                    )
                )

                enrichment.extraction
                    ?.takeIf { it.entities.isNotEmpty() && it.relations.isNotEmpty() }
                    ?.let { inferred ->
                        val merge = ExtractionMerger.merge(extraction, inferred)
                        extraction = merge.extraction
                        hasInferredAdditions = merge.hasInferredAdditions
                    }

                modelTraceJson = buildJsonArray {
                    add(
                        buildJsonObject {
                            put("stage", LLM_ENRICHMENT_STAGE)
                            put("provider", enrichment.provider.name.lowercase())
                            put("model", enrichment.model)
                            put("inputTokens", usage.inputTokens)
                            put("outputTokens", usage.outputTokens)
                            put("cachedInputTokens", usage.cachedInputTokens)
                            put("applied", hasInferredAdditions)
                            enrichment.failureKind?.let { put("failureKind", it) }
                        }
                    )
                }
                    .toString()
            }
        }

        extraction = ExtractionScopeReducer.reduce(extraction, request.scope)
        if (extraction.entities.isEmpty()) {
            throw InsufficientDiagramStructureException(
                "No diagram structure could be extracted. Add named steps, bullet points, " +
                    "or explicit relationships."
            )
        }

        // Objectives are untrusted source-adjacent metadata. Run them through the same canonical
        // redaction boundary as source chunks before they become a persisted/exported IR title or
        // objective. This closes the path where a credential redacted from the source body could
        // otherwise survive in request.objective.
        val safeObjective =
            request.objective
                ?.let { SourceSafetyGateway.process(it).canonicalText }
                ?.takeIf { it.isNotBlank() }
        val title =
            safeObjective
                ?: "Generated ${request.diagramType.name.lowercase()} diagram"
        val diagramId = diagrams.diagramIdForGeneration(workspaceId, jobId)

        val ir =
            IrAssembler.assemble(
                diagramId = diagramId.toString(),
                title = title,
                diagramType = request.diagramType,
                objective = safeObjective,
                sourceSnapshotIds = sourceVersionIds.map(UUID::toString),
                extraction = extraction,
                generatedBy =
                if (hasInferredAdditions) {
                    "deterministic-extractor+llm-enricher"
                } else {
                    "deterministic-extractor"
                }
            )

        emit(
            StageName.IR_VALIDATOR,
            "validating IR (${ir.nodes.size} nodes, ${ir.edges.size} edges)"
        )
        val report = validator.validate(ir)
        val coverage = EvidenceCoverageScorer.score(ir)

        emit(StageName.RENDERER_COMPILER, "compiling code renderings")
        val renderings = compileRenderings(ir, request.outputFormats)

        val irJson = IrJson.encode(ir, pretty = false)
        emit(StageName.PERSIST_DIAGRAM_VERSION, "persisting diagram version")
        val artifact =
            diagrams.persistGeneratedArtifact(
                workspaceId = workspaceId,
                projectId = projectId,
                diagramId = diagramId,
                generationJobId = jobId,
                title = title,
                diagramType = request.diagramType.name.lowercase(),
                irJson = irJson,
                validationReportJson =
                json.encodeToString(
                    ValidationReport.serializer(),
                    report.toApiDto()
                ),
                evidenceCoverageJson = json.encodeToString(EvidenceCoverage.serializer(), coverage),
                sourceSnapshotJson = buildJsonArray {
                    sourceVersionIds.forEach {
                        add(kotlinx.serialization.json.JsonPrimitive(it.toString()))
                    }
                }
                    .toString(),
                modelTraceJson = modelTraceJson,
                rendererVersion = RENDERER_VERSION,
                layoutEngineVersion = LAYOUT_ENGINE_VERSION,
                renderings = renderings.map { it.format to it.contentText.orEmpty() },
                createdBy = createdBy
            )
        return resultFromArtifact(artifact, outputFormats)
    }

    private fun resultFromArtifact(
        artifact: GeneratedDiagramArtifact,
        outputFormats: List<CodegenFormat>
    ): DiagramResult {
        val expectedFormats = outputFormats.map { it.name.lowercase() }
        val persistedByFormat = artifact.renderings.associateBy { it.format }
        if (
            persistedByFormat.keys != expectedFormats.toSet() ||
            artifact.renderings.size != expectedFormats.size
        ) {
            throw TenantIntegrityException(
                "Persisted diagram artifact does not match the job output formats"
            )
        }

        val ir = IrJson.decode(artifact.version.irJson)
        val report = validator.validate(ir)
        val coverage = EvidenceCoverageScorer.score(ir)
        return DiagramResult(
            diagramId = artifact.diagram.id,
            versionId = artifact.version.id,
            ir = ir,
            irJson = artifact.version.irJson,
            validation = report.toApiDto(),
            coverage =
            EvidenceCoverageDto(
                nodeCoverage = coverage.nodeCoverage,
                edgeCoverage = coverage.edgeCoverage,
                unsupportedCriticalClaims = coverage.unsupportedCriticalClaims
            ),
            renderings =
            expectedFormats.map { format ->
                RenderingDto(
                    format = format,
                    contentText = requireNotNull(persistedByFormat[format]).contentText
                )
            }
        )
    }

    /** Compiles requested JVM code formats. Unknown/browser-only formats fail closed. */
    fun compileRenderings(ir: DiagramIR, requested: List<String>): List<RenderingDto> {
        return canonicalFormats(requested).map { fmt ->
            RenderingDto(
                format = fmt.name.lowercase(),
                contentText = CodegenFacade.compile(ir, fmt)
            )
        }
    }

    private fun canonicalFormats(requested: List<String>): List<CodegenFormat> {
        val unsupported = unsupportedFormats(requested)
        require(unsupported.isEmpty()) {
            "unsupported outputFormats: ${unsupported.joinToString(", ")}"
        }
        return requested
            .map { requireNotNull(toCodegenFormat(it)) }
            .distinct()
            .ifEmpty { listOf(CodegenFormat.MERMAID) }
    }

    companion object {
        fun unsupportedFormats(names: List<String>): List<String> =
            names.filter { toCodegenFormat(it) == null }.distinct()

        fun toCodegenFormat(name: String): CodegenFormat? =
            when (name.lowercase().trim()) {
                "mermaid",
                "mmd" -> CodegenFormat.MERMAID
                "d2" -> CodegenFormat.D2
                "plantuml",
                "puml" -> CodegenFormat.PLANTUML
                "dot",
                "graphviz" -> CodegenFormat.DOT
                "markdown",
                "md" -> CodegenFormat.MARKDOWN
                else -> null // ascii / svg / png / pdf require renderers not implemented here
            }
    }
}

/**
 * Wraps [DiagramPipeline] as the worker's [JobPipeline]: parses the stored DiagramJobRequest, runs
 * generation, and returns a small JSON output carrying the new diagram/version ids.
 */
fun diagramJobPipeline(pipeline: DiagramPipeline, json: Json): JobPipeline =
    JobPipeline(
        listOf(
            object : PipelineStep {
                override val name = StageName.IR_GENERATOR

                override suspend fun run(input: Any?, context: JobContext): StageResult<Any?> {
                    val projectId =
                        context.projectId
                            ?: return StageResult.FatalFailure("diagram job is missing a projectId")
                    val request = runCatching {
                        json.decodeFromString(DiagramJobRequest.serializer(), input as String)
                    }
                        .getOrElse {
                            return StageResult.FatalFailure("invalid job input: ${it.message}")
                        }

                    val result =
                        try {
                            pipeline.generate(
                                workspaceId = context.workspaceId,
                                projectId = projectId,
                                createdBy = context.createdBy,
                                request = request,
                                jobId = context.jobId,
                                emit = { stage, message -> context.emitEvent(stage, message) }
                            )
                        } catch (invalid: TenantIntegrityException) {
                            return StageResult.FatalFailure(
                                invalid.message ?: "invalid diagram job source scope"
                            )
                        } catch (insufficient: InsufficientDiagramStructureException) {
                            return StageResult.NeedsUserInput(
                                insufficient.message ?: "more source structure is required",
                                listOf("named steps", "bullet points", "explicit relationships")
                            )
                        } catch (invalid: IllegalArgumentException) {
                            return StageResult.FatalFailure(
                                invalid.message ?: "invalid diagram job input"
                            )
                        }
                    val output = buildJsonObject {
                        put("diagramId", result.diagramId.toString())
                        put("versionId", result.versionId.toString())
                        put("status", "needs_review")
                        put("nodeCoverage", result.coverage.nodeCoverage)
                        put("edgeCoverage", result.coverage.edgeCoverage)
                    }
                    return StageResult.Ok(
                        json.encodeToString(
                            kotlinx.serialization.json.JsonObject.serializer(),
                            output
                        )
                    )
                }
            }
        )
    )

private const val LLM_ENRICHMENT_STAGE = "diagram_enrichment"
