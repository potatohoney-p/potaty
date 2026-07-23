/*
 * Copyright (c) 2026, Potaty
 *
 * Pipeline stage contract (plan section 11.1). A Stage<I, O> is a pure, retryable,
 * resumable unit of work that consumes the previous stage's output and produces the next.
 */

package com.potaty.backend.jobs

import java.util.UUID
import kotlin.time.Duration
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/** Canonical names of the 20 pipeline stages (plan 11.2). */
enum class StageName {
    INPUT_ROUTER,
    SOURCE_NORMALIZER,
    SAFETY_PRE_SCAN,
    CHUNKER,
    EMBEDDING_INDEXER,
    STATIC_EXTRACTOR,
    ENTITY_RELATION_EXTRACTOR,
    ENTITY_DEDUP_GRAPH_MERGE,
    DIAGRAM_TASK_PLANNER,
    IR_GENERATOR,
    IR_VALIDATOR,
    FAITHFULNESS_CRITIC,
    LAYOUT_PLANNER,
    RENDERER_COMPILER,
    SYNTAX_VALIDATOR,
    REPAIR_LOOP,
    EVIDENCE_COVERAGE_SCORER,
    PERSIST_DIAGRAM_VERSION,
    HUMAN_REVIEW_GATE,
    EXPORT_OR_PUBLISH
}

/** Per-stage instrumentation captured into job_events / usage_events. */
data class StageMetrics(
    val durationMs: Long = 0,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val notes: String? = null
)

/** Context handed to every stage: identity + a sink for incremental progress events. */
data class JobContext(
    val jobId: UUID,
    val workspaceId: UUID,
    val projectId: UUID?,
    val createdBy: UUID? = null,
    val emitEvent: suspend (StageName, String) -> Unit
)

sealed interface StageResult<out T> {
    data class Ok<T>(val value: T, val metrics: StageMetrics = StageMetrics()) : StageResult<T>

    data class RetryableFailure(val reason: String, val retryAfter: Duration? = null) :
        StageResult<Nothing>

    data class FatalFailure(
        val reason: String,
        val details: JsonObject = buildJsonObject {}
    ) : StageResult<Nothing>

    data class NeedsUserInput(val reason: String, val requiredInput: List<String>) :
        StageResult<Nothing>
}

interface Stage<I, O> {
    val name: StageName

    suspend fun run(input: I, context: JobContext): StageResult<O>
}
