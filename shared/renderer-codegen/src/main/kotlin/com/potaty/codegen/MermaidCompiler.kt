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
import com.potaty.ir.LayoutDirection
import com.potaty.ir.NodeType

/**
 * Compiles a [DiagramIR] into Mermaid source.
 *
 * Mapping of [DiagramType] to Mermaid diagram kind:
 *  - SEQUENCE                                 -> `sequenceDiagram`
 *  - ER                                       -> `erDiagram`
 *  - STATE                                    -> `stateDiagram-v2`
 *  - everything else (architecture/flow/...)  -> `flowchart` (`graph TD`/`graph LR` per direction)
 *
 * Security: output is *securityLevel-safe*. We never emit HTML labels and never emit `click`/`href`
 * directives, so the consuming app can render with `securityLevel: 'strict'`. All label text is run
 * through [LabelEscaper] and wrapped in quotes.
 *
 * Identifiers: a single [IdentAllocator] per compile maps IR ids to unique, collision-safe node
 * identifiers (groups + nodes are allocated before edges so endpoints reference the same identifier).
 *
 * Confidence: edges that are inferred (`LLM_INFERRED`) or low-confidence (confidence < 1.0 and not
 * a grounded source) are drawn with the dotted `-.->` connector so uncertainty is visible.
 */
object MermaidCompiler {

    fun compile(ir: DiagramIR): String = when (ir.diagramType) {
        DiagramType.SEQUENCE -> sequence(ir)
        DiagramType.ER -> er(ir)
        DiagramType.STATE -> state(ir)
        else -> flowchart(ir)
    }

    // ---------------------------------------------------------------- flowchart

    private fun flowchart(ir: DiagramIR): String {
        val ids = IdentAllocator()
        val dir = when (ir.layoutHints.direction) {
            LayoutDirection.TB -> "TD"
            LayoutDirection.BT -> "BT"
            LayoutDirection.LR -> "LR"
            LayoutDirection.RL -> "RL"
        }
        val sb = StringBuilder()
        sb.append("flowchart ").append(dir).append('\n')

        val grouped = HashSet<String>()
        for (group in ir.groups) {
            sb.append("  subgraph ")
                .append(ids.identify(group.id))
                .append(" [\"")
                .append(LabelEscaper.mermaid(group.label))
                .append("\"]\n")
            for (nid in group.nodeIds) {
                val node = ir.nodes.firstOrNull { it.id == nid } ?: continue
                if (grouped.add(nid)) {
                    sb.append("    ").append(nodeDecl(node, ids)).append('\n')
                }
            }
            sb.append("  end\n")
        }
        // ungrouped nodes
        for (node in ir.nodes) {
            if (node.id !in grouped) {
                sb.append("  ").append(nodeDecl(node, ids)).append('\n')
            }
        }

        for (edge in ir.edges) {
            sb.append("  ").append(edgeLine(edge, ir.styleHints.showEdgeLabels, ids)).append('\n')
        }
        return sb.toString().trimEnd('\n')
    }

    /** A node declaration `id["label"]` using a shape that hints at the node type. */
    private fun nodeDecl(node: IrNode, ids: IdentAllocator): String {
        val id = ids.identify(node.id)
        val label = LabelEscaper.mermaid(node.label)
        val (open, close) = shapeBrackets(node.type)
        return "$id$open\"$label\"$close"
    }

    /**
     * Pick a Mermaid node shape from the node type. We stay within the safe ASCII shape syntax and
     * deliberately avoid the rarely-supported shapes.
     */
    private fun shapeBrackets(type: NodeType): Pair<String, String> = when (type) {
        NodeType.DATABASE, NodeType.STORAGE, NodeType.CACHE -> "[(" to ")]" // cylinder
        NodeType.QUEUE, NodeType.TOPIC -> "[[" to "]]" // subroutine box
        NodeType.DECISION -> "{" to "}" // rhombus
        NodeType.EVENT, NodeType.MILESTONE -> "((" to "))" // circle
        NodeType.USER, NodeType.ACTOR, NodeType.PERSON -> "([" to "])" // stadium
        NodeType.EXTERNAL_SERVICE, NodeType.GATEWAY -> "[/" to "/]" // parallelogram
        else -> "[" to "]" // rectangle
    }

    private fun edgeLine(edge: IrEdge, showLabels: Boolean, ids: IdentAllocator): String {
        val from = ids.identify(edge.from)
        val to = ids.identify(edge.to)
        val dotted = isUncertain(edge)
        val label = edge.label?.takeIf { it.isNotBlank() && showLabels }
        return if (label != null) {
            val esc = LabelEscaper.mermaid(label)
            if (dotted) "$from -.->|\"$esc\"| $to" else "$from -->|\"$esc\"| $to"
        } else {
            if (dotted) "$from -.-> $to" else "$from --> $to"
        }
    }

    /** Inferred or low-confidence (non-grounded) edges are drawn dotted. */
    private fun isUncertain(edge: IrEdge): Boolean =
        edge.edgeSourceType == EdgeSourceType.LLM_INFERRED ||
            (!edge.edgeSourceType.isGrounded && edge.confidence < 1.0)

    // ---------------------------------------------------------------- sequence

    private fun sequence(ir: DiagramIR): String {
        val ids = IdentAllocator()
        val sb = StringBuilder()
        sb.append("sequenceDiagram\n")
        for (node in ir.nodes) {
            val keyword = if (node.type == NodeType.USER || node.type == NodeType.ACTOR ||
                node.type == NodeType.PERSON
            ) {
                "actor"
            } else {
                "participant"
            }
            sb.append("  ").append(keyword).append(' ')
                .append(ids.identify(node.id))
                .append(" as ")
                .append(LabelEscaper.mermaid(node.label))
                .append('\n')
        }
        for (edge in ir.edges) {
            val from = ids.identify(edge.from)
            val to = ids.identify(edge.to)
            // dotted (return) arrow for response edges and inferred edges; solid otherwise.
            val arrow = if (edge.type == EdgeType.RESPONSE || isUncertain(edge)) "-->>" else "->>"
            val label = edge.label?.takeIf { it.isNotBlank() } ?: edge.type.name.lowercase()
            sb.append("  ").append(from).append(arrow).append(to)
                .append(": ").append(LabelEscaper.mermaid(label)).append('\n')
        }
        return sb.toString().trimEnd('\n')
    }

    // ---------------------------------------------------------------- er

    private fun er(ir: DiagramIR): String {
        val ids = IdentAllocator()
        // Allocate entity identifiers in node-declaration order so edges reference the same ids
        // and output is deterministic regardless of edge order.
        ir.nodes.forEach { ids.identify(it.id) }
        val sb = StringBuilder()
        sb.append("erDiagram\n")
        for (edge in ir.edges) {
            val from = ids.identify(edge.from)
            val to = ids.identify(edge.to)
            // Map relation cardinality from edge type; default to a non-identifying many-to-one.
            // Use direct enum comparison (not edge.type.name string matching) so a rename of an
            // EdgeType member is caught by the compiler instead of silently falling through.
            val cardinality = when (edge.type) {
                EdgeType.HAS_MANY -> "||--o{"
                EdgeType.HAS_ONE -> "||--||"
                EdgeType.REFERENCES, EdgeType.DEPENDS_ON -> "}o--||"
                else -> "||--o{"
            }
            val label = (edge.label?.takeIf { it.isNotBlank() } ?: edge.type.name.lowercase())
                .let { LabelEscaper.mermaid(it) }
            sb.append("  ").append(from).append(' ').append(cardinality).append(' ')
                .append(to).append(" : \"").append(label).append("\"\n")
        }
        // Entities with no relations still need to appear.
        val referenced = ir.edges.flatMap { listOf(it.from, it.to) }.toSet()
        for (node in ir.nodes) {
            if (node.id !in referenced) {
                sb.append("  ").append(ids.identify(node.id)).append(" {\n  }\n")
            }
        }
        return sb.toString().trimEnd('\n')
    }

    // ---------------------------------------------------------------- state

    private fun state(ir: DiagramIR): String {
        val ids = IdentAllocator()
        val sb = StringBuilder()
        sb.append("stateDiagram-v2\n")
        for (node in ir.nodes) {
            sb.append("  ").append(ids.identify(node.id))
                .append(" : ").append(LabelEscaper.mermaid(node.label)).append('\n')
        }
        for (edge in ir.edges) {
            val from = ids.identify(edge.from)
            val to = ids.identify(edge.to)
            val label = edge.label?.takeIf { it.isNotBlank() }
            sb.append("  ").append(from).append(" --> ").append(to)
            if (label != null) sb.append(" : ").append(LabelEscaper.mermaid(label))
            sb.append('\n')
        }
        return sb.toString().trimEnd('\n')
    }
}
