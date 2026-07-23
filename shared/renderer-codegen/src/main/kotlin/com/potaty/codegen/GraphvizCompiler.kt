/*
 * Copyright (c) 2026, Potaty
 */

package com.potaty.codegen

import com.potaty.ir.DiagramIR
import com.potaty.ir.EdgeSourceType
import com.potaty.ir.IrEdge
import com.potaty.ir.IrNode
import com.potaty.ir.LayoutDirection
import com.potaty.ir.NodeType

/**
 * Compiles a [DiagramIR] into Graphviz DOT.
 *
 * Output is a `digraph` with:
 *  - `rankdir` derived from [com.potaty.ir.LayoutDirection];
 *  - one `subgraph cluster_<id>` per [com.potaty.ir.IrGroup];
 *  - node shapes hinted from [NodeType];
 *  - inferred / low-confidence edges drawn dashed.
 *
 * All labels are escaped via [LabelEscaper]; a single [IdentAllocator] per compile makes node
 * identifiers unique so distinct ids never merge or misattribute edges.
 */
object GraphvizCompiler {

    fun compile(ir: DiagramIR): String {
        val ids = IdentAllocator()
        val sb = StringBuilder()
        sb.append("digraph G {\n")
        sb.append("  rankdir=").append(rankdir(ir.layoutHints.direction)).append(";\n")
        sb.append("  node [shape=box];\n")

        val grouped = HashSet<String>()
        for ((index, group) in ir.groups.withIndex()) {
            sb.append("  subgraph cluster_").append(index).append(" {\n")
            sb.append("    label=\"").append(LabelEscaper.dot(group.label)).append("\";\n")
            for (nid in group.nodeIds) {
                val node = ir.nodes.firstOrNull { it.id == nid } ?: continue
                if (grouped.add(nid)) {
                    sb.append("    ").append(nodeDecl(node, ids)).append('\n')
                }
            }
            sb.append("  }\n")
        }
        for (node in ir.nodes) {
            if (node.id !in grouped) {
                sb.append("  ").append(nodeDecl(node, ids)).append('\n')
            }
        }

        for (edge in ir.edges) {
            sb.append("  ").append(edgeLine(edge, ir.styleHints.showEdgeLabels, ids)).append('\n')
        }

        sb.append("}")
        return sb.toString()
    }

    private fun rankdir(dir: LayoutDirection): String = when (dir) {
        LayoutDirection.TB -> "TB"
        LayoutDirection.BT -> "BT"
        LayoutDirection.LR -> "LR"
        LayoutDirection.RL -> "RL"
    }

    private fun nodeDecl(node: IrNode, ids: IdentAllocator): String {
        val id = ids.identify(node.id)
        val label = LabelEscaper.dot(node.label)
        val shape = dotShape(node.type)
        return "\"$id\" [label=\"$label\", shape=$shape];"
    }

    private fun dotShape(type: NodeType): String = when (type) {
        NodeType.DATABASE, NodeType.STORAGE, NodeType.CACHE -> "cylinder"
        NodeType.QUEUE, NodeType.TOPIC -> "box3d"
        NodeType.DECISION -> "diamond"
        NodeType.EVENT, NodeType.MILESTONE -> "circle"
        NodeType.USER, NodeType.ACTOR, NodeType.PERSON -> "ellipse"
        NodeType.EXTERNAL_SERVICE, NodeType.GATEWAY -> "hexagon"
        else -> "box"
    }

    private fun edgeLine(edge: IrEdge, showLabels: Boolean, ids: IdentAllocator): String {
        val from = ids.identify(edge.from)
        val to = ids.identify(edge.to)
        val attrs = mutableListOf<String>()
        val label = edge.label?.takeIf { it.isNotBlank() && showLabels }
        if (label != null) attrs += "label=\"${LabelEscaper.dot(label)}\""
        if (isUncertain(edge)) attrs += "style=dashed"
        val attrStr = if (attrs.isEmpty()) "" else " [${attrs.joinToString(", ")}]"
        return "\"$from\" -> \"$to\"$attrStr;"
    }

    private fun isUncertain(edge: IrEdge): Boolean =
        edge.edgeSourceType == EdgeSourceType.LLM_INFERRED ||
            (!edge.edgeSourceType.isGrounded && edge.confidence < 1.0)
}
