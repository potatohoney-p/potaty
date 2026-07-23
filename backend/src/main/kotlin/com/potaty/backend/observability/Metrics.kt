/*
 * Copyright (c) 2026, Potaty
 *
 * Tiny in-process metrics registry (plan 22-23, 20.6 operations). Deliberately dependency-free:
 * no Micrometer / Prometheus client, just thread-safe atomic counters and gauges plus a
 * Prometheus text-exposition renderer. This keeps the P0 single backend self-contained and
 * scrape-able by any Prometheus without pulling in a metrics stack.
 *
 * Two metric kinds:
 *   - counters: monotonically increasing totals (jobs created / completed / failed, llm tokens,
 *     render failures, secret-scan hits). Rendered as Prometheus `counter` with a `_total` suffix.
 *   - gauges:   arbitrary point-in-time values that can go up or down. Rendered as `gauge`.
 *
 * Well-known metric names are exposed as constants so producers and the renderer agree, and the
 * named convenience methods (incJobsCreated() etc.) register the metric with a HELP string the
 * first time they are touched so the exposition is always self-describing.
 */

package com.potaty.backend.observability

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.DoubleAdder

class Metrics {

    private data class CounterEntry(val help: String, val value: AtomicLong)

    private data class GaugeEntry(val help: String, val value: DoubleAdder)

    private val counters = ConcurrentHashMap<String, CounterEntry>()
    private val gauges = ConcurrentHashMap<String, GaugeEntry>()

    // ---- generic API ----------------------------------------------------------------------

    /** Increments [name] by [delta] (default 1), registering it with [help] on first use. */
    fun increment(name: String, delta: Long = 1, help: String = "") {
        require(delta >= 0) { "counters are monotonic; delta must be >= 0 (was $delta)" }
        counters.computeIfAbsent(name) { CounterEntry(help, AtomicLong(0)) }.value.addAndGet(delta)
    }

    fun counter(name: String): Long = counters[name]?.value?.get() ?: 0L

    /** Sets gauge [name] to [value] (registering it with [help] on first use). */
    fun setGauge(name: String, value: Double, help: String = "") {
        val entry = gauges.computeIfAbsent(name) { GaugeEntry(help, DoubleAdder()) }
        // DoubleAdder has no set(): reset then add so the observed value is exactly [value].
        synchronized(entry.value) {
            entry.value.reset()
            entry.value.add(value)
        }
    }

    fun gauge(name: String): Double = gauges[name]?.value?.sum() ?: 0.0

    // ---- well-known counters (plan 22) ----------------------------------------------------

    fun incJobsCreated(n: Long = 1) = increment(JOBS_CREATED, n, "Diagram jobs enqueued.")

    fun incJobsCompleted(n: Long = 1) =
        increment(
            JOBS_COMPLETED,
            n,
            "Diagram jobs that reached SUCCEEDED."
        )

    fun incJobsFailed(n: Long = 1) = increment(JOBS_FAILED, n, "Diagram jobs that reached FAILED.")

    fun incLlmTokens(n: Long) =
        increment(
            LLM_TOKENS,
            n,
            "Total LLM tokens (input + output) consumed."
        )

    fun incRenderFailures(n: Long = 1) = increment(RENDER_FAILURES, n, "Renderer/codegen failures.")

    fun incSecretScanHits(n: Long = 1) =
        increment(
            SECRET_SCAN_HITS,
            n,
            "Secrets stripped by the safety pre-scan."
        )

    /**
     * Renders all registered metrics in Prometheus text exposition format (v0.0.4). Output is
     * deterministic: metrics are emitted in name order so a snapshot test is stable. Each metric
     * gets a HELP and TYPE line followed by a single (unlabeled) sample.
     */
    fun render(): String {
        val sb = StringBuilder()
        counters.toSortedMap().forEach { (name, entry) ->
            appendMetric(sb, name, "counter", entry.help, entry.value.get().toString())
        }
        gauges.toSortedMap().forEach { (name, entry) ->
            appendMetric(sb, name, "gauge", entry.help, formatDouble(entry.value.sum()))
        }
        return sb.toString()
    }

    private fun appendMetric(
        sb: StringBuilder,
        name: String,
        type: String,
        help: String,
        value: String
    ) {
        if (help.isNotBlank()) {
            sb.append("# HELP ").append(name).append(' ').append(escapeHelp(help)).append('\n')
        }
        sb.append("# TYPE ").append(name).append(' ').append(type).append('\n')
        sb.append(name).append(' ').append(value).append('\n')
    }

    private fun escapeHelp(help: String): String = help.replace("\\", "\\\\").replace("\n", "\\n")

    /** Whole numbers render without a trailing ".0" so counters-as-gauges stay clean. */
    private fun formatDouble(d: Double): String =
        if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()

    companion object {
        const val JOBS_CREATED = "potaty_jobs_created_total"
        const val JOBS_COMPLETED = "potaty_jobs_completed_total"
        const val JOBS_FAILED = "potaty_jobs_failed_total"
        const val LLM_TOKENS = "potaty_llm_tokens_total"
        const val RENDER_FAILURES = "potaty_render_failures_total"
        const val SECRET_SCAN_HITS = "potaty_secret_scan_hits_total"
    }
}
