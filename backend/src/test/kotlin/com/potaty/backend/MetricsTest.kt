/*
 * Copyright (c) 2026, Potaty
 *
 * Unit tests for the in-process Metrics registry (WS15): counters accumulate, gauges are
 * settable, the well-known named helpers map to the documented metric names, and render()
 * produces valid, deterministic Prometheus text exposition (HELP/TYPE lines + samples).
 * Deterministic; no network, no database.
 */

package com.potaty.backend

import com.potaty.backend.observability.Metrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MetricsTest {

    @Test
    fun countersAccumulate() {
        val m = Metrics()
        m.incJobsCreated()
        m.incJobsCreated(2)
        m.incJobsCompleted()
        m.incJobsFailed(3)
        m.incLlmTokens(1500)
        m.incSecretScanHits()

        assertEquals(3, m.counter(Metrics.JOBS_CREATED))
        assertEquals(1, m.counter(Metrics.JOBS_COMPLETED))
        assertEquals(3, m.counter(Metrics.JOBS_FAILED))
        assertEquals(1500, m.counter(Metrics.LLM_TOKENS))
        assertEquals(1, m.counter(Metrics.SECRET_SCAN_HITS))
        // Untouched counter reads as zero.
        assertEquals(0, m.counter(Metrics.RENDER_FAILURES))
    }

    @Test
    fun gaugesAreSettable() {
        val m = Metrics()
        m.setGauge("potaty_queue_depth", 5.0, "Jobs currently queued.")
        assertEquals(5.0, m.gauge("potaty_queue_depth"))
        m.setGauge("potaty_queue_depth", 2.0)
        assertEquals(2.0, m.gauge("potaty_queue_depth"))
    }

    @Test
    fun rejectsNegativeCounterDelta() {
        val m = Metrics()
        var threw = false
        try {
            m.increment("x", -1)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw, "counters must reject negative deltas")
    }

    @Test
    fun renderProducesPrometheusExposition() {
        val m = Metrics()
        m.incJobsCreated(2)
        m.incRenderFailures()

        val out = m.render()

        // HELP + TYPE + sample line for a counter, with the _total suffix preserved.
        assertTrue(out.contains("# TYPE ${Metrics.JOBS_CREATED} counter"), "TYPE line present")
        assertTrue(out.contains("# HELP ${Metrics.JOBS_CREATED} "), "HELP line present")
        assertTrue(out.contains("${Metrics.JOBS_CREATED} 2"), "sample line present with value")
        assertTrue(out.contains("${Metrics.RENDER_FAILURES} 1"))

        // Every non-comment line is of the form `<name> <value>`; counters render as integers.
        out.lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .forEach { line ->
                val parts = line.split(' ')
                assertEquals(2, parts.size, "metric sample must be `name value`: '$line'")
                assertTrue(parts[1].toDoubleOrNull() != null, "value must be numeric: '$line'")
            }
    }

    @Test
    fun renderIsDeterministicallyOrdered() {
        val m = Metrics()
        // Register out of name order; output must be name-sorted for stable snapshots.
        m.incJobsFailed()
        m.incJobsCreated()
        m.incJobsCompleted()

        val out = m.render()
        val createdIdx = out.indexOf(Metrics.JOBS_CREATED + " ")
        val completedIdx = out.indexOf(Metrics.JOBS_COMPLETED + " ")
        val failedIdx = out.indexOf(Metrics.JOBS_FAILED + " ")
        // Alphabetical names: potaty_jobs_completed_total < potaty_jobs_created_total < potaty_jobs_failed_total
        assertTrue(completedIdx < createdIdx, "completed sorts before created")
        assertTrue(createdIdx < failedIdx, "created sorts before failed")

        // Calling render twice yields identical output.
        assertEquals(out, m.render())
    }
}
