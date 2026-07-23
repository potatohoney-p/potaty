/*
 * Copyright (c) 2026, Potaty
 */

package com.potaty.codegen

import com.potaty.ir.DiagramIR
import com.potaty.ir.EdgeSourceType
import com.potaty.ir.IrEdge
import com.potaty.ir.IrNode
import com.potaty.ir.LayoutDirection

/**
 * Compiles a [DiagramIR] into D2 (https://d2lang.com) source.
 *
 * Structure:
 *  - each [com.potaty.ir.IrGroup] becomes a D2 container (`group: "label" { ... }`) holding its
 *    member nodes, which are then referenced with their fully-qualified container path on edges;
 *  - ungrouped nodes are declared at the top level;
 *  - edges are `a -> b: label`.
 *
 * Direction is set via the `direction:` board attribute. Inferred / low-confidence edges get a
 * dashed style so uncertainty stays visible. All text is escaped via [LabelEscaper], and a single
 * [IdentAllocator] guarantees group/node identifiers are unique and that edge endpoints resolve to
 * the exact same (container-qualified) path used at declaration.
 */
object D2Compiler {

    fun compile(ir: DiagramIR): String {
        val ids = IdentAllocator()
        val sb = StringBuilder()

        sb.append("direction: ").append(direction(ir.layoutHints.direction)).append('\n')

        // Map node id -> the D2 path used to reference it (container-qualified when grouped).
        val pathById = HashMap<String, String>()

        val grouped = HashSet<String>()
        for (group in ir.groups) {
            val gid = ids.identify(group.id)
            sb.append(gid).append(": \"").append(LabelEscaper.d2(group.label)).append("\" {\n")
            for (nid in group.nodeIds) {
                val node = ir.nodes.firstOrNull { it.id == nid } ?: continue
                if (grouped.add(nid)) {
                    val local = ids.identify(node.id)
                    sb.append("  ").append(nodeDecl(node, local)).append('\n')
                    pathById[nid] = "$gid.$local"
                }
            }
            sb.append("}\n")
        }

        for (node in ir.nodes) {
            if (node.id !in grouped) {
                val local = ids.identify(node.id)
                sb.append(nodeDecl(node, local)).append('\n')
                pathById[node.id] = local
            }
        }

        for (edge in ir.edges) {
            sb.append(edgeLine(edge, pathById, ir.styleHints.showEdgeLabels, ids)).append('\n')
        }

        return sb.toString().trimEnd('\n')
    }

    private fun direction(dir: LayoutDirection): String = when (dir) {
        LayoutDirection.TB -> "down"
        LayoutDirection.BT -> "up"
        LayoutDirection.LR -> "right"
        LayoutDirection.RL -> "left"
    }

    private fun nodeDecl(node: IrNode, id: String): String {
        val label = LabelEscaper.d2(node.label)
        val shape = d2Shape(node.type.name)
        return if (shape != null) {
            "$id: \"$label\" { shape: $shape }"
        } else {
            "$id: \"$label\""
        }
    }

    private fun d2Shape(typeName: String): String? = when (typeName) {
        "DATABASE", "STORAGE" -> "cylinder"
        "CACHE" -> "stored_data"
        "QUEUE", "TOPIC" -> "queue"
        "DECISION" -> "diamond"
        "USER", "ACTOR", "PERSON" -> "person"
        "EVENT", "MILESTONE" -> "circle"
        "EXTERNAL_SERVICE", "GATEWAY" -> "hexagon"
        else -> null // default rectangle
    }

    private fun edgeLine(
        edge: IrEdge,
        pathById: Map<String, String>,
        showLabels: Boolean,
        ids: IdentAllocator
    ): String {
        val from = pathById[edge.from] ?: ids.identify(edge.from)
        val to = pathById[edge.to] ?: ids.identify(edge.to)
        val sb = StringBuilder()
        sb.append(from).append(" -> ").append(to)
        val label = edge.label?.takeIf { it.isNotBlank() && showLabels }
        if (label != null) {
            sb.append(": \"").append(LabelEscaper.d2(label)).append("\"")
        }
        if (isUncertain(edge)) {
            // attribute block applies to the edge; dashed stroke signals inference.
            sb.append(" { style.stroke-dash: 3 }")
        }
        return sb.toString()
    }

    private fun isUncertain(edge: IrEdge): Boolean =
        edge.edgeSourceType == EdgeSourceType.LLM_INFERRED ||
            (!edge.edgeSourceType.isGrounded && edge.confidence < 1.0)
}
