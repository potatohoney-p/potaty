/*
 * Copyright (c) 2026, Potaty
 *
 * Type-erased pipeline runner used by background jobs. Concrete pipelines provide real
 * [PipelineStep] implementations (for example `diagramJobPipeline` in the diagram module); this
 * class owns only ordered execution, progress events, and terminal short-circuiting.
 */

package com.potaty.backend.jobs

/**
 * Type-erased stage façade so heterogeneous Stage<I,O> instances can live in one ordered list.
 * Concrete pipelines should prefer a typed builder when their stages exchange different types.
 * This erased form keeps the queue/worker contract small and stable.
 */
interface PipelineStep {
    val name: StageName
    suspend fun run(input: Any?, context: JobContext): StageResult<Any?>
}

class JobPipeline(val steps: List<PipelineStep>) {

    /**
     * Runs steps in order, threading each Ok value into the next. Returns the terminal result.
     * Non-Ok results short-circuit (caller maps them to queue transitions).
     */
    suspend fun run(initialInput: Any?, context: JobContext): StageResult<Any?> {
        var current: Any? = initialInput
        for (step in steps) {
            context.emitEvent(step.name, "stage ${step.name} started")
            when (val result = step.run(current, context)) {
                is StageResult.Ok -> {
                    current = result.value
                    context.emitEvent(step.name, "stage ${step.name} ok")
                }
                else -> return result // RetryableFailure / FatalFailure / NeedsUserInput
            }
        }
        return StageResult.Ok(current)
    }
}
