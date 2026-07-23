/*
 * Copyright (c) 2026, Potaty
 */

package com.potaty.codegen

import com.potaty.ir.DiagramIR
import com.potaty.ir.DiagramType
import com.potaty.ir.EdgeSourceType
import com.potaty.ir.EdgeType
import com.potaty.ir.IrEdge
import com.potaty.ir.IrNode
import com.potaty.ir.NodeType

/**
 * Compiles a [DiagramIR] into PlantUML source.
 *
 * Diagram-kind mapping:
 *  - SEQUENCE                                  -> a PlantUML sequence diagram (participants + msgs)
 *  - ARCHITECTURE / CONTAINER / everything else -> a component diagram (components, databases,
 *    queues, actors) with `package`s for groups.
 *
 * All text is escaped via [LabelEscaper]; a single [IdentAllocator] per compile gives every element
 * a unique alias so distinct ids never merge; inferred/low-confidence edges use a dotted connector.
 */
object PlantUmlCompiler {

    fun compile(ir: DiagramIR): String = when (ir.diagramType) {
        DiagramType.SEQUENCE -> sequence(ir)
        else -> component(ir)
    }

    // ---------------------------------------------------------------- component

    private fun component(ir: DiagramIR): String {
        val ids = IdentAllocator()
        val sb = StringBuilder()
        sb.append("@startuml\n")
        sb.append(
            "' AI-generated component diagram — verify against source before relying on it.\n"
        )

        val grouped = HashSet<String>()
        for (group in ir.groups) {
            sb.append("package \"").append(LabelEscaper.plantUml(group.label)).append("\" {\n")
            for (nid in group.nodeIds) {
                val node = ir.nodes.firstOrNull { it.id == nid } ?: continue
                if (grouped.add(nid)) {
                    sb.append("  ").append(nodeDecl(node, ids)).append('\n')
                }
            }
            sb.append("}\n")
        }
        for (node in ir.nodes) {
            if (node.id !in grouped) {
                sb.append(nodeDecl(node, ids)).append('\n')
            }
        }

        for (edge in ir.edges) {
            sb.append(edgeLine(edge, ir.styleHints.showEdgeLabels, ids)).append('\n')
        }

        sb.append("@enduml")
        return sb.toString()
    }

    /** A PlantUML element declaration keyed by node type, e.g. `database "X" as id`. */
    private fun nodeDecl(node: IrNode, ids: IdentAllocator): String {
        val id = ids.identify(node.id)
        val label = LabelEscaper.plantUml(node.label)
        val keyword = when (node.type) {
            NodeType.DATABASE, NodeType.STORAGE, NodeType.CACHE -> "database"
            NodeType.QUEUE, NodeType.TOPIC -> "queue"
            NodeType.USER, NodeType.ACTOR, NodeType.PERSON -> "actor"
            NodeType.EXTERNAL_SERVICE, NodeType.GATEWAY -> "cloud"
            NodeType.FRONTEND -> "node"
            else -> "component"
        }
        return "$keyword \"$label\" as $id"
    }

    private fun edgeLine(edge: IrEdge, showLabels: Boolean, ids: IdentAllocator): String {
        val from = ids.identify(edge.from)
        val to = ids.identify(edge.to)
        val connector = if (isUncertain(edge)) "..>" else "-->"
        val label = edge.label?.takeIf { it.isNotBlank() && showLabels }
        return if (label != null) {
            "$from $connector $to : ${LabelEscaper.plantUml(label)}"
        } else {
            "$from $connector $to"
        }
    }

    // ---------------------------------------------------------------- sequence

    private fun sequence(ir: DiagramIR): String {
        val ids = IdentAllocator()
        val sb = StringBuilder()
        sb.append("@startuml\n")
        sb.append("' AI-generated sequence diagram — verify against source before relying on it.\n")
        for (node in ir.nodes) {
            val keyword = if (node.type == NodeType.USER || node.type == NodeType.ACTOR ||
                node.type == NodeType.PERSON
            ) {
                "actor"
            } else {
                "participant"
            }
            sb.append(keyword).append(" \"")
                .append(LabelEscaper.plantUml(node.label))
                .append("\" as ").append(ids.identify(node.id)).append('\n')
        }
        for (edge in ir.edges) {
            val from = ids.identify(edge.from)
            val to = ids.identify(edge.to)
            val arrow = if (edge.type == EdgeType.RESPONSE || isUncertain(edge)) "-->" else "->"
            val label = edge.label?.takeIf { it.isNotBlank() } ?: edge.type.name.lowercase()
            sb.append(from).append(' ').append(arrow).append(' ').append(to)
                .append(" : ").append(LabelEscaper.plantUml(label)).append('\n')
        }
        sb.append("@enduml")
        return sb.toString()
    }

    private fun isUncertain(edge: IrEdge): Boolean =
        edge.edgeSourceType == EdgeSourceType.LLM_INFERRED ||
            (!edge.edgeSourceType.isGrounded && edge.confidence < 1.0)
}
