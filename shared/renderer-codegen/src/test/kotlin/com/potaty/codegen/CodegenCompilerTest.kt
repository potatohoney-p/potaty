/*
 * Copyright (c) 2026, Potaty
 */

package com.potaty.codegen

import com.potaty.ir.DiagramIR
import com.potaty.ir.DiagramType
import com.potaty.ir.EdgeSourceType
import com.potaty.ir.EdgeType
import com.potaty.ir.EvidenceRef
import com.potaty.ir.IrEdge
import com.potaty.ir.IrGroup
import com.potaty.ir.IrNode
import com.potaty.ir.LayoutDirection
import com.potaty.ir.LayoutHints
import com.potaty.ir.NodeType
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Per-format substring assertions for the IR -> diagram-as-code compilers. Each test builds a tiny
 * IR and checks that the key structural substrings appear (and, for security, that dangerous
 * constructs do NOT appear).
 */
class CodegenCompilerTest {

    private fun ev() = EvidenceRef(sourceChunkId = "chk")

    private fun node(
        id: String,
        label: String = id,
        type: NodeType = NodeType.GENERIC
    ) = IrNode(id = id, label = label, type = type, evidence = listOf(ev()))

    /** A small architecture IR: a frontend -> backend -> database, grounded, LR. */
    private fun archIr(): DiagramIR = DiagramIR(
        diagramId = "d1",
        title = "Sample Service",
        objective = "How requests flow.",
        diagramType = DiagramType.ARCHITECTURE,
        nodes = listOf(
            node("web", "Web App", NodeType.FRONTEND),
            node("api", "API Service", NodeType.BACKEND),
            node("db", "Postgres", NodeType.DATABASE)
        ),
        edges = listOf(
            IrEdge(
                id = "e1",
                from = "web",
                to = "api",
                type = EdgeType.CALLS,
                label = "HTTP",
                edgeSourceType = EdgeSourceType.EXPLICIT_CALL,
                evidence = listOf(ev())
            ),
            IrEdge(
                id = "e2",
                from = "api",
                to = "db",
                type = EdgeType.READS_WRITES,
                label = "SQL",
                edgeSourceType = EdgeSourceType.LLM_INFERRED,
                confidence = 0.5
            )
        ),
        groups = listOf(IrGroup(id = "backend", label = "Backend", nodeIds = listOf("api", "db"))),
        layoutHints = LayoutHints(direction = LayoutDirection.LR)
    )

    private fun sequenceIr(): DiagramIR = DiagramIR(
        diagramId = "d2",
        title = "Login Flow",
        diagramType = DiagramType.SEQUENCE,
        nodes = listOf(
            node("user", "User", NodeType.USER),
            node("server", "Server", NodeType.BACKEND)
        ),
        edges = listOf(
            IrEdge(
                id = "m1",
                from = "user",
                to = "server",
                type = EdgeType.REQUEST,
                label = "POST /login",
                edgeSourceType = EdgeSourceType.DOCUMENT_STATEMENT,
                evidence = listOf(ev())
            ),
            IrEdge(
                id = "m2",
                from = "server",
                to = "user",
                type = EdgeType.RESPONSE,
                label = "200 OK",
                edgeSourceType = EdgeSourceType.DOCUMENT_STATEMENT,
                evidence = listOf(ev())
            )
        )
    )

    private fun erIr(): DiagramIR = DiagramIR(
        diagramId = "d3",
        title = "Schema",
        diagramType = DiagramType.ER,
        nodes = listOf(
            node("USER", "USER", NodeType.ENTITY),
            node("ORDER", "ORDER", NodeType.ENTITY)
        ),
        edges = listOf(
            IrEdge(
                id = "r1",
                from = "USER",
                to = "ORDER",
                type = EdgeType.HAS_MANY,
                label = "places",
                edgeSourceType = EdgeSourceType.DATABASE_RELATION,
                evidence = listOf(ev())
            )
        )
    )

    private fun stateIr(): DiagramIR = DiagramIR(
        diagramId = "d4",
        title = "Order State",
        diagramType = DiagramType.STATE,
        nodes = listOf(node("draft", "Draft"), node("placed", "Placed")),
        edges = listOf(
            IrEdge(
                id = "t1",
                from = "draft",
                to = "placed",
                type = EdgeType.PRECEDES,
                label = "submit",
                edgeSourceType = EdgeSourceType.DOCUMENT_STATEMENT,
                evidence = listOf(ev())
            )
        )
    )

    // ---------------------------------------------------------------- Mermaid

    @Test
    fun mermaidFlowchartHasKindDirectionNodesEdgesAndSubgraph() {
        val out = MermaidCompiler.compile(archIr())
        assertTrue(out.startsWith("flowchart LR"), "direction LR -> 'flowchart LR': $out")
        assertTrue(out.contains("web[\"Web App\"]"), "frontend rectangle node: $out")
        assertTrue(out.contains("db[(\"Postgres\")]"), "database cylinder node: $out")
        assertTrue(out.contains("subgraph backend"), "group becomes subgraph: $out")
        assertTrue(out.contains("web -->|\"HTTP\"| api"), "labelled solid edge: $out")
        assertTrue(
            out.contains("api -.->|\"SQL\"| db"),
            "inferred low-confidence edge dotted: $out"
        )
    }

    @Test
    fun mermaidIsSecurityLevelSafe() {
        val out = MermaidCompiler.compile(archIr())
        assertFalse(out.contains("click "), "no click directives: $out")
        assertFalse(out.contains("<"), "no raw angle brackets / HTML labels: $out")
    }

    @Test
    fun mermaidSequenceHasParticipantsAndMessages() {
        val out = MermaidCompiler.compile(sequenceIr())
        assertTrue(out.startsWith("sequenceDiagram"), out)
        assertTrue(out.contains("actor user as User"), "user actor: $out")
        assertTrue(out.contains("participant server as Server"), out)
        assertTrue(out.contains("user->>server: POST /login"), "request solid arrow: $out")
        assertTrue(out.contains("server-->>user: 200 OK"), "response dashed arrow: $out")
    }

    @Test
    fun mermaidErAndState() {
        val er = MermaidCompiler.compile(erIr())
        assertTrue(er.startsWith("erDiagram"), er)
        assertTrue(er.contains("USER ||--o{ ORDER"), "has_many cardinality: $er")

        val state = MermaidCompiler.compile(stateIr())
        assertTrue(state.startsWith("stateDiagram-v2"), state)
        assertTrue(state.contains("draft --> placed : submit"), state)
    }

    // ---------------------------------------------------------------- D2

    @Test
    fun d2HasDirectionContainerNodesAndEdges() {
        val out = D2Compiler.compile(archIr())
        assertTrue(out.contains("direction: right"), "LR -> direction right: $out")
        assertTrue(out.contains("backend: \"Backend\" {"), "group container: $out")
        assertTrue(out.contains("backend.api -> backend.db"), "container-qualified edge: $out")
        assertTrue(out.contains("web -> backend.api: \"HTTP\""), "labelled edge: $out")
        assertTrue(out.contains("style.stroke-dash"), "inferred edge dashed: $out")
        assertTrue(out.contains("shape: cylinder"), "database shape: $out")
    }

    // ---------------------------------------------------------------- PlantUML

    @Test
    fun plantUmlComponentDiagram() {
        val out = PlantUmlCompiler.compile(archIr())
        assertTrue(out.startsWith("@startuml"), out)
        assertTrue(out.endsWith("@enduml"), out)
        assertTrue(out.contains("package \"Backend\" {"), "group package: $out")
        assertTrue(out.contains("database \"Postgres\" as db"), "database element: $out")
        assertTrue(out.contains("web --> api : HTTP"), "solid edge with label: $out")
        assertTrue(out.contains("api ..> db"), "inferred edge dotted: $out")
    }

    @Test
    fun plantUmlSequenceDiagram() {
        val out = PlantUmlCompiler.compile(sequenceIr())
        assertTrue(out.contains("actor \"User\" as user"), out)
        assertTrue(out.contains("participant \"Server\" as server"), out)
        assertTrue(out.contains("user -> server : POST /login"), out)
        assertTrue(out.contains("server --> user : 200 OK"), "response dashed: $out")
    }

    // ---------------------------------------------------------------- Graphviz

    @Test
    fun dotDigraphWithClusterRankdirAndDashedEdge() {
        val out = GraphvizCompiler.compile(archIr())
        assertTrue(out.startsWith("digraph G {"), out)
        assertTrue(out.contains("rankdir=LR;"), "LR rankdir: $out")
        assertTrue(out.contains("subgraph cluster_0 {"), "group cluster: $out")
        assertTrue(out.contains("label=\"Backend\";"), "cluster label: $out")
        assertTrue(out.contains("\"web\" -> \"api\" [label=\"HTTP\"];"), "labelled edge: $out")
        assertTrue(out.contains("style=dashed"), "inferred edge dashed: $out")
        assertTrue(out.contains("shape=cylinder"), "database shape: $out")
    }

    // ---------------------------------------------------------------- Markdown

    @Test
    fun markdownEmbedsMermaidEvidenceAndDisclosure() {
        val out = MarkdownExporter.export(archIr())
        assertTrue(out.contains("# Sample Service"), "title heading: $out")
        assertTrue(out.contains("AI-generated diagram"), "AI disclosure present: $out")
        assertTrue(out.contains("```mermaid"), "embeds a mermaid code block: $out")
        assertTrue(out.contains("flowchart LR"), "mermaid body inside block: $out")
        assertTrue(out.contains("## Evidence & validation"), "evidence/validation summary: $out")
        assertTrue(out.contains("| Node evidence coverage |"), "coverage table row: $out")
    }

    // ---------------------------------------------------------------- Facade

    @Test
    fun facadeDispatchesEachFormat() {
        val ir = archIr()
        assertTrue(CodegenFacade.compile(ir, CodegenFormat.MERMAID).startsWith("flowchart"))
        assertTrue(CodegenFacade.compile(ir, CodegenFormat.D2).contains("direction:"))
        assertTrue(CodegenFacade.compile(ir, CodegenFormat.PLANTUML).startsWith("@startuml"))
        assertTrue(CodegenFacade.compile(ir, CodegenFormat.DOT).startsWith("digraph"))
        assertTrue(CodegenFacade.compile(ir, CodegenFormat.MARKDOWN).startsWith("# Sample Service"))
    }
}
