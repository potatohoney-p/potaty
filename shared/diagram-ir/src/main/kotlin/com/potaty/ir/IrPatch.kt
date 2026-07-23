/*
 * Copyright (c) 2026, Potaty
 */

package com.potaty.ir

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A structured, serializable edit to a [DiagramIR]. Natural-language edits and the faithfulness
 * critic both emit [IrPatch]es rather than mutating shapes directly, so every change is
 * inspectable, diffable, and re-validatable. User edits set `userModified = true` so regeneration
 * can preserve them.
 */
@Serializable
data class IrPatch(
    val operations: List<IrPatchOp> = emptyList(),
    @SerialName("preserve_user_edits") val preserveUserEdits: Boolean = true
)

@Serializable
sealed class IrPatchOp {
    @Serializable
    @SerialName("add_node")
    data class AddNode(val node: IrNode) : IrPatchOp()

    @Serializable
    @SerialName("remove_node")
    data class RemoveNode(@SerialName("node_id") val nodeId: String) : IrPatchOp()

    @Serializable
    @SerialName("update_node")
    data class UpdateNode(
        @SerialName("node_id") val nodeId: String,
        val label: String? = null,
        val summary: String? = null,
        val type: NodeType? = null,
        val confidence: Double? = null
    ) : IrPatchOp()

    @Serializable
    @SerialName("add_edge")
    data class AddEdge(val edge: IrEdge) : IrPatchOp()

    @Serializable
    @SerialName("remove_edge")
    data class RemoveEdge(@SerialName("edge_id") val edgeId: String) : IrPatchOp()

    @Serializable
    @SerialName("update_edge")
    data class UpdateEdge(
        @SerialName("edge_id") val edgeId: String,
        val label: String? = null,
        val type: EdgeType? = null,
        val confidence: Double? = null
    ) : IrPatchOp()

    /**
     * Mark an edge as inferred with a (typically lowered) confidence — the critic's main lever.
     *
     * Evidence on the edge is **retained** by this operation for traceability: an edge can be
     * downgraded to [EdgeSourceType.LLM_INFERRED] while still citing the chunk the model leaned on.
     * Retained evidence does *not* re-ground the edge — once the source type is LLM_INFERRED the
     * R010 publish gate is satisfied by visible uncertainty (confidence < 1.0), not by the
     * evidence.
     */
    @Serializable
    @SerialName("mark_inferred")
    data class MarkInferred(
        @SerialName("edge_id") val edgeId: String,
        val confidence: Double
    ) : IrPatchOp()

    /** Adjust a node's confidence (critic confidenceAdjustments). */
    @Serializable
    @SerialName("set_node_confidence")
    data class SetNodeConfidence(
        @SerialName("node_id") val nodeId: String,
        val confidence: Double
    ) : IrPatchOp()

    /** Split one node into several, re-pointing edges to a designated primary by default. */
    @Serializable
    @SerialName("split_node")
    data class SplitNode(
        @SerialName("node_id") val nodeId: String,
        @SerialName("new_nodes") val newNodes: List<IrNode>,
        @SerialName("preserve_evidence") val preserveEvidence: Boolean = true
    ) : IrPatchOp()

    /**
     * Merge several nodes into one canonical node; edges re-point to it.
     *
     * When [dropSelfLoops] is true (the default, preserving historical behavior) any edge that
     * becomes a self-loop after re-pointing (i.e. it connected two of the merged nodes, or a merged
     * node to itself) is removed, since a merged self-edge usually represents an intra-cluster
     * relationship that no longer makes sense as a connector. Set it to false to keep those edges
     * (they will render per the engine's self-loop handling and surface IR-R011 warnings).
     */
    @Serializable
    @SerialName("merge_nodes")
    data class MergeNodes(
        @SerialName("node_ids") val nodeIds: List<String>,
        @SerialName("into") val into: IrNode,
        @SerialName("drop_self_loops") val dropSelfLoops: Boolean = true
    ) : IrPatchOp()

    @Serializable
    @SerialName("set_group")
    data class SetGroup(val group: IrGroup) : IrPatchOp()
}

object IrPatcher {

    /**
     * Result of [applyWithReport]: the patched IR plus the node/edge ids that were dropped as
     * duplicates during the defensive dedup step. Both lists are empty in the common case.
     */
    data class PatchResult(
        val ir: DiagramIR,
        val droppedDuplicateNodeIds: List<String> = emptyList(),
        val droppedDuplicateEdgeIds: List<String> = emptyList()
    ) {
        /** True when the patch produced no duplicate-id collisions that had to be dropped. */
        val isClean: Boolean
            get() = droppedDuplicateNodeIds.isEmpty() && droppedDuplicateEdgeIds.isEmpty()
    }

    /** Applies [patch] to [ir], returning a new IR. Unknown targets are ignored defensively. */
    fun apply(ir: DiagramIR, patch: IrPatch): DiagramIR = applyWithReport(ir, patch).ir

    /**
     * Like [apply], but also reports which node/edge ids were dropped during the defensive dedup
     * step (e.g. a SplitNode/MergeNodes op that collides with an existing id). The returned
     * [PatchResult.ir] is identical to what [apply] would produce; callers that want to surface the
     * collision (instead of letting it pass silently) can inspect the dropped-id lists.
     */
    fun applyWithReport(ir: DiagramIR, patch: IrPatch): PatchResult {
        var nodes = ir.nodes.toMutableList()
        var edges = ir.edges.toMutableList()
        var groups = ir.groups.toMutableList()

        for (op in patch.operations) {
            when (op) {
                is IrPatchOp.AddNode -> nodes.add(op.node.copy(userModified = true))

                is IrPatchOp.RemoveNode -> {
                    nodes = nodes.filterNot { it.id == op.nodeId }.toMutableList()
                    edges =
                        edges
                            .filterNot { it.from == op.nodeId || it.to == op.nodeId }
                            .toMutableList()
                    groups =
                        groups
                            .map { g -> g.copy(nodeIds = g.nodeIds.filterNot { it == op.nodeId }) }
                            .toMutableList()
                }

                is IrPatchOp.UpdateNode ->
                    nodes =
                        nodes
                            .map {
                                if (it.id == op.nodeId) {
                                    it.copy(
                                        label = op.label ?: it.label,
                                        summary = op.summary ?: it.summary,
                                        type = op.type ?: it.type,
                                        confidence = op.confidence ?: it.confidence,
                                        userModified = true
                                    )
                                } else {
                                    it
                                }
                            }
                            .toMutableList()

                is IrPatchOp.AddEdge -> edges.add(op.edge.copy(userModified = true))

                is IrPatchOp.RemoveEdge ->
                    edges = edges.filterNot { it.id == op.edgeId }.toMutableList()

                is IrPatchOp.UpdateEdge ->
                    edges =
                        edges
                            .map {
                                if (it.id == op.edgeId) {
                                    it.copy(
                                        label = op.label ?: it.label,
                                        type = op.type ?: it.type,
                                        confidence = op.confidence ?: it.confidence,
                                        userModified = true
                                    )
                                } else {
                                    it
                                }
                            }
                            .toMutableList()

                is IrPatchOp.MarkInferred ->
                    edges =
                        edges
                            .map {
                                if (it.id == op.edgeId) {
                                    it.copy(
                                        edgeSourceType = EdgeSourceType.LLM_INFERRED,
                                        confidence = op.confidence
                                    )
                                } else {
                                    it
                                }
                            }
                            .toMutableList()

                is IrPatchOp.SetNodeConfidence ->
                    nodes =
                        nodes
                            .map {
                                if (it.id == op.nodeId) it.copy(confidence = op.confidence) else it
                            }
                            .toMutableList()

                is IrPatchOp.SplitNode -> {
                    val index = nodes.indexOfFirst { it.id == op.nodeId }
                    if (index >= 0) {
                        val original = nodes[index]
                        val replacements =
                            op.newNodes.map {
                                if (op.preserveEvidence && it.evidence.isEmpty()) {
                                    it.copy(evidence = original.evidence, userModified = true)
                                } else {
                                    it.copy(userModified = true)
                                }
                            }
                        nodes.removeAt(index)
                        nodes.addAll(index, replacements)
                        val primary = replacements.firstOrNull()?.id
                        if (primary != null) {
                            edges =
                                edges
                                    .map { e ->
                                        e.copy(
                                            from = if (e.from == op.nodeId) primary else e.from,
                                            to = if (e.to == op.nodeId) primary else e.to
                                        )
                                    }
                                    .toMutableList()
                        }
                    }
                }

                is IrPatchOp.MergeNodes -> {
                    val idSet = op.nodeIds.toSet()
                    nodes = nodes.filterNot { it.id in idSet }.toMutableList()
                    nodes.add(op.into.copy(userModified = true))
                    val repointed = edges.map { e ->
                        e.copy(
                            from = if (e.from in idSet) op.into.id else e.from,
                            to = if (e.to in idSet) op.into.id else e.to
                        )
                    }
                    edges =
                        (
                            if (op.dropSelfLoops) {
                                repointed.filterNot { it.from == it.to }
                            } else {
                                repointed
                            }
                            )
                            .toMutableList()
                    groups =
                        groups
                            .map { g ->
                                g.copy(
                                    nodeIds =
                                    g.nodeIds
                                        .map { if (it in idSet) op.into.id else it }
                                        .distinct()
                                )
                            }
                            .toMutableList()
                }

                is IrPatchOp.SetGroup -> {
                    val index = groups.indexOfFirst { it.id == op.group.id }
                    if (index >= 0) groups[index] = op.group else groups.add(op.group)
                }
            }
        }

        // Defensive: a SplitNode/MergeNode op could introduce duplicate ids; keep the first of each
        // so the result never violates IR-R002/R003. The dropped ids are reported so callers using
        // applyWithReport can surface the collision instead of having it pass silently; plain apply
        // and applyAndValidate discard the report and behave exactly as before.
        val dedupedNodes = nodes.distinctBy { it.id }
        val dedupedEdges = edges.distinctBy { it.id }
        val droppedNodeIds = diffDuplicates(nodes.map { it.id }, dedupedNodes.size)
        val droppedEdgeIds = diffDuplicates(edges.map { it.id }, dedupedEdges.size)
        return PatchResult(
            ir = ir.copy(nodes = dedupedNodes, edges = dedupedEdges, groups = groups),
            droppedDuplicateNodeIds = droppedNodeIds,
            droppedDuplicateEdgeIds = droppedEdgeIds
        )
    }

    /**
     * Returns the ids that [List.distinctBy] would drop (each later occurrence of a repeated id).
     */
    private fun diffDuplicates(allIds: List<String>, distinctCount: Int): List<String> {
        if (allIds.size == distinctCount) return emptyList()
        val seen = HashSet<String>()
        val dropped = ArrayList<String>()
        for (id in allIds) {
            if (!seen.add(id)) dropped.add(id)
        }
        return dropped
    }

    /**
     * Applies [patch] and runs [IrValidator], returning both the new IR and its validation report.
     */
    fun applyAndValidate(
        ir: DiagramIR,
        patch: IrPatch,
        validator: IrValidator = IrValidator()
    ): Pair<DiagramIR, ValidationReport> {
        val next = apply(ir, patch)
        return next to validator.validate(next)
    }
}
