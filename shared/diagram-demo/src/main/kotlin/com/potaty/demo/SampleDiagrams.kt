/*
 * Copyright (c) 2026, Potaty
 */

package com.potaty.demo

import com.potaty.ir.DiagramIR
import com.potaty.ir.DiagramScope
import com.potaty.ir.DiagramType
import com.potaty.ir.EdgeSourceType
import com.potaty.ir.EvidenceRef
import com.potaty.ir.IrEdge
import com.potaty.ir.IrGroup
import com.potaty.ir.IrNode
import com.potaty.ir.LayoutDirection
import com.potaty.ir.LayoutHints
import com.potaty.ir.NodeType
import com.potaty.ir.StyleHints

/**
 * Hand-written IR fixtures that mirror the diagrams in `output_sample/`. They double as the source
 * for golden ASCII fixtures and as a manual aesthetic check ("does generated ASCII match the moat?").
 */
object SampleDiagrams {

    fun all(): List<Pair<String, DiagramIR>> = listOf(
        "Repository Architecture (containers)" to architecture(),
        "Report Page Navigation Bug (flowchart)" to flowchart(),
        "Client / Server Handshake (sequence)" to sequence()
    )

    private fun ev(chunk: String, path: String? = null, line: Int? = null) =
        EvidenceRef(sourceChunkId = chunk, path = path, startLine = line, endLine = line)

    // Mirrors output_sample/sample2 "the result": labelled dashed regions of boxes.
    private fun architecture(): DiagramIR = DiagramIR(
        diagramId = "dia_arch",
        title = "Database Engine Architecture",
        objective = "Explain the network interface and relational engine for a new engineer.",
        diagramType = DiagramType.CONTAINER,
        scope = DiagramScope(abstractionLevel = com.potaty.ir.AbstractionLevel.MEDIUM),
        layoutHints = LayoutHints(direction = LayoutDirection.TB, maxNodesPerGroup = 2),
        styleHints = StyleHints(styleProfile = "potaty-clean"),
        nodes = listOf(
            IrNode(
                "cluster",
                label = "Cluster Communication",
                type = NodeType.SERVICE,
                confidence = 0.9,
                evidence = listOf(ev("chk_net1", "src/net/cluster.kt", 12))
            ),
            IrNode(
                "client",
                label = "Client Communication",
                type = NodeType.SERVICE,
                confidence = 0.92,
                evidence = listOf(ev("chk_net2", "src/net/client.kt", 8))
            ),
            IrNode(
                "parser",
                label = "Command Parser",
                type = NodeType.COMPONENT,
                confidence = 0.88,
                evidence = listOf(ev("chk_eng1", "src/engine/parser.kt", 20))
            ),
            IrNode(
                "optimizer",
                label = "Query Optimizer",
                type = NodeType.COMPONENT,
                confidence = 0.85,
                evidence = listOf(ev("chk_eng2", "src/engine/optimizer.kt", 34))
            ),
            IrNode(
                "executor",
                label = "Query Executor",
                type = NodeType.COMPONENT,
                confidence = 0.86,
                evidence = listOf(ev("chk_eng3", "src/engine/executor.kt", 51))
            )
        ),
        groups = listOf(
            IrGroup("g_net", "Network Interface", listOf("cluster", "client")),
            IrGroup("g_engine", "Relational Engine", listOf("parser", "optimizer", "executor"))
        ),
        edges = listOf(
            IrEdge(
                "e1",
                from = "client",
                to = "parser",
                type = com.potaty.ir.EdgeType.REQUEST,
                label = "submit",
                edgeSourceType = EdgeSourceType.EXPLICIT_CALL,
                evidence = listOf(ev("chk_eng1", "src/engine/parser.kt", 22))
            ),
            IrEdge(
                "e2",
                from = "parser",
                to = "optimizer",
                type = com.potaty.ir.EdgeType.CALLS,
                edgeSourceType = EdgeSourceType.STATIC_IMPORT,
                evidence = listOf(ev("chk_eng2", "src/engine/optimizer.kt", 36))
            ),
            IrEdge(
                "e3",
                from = "optimizer",
                to = "executor",
                type = com.potaty.ir.EdgeType.CALLS,
                edgeSourceType = EdgeSourceType.STATIC_IMPORT,
                evidence = listOf(ev("chk_eng3", "src/engine/executor.kt", 53))
            )
        )
    )

    // Mirrors the plan's worked example (section 13.3): report page navigation bug.
    private fun flowchart(): DiagramIR = DiagramIR(
        diagramId = "dia_flow",
        title = "Report Page Navigation Bug",
        diagramType = DiagramType.FLOWCHART,
        layoutHints = LayoutHints(direction = LayoutDirection.TB),
        styleHints = StyleHints(styleProfile = "potaty-rounded"),
        nodes = listOf(
            IrNode(
                "running",
                label = "Running Page",
                type = NodeType.FRONTEND,
                evidence = listOf(ev("chk_doc1", line = 1))
            ),
            IrNode(
                "embryo",
                label = "Embryo View Button",
                type = NodeType.COMPONENT,
                evidence = listOf(ev("chk_doc1", line = 1))
            ),
            IrNode(
                "timeline",
                label = "Timeline Page",
                type = NodeType.FRONTEND,
                evidence = listOf(ev("chk_doc1", line = 1))
            ),
            IrNode(
                "report",
                label = "Report Page",
                type = NodeType.FRONTEND,
                evidence = listOf(ev("chk_doc2", line = 2))
            ),
            IrNode(
                "bug",
                label = "Infinite Loading Issue",
                type = NodeType.RISK,
                confidence = 0.95,
                evidence = listOf(ev("chk_doc2", line = 2))
            )
        ),
        edges = listOf(
            IrEdge(
                "f1",
                from = "running",
                to = "embryo",
                type = com.potaty.ir.EdgeType.NAVIGATES_TO,
                edgeSourceType = EdgeSourceType.DOCUMENT_STATEMENT,
                evidence = listOf(ev("chk_doc1", line = 1))
            ),
            IrEdge(
                "f2",
                from = "embryo",
                to = "timeline",
                type = com.potaty.ir.EdgeType.NAVIGATES_TO,
                edgeSourceType = EdgeSourceType.DOCUMENT_STATEMENT,
                evidence = listOf(ev("chk_doc1", line = 1))
            ),
            IrEdge(
                "f3",
                from = "embryo",
                to = "report",
                type = com.potaty.ir.EdgeType.NAVIGATES_TO,
                edgeSourceType = EdgeSourceType.DOCUMENT_STATEMENT,
                evidence = listOf(ev("chk_doc2", line = 2))
            ),
            IrEdge(
                "f4",
                from = "report",
                to = "bug",
                label = "currently",
                type = com.potaty.ir.EdgeType.RELATES_TO,
                edgeSourceType = EdgeSourceType.DOCUMENT_STATEMENT,
                evidence = listOf(ev("chk_doc2", line = 2))
            )
        )
    )

    // Mirrors output_sample/sample1 top: Client/Server interaction.
    private fun sequence(): DiagramIR = DiagramIR(
        diagramId = "dia_seq",
        title = "Client / Server Communication",
        diagramType = DiagramType.SEQUENCE,
        styleHints = StyleHints(styleProfile = "potaty-clean"),
        nodes = listOf(
            IrNode(
                "client",
                label = "Client",
                type = NodeType.ACTOR,
                evidence = listOf(ev("chk_seq", line = 1))
            ),
            IrNode(
                "server",
                label = "Server",
                type = NodeType.BACKEND,
                evidence = listOf(ev("chk_seq", line = 1))
            )
        ),
        edges = listOf(
            IrEdge(
                "m1",
                from = "client",
                to = "server",
                label = "SYN",
                edgeSourceType = EdgeSourceType.DOCUMENT_STATEMENT,
                evidence = listOf(ev("chk_seq", line = 2))
            ),
            IrEdge(
                "m2",
                from = "server",
                to = "client",
                label = "ACK",
                edgeSourceType = EdgeSourceType.DOCUMENT_STATEMENT,
                evidence = listOf(ev("chk_seq", line = 3))
            ),
            IrEdge(
                "m3",
                from = "client",
                to = "server",
                label = "ClientHello",
                edgeSourceType = EdgeSourceType.DOCUMENT_STATEMENT,
                evidence = listOf(ev("chk_seq", line = 4))
            ),
            IrEdge(
                "m4",
                from = "server",
                to = "client",
                label = "Certificate",
                edgeSourceType = EdgeSourceType.DOCUMENT_STATEMENT,
                evidence = listOf(ev("chk_seq", line = 5))
            ),
            IrEdge(
                "m5",
                from = "client",
                to = "server",
                label = "HTTP GET",
                edgeSourceType = EdgeSourceType.DOCUMENT_STATEMENT,
                evidence = listOf(ev("chk_seq", line = 6))
            ),
            IrEdge(
                "m6",
                from = "server",
                to = "client",
                label = "HTTP Response",
                edgeSourceType = EdgeSourceType.DOCUMENT_STATEMENT,
                evidence = listOf(ev("chk_seq", line = 7))
            )
        )
    )
}
