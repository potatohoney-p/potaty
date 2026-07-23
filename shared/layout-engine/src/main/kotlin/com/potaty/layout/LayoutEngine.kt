/*
 * Copyright (c) 2026, Potaty
 */

package com.potaty.layout

import com.potaty.ir.DiagramIR
import com.potaty.ir.DiagramType

/**
 * Computes integer-cell coordinates for a [DiagramIR]. Implementations are deterministic so the
 * same IR always yields the same layout (required for golden/visual-regression tests).
 *
 * Determinism guarantee & its limits: identical IR -> byte-identical [LayoutResult] on every run
 * and on every Kotlin target (JVM and JS). This holds because the engines iterate the IR's node and
 * edge *lists* (insertion order), never a `HashMap`'s key order — see [com.potaty.ir.CycleDetector],
 * which deliberately re-seeds its DFS from the caller-supplied node order. The corollary is that the
 * guarantee is over the IR *as given*: reordering `ir.nodes` is a different input and may shift
 * layer assignment / barycenter ordering, so the layout may change. Callers who need a canonical
 * layout must canonicalise node order upstream.
 */
interface LayoutEngine {
    val version: String
    fun layout(ir: DiagramIR): LayoutResult
}

object LayoutEngineFactory {
    const val VERSION: String = "layout-1.0"

    /**
     * Returns the engine for [type]. Determinism is the same as documented on [LayoutEngine]: the
     * result is stable for a given IR across runs and platforms, but is a function of the IR's node
     * list order — reordered nodes are a different input.
     */
    fun forType(
        type: DiagramType,
        metrics: LayoutMetrics = LayoutMetrics.DEFAULT
    ): LayoutEngine = when (type) {
        DiagramType.SEQUENCE -> SwimlaneLayoutEngine(metrics)
        DiagramType.TIMELINE -> TimelineLayoutEngine(metrics)
        DiagramType.ARCHITECTURE,
        DiagramType.CONTAINER,
        DiagramType.DEPLOYMENT -> GridContainerLayoutEngine(metrics)
        else -> LayeredLayoutEngine(metrics)
    }

    /**
     * Group-aware engine selection. The grid/container engine is designed for grouped C4-style
     * diagrams; given a container-family diagram with NO groups (e.g. one extracted from plain
     * pasted text), it packs nodes into a grid and routes edges through them, producing overlaps.
     * In that case we fall back to the layered (Sugiyama) engine, which lays out an arbitrary DAG
     * cleanly with zero overlaps. Grouped container diagrams keep the grid engine, so existing
     * golden fixtures (which carry groups) are unchanged.
     */
    fun forIr(
        ir: DiagramIR,
        metrics: LayoutMetrics = LayoutMetrics.DEFAULT
    ): LayoutEngine {
        val containerFamily = ir.diagramType == DiagramType.ARCHITECTURE ||
            ir.diagramType == DiagramType.CONTAINER ||
            ir.diagramType == DiagramType.DEPLOYMENT
        return if (containerFamily && ir.groups.isEmpty()) {
            LayeredLayoutEngine(metrics)
        } else {
            forType(ir.diagramType, metrics)
        }
    }
}
