/*
 * Copyright (c) 2026, Potaty
 */

package com.potaty.layout

import com.potaty.ir.DiagramIR

/**
 * Timeline / roadmap layout. A timeline is a strictly ordered vertical flow, so it reuses the
 * layered engine (top-to-bottom) which already produces clean ordered chains with elbow connectors.
 *
 * Lives in its own file (per plan section 16.2's one-engine-per-file separation); the behaviour is
 * unchanged — it delegates verbatim to [LayeredLayoutEngine].
 */
class TimelineLayoutEngine(
    private val metrics: LayoutMetrics = LayoutMetrics.DEFAULT
) : LayoutEngine {
    override val version: String = "timeline-1.0"
    private val delegate = LayeredLayoutEngine(metrics)
    override fun layout(ir: DiagramIR): LayoutResult = delegate.layout(ir)
}
