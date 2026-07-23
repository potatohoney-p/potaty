/*
 * Copyright (c) 2026, Potaty
 */

package com.potaty.ir

/**
 * Computes a human-meaningful diff between two diagram versions, keyed by [IrNode.stableKey] /
 * [IrEdge.stableKey] so that regeneration (new local ids) does not look like a full rewrite.
 * Drives the Workbench version-diff view (plan section 19.5).
 */
object IrDiff {

    data class NodeChange(
        val stableKey: String,
        val before: IrNode?,
        val after: IrNode?,
        val labelChanged: Boolean,
        val confidenceDelta: Double
    )

    data class EdgeChange(
        val stableKey: String,
        val before: IrEdge?,
        val after: IrEdge?,
        val confidenceDelta: Double,
        val sourceTypeChanged: Boolean
    )

    data class Result(
        val addedNodes: List<IrNode>,
        val removedNodes: List<IrNode>,
        val changedNodes: List<NodeChange>,
        val addedEdges: List<IrEdge>,
        val removedEdges: List<IrEdge>,
        val changedEdges: List<EdgeChange>
    ) {
        val isEmpty: Boolean
            get() = addedNodes.isEmpty() && removedNodes.isEmpty() && changedNodes.isEmpty() &&
                addedEdges.isEmpty() && removedEdges.isEmpty() && changedEdges.isEmpty()
    }

    fun diff(before: DiagramIR, after: DiagramIR): Result {
        val beforeNodes = before.nodes.associateBy { it.stableKey }
        val afterNodes = after.nodes.associateBy { it.stableKey }

        val addedNodes = after.nodes.filter { it.stableKey !in beforeNodes }
        val removedNodes = before.nodes.filter { it.stableKey !in afterNodes }
        val changedNodes = beforeNodes.keys.intersect(afterNodes.keys).mapNotNull { key ->
            val b = beforeNodes.getValue(key)
            val a = afterNodes.getValue(key)
            if (b == a) {
                null
            } else {
                NodeChange(
                    stableKey = key,
                    before = b,
                    after = a,
                    labelChanged = b.label != a.label,
                    confidenceDelta = a.confidence - b.confidence
                )
            }
        }

        val beforeEdges = before.edges.associateBy { it.stableKey }
        val afterEdges = after.edges.associateBy { it.stableKey }

        val addedEdges = after.edges.filter { it.stableKey !in beforeEdges }
        val removedEdges = before.edges.filter { it.stableKey !in afterEdges }
        val changedEdges = beforeEdges.keys.intersect(afterEdges.keys).mapNotNull { key ->
            val b = beforeEdges.getValue(key)
            val a = afterEdges.getValue(key)
            if (b == a) {
                null
            } else {
                EdgeChange(
                    stableKey = key,
                    before = b,
                    after = a,
                    confidenceDelta = a.confidence - b.confidence,
                    sourceTypeChanged = b.edgeSourceType != a.edgeSourceType
                )
            }
        }

        return Result(
            addedNodes,
            removedNodes,
            changedNodes,
            addedEdges,
            removedEdges,
            changedEdges
        )
    }
}
