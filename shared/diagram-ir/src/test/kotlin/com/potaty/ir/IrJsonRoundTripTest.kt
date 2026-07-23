/*
 * Copyright (c) 2026, Potaty
 */

package com.potaty.ir

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round-trip coverage for [IrJson]. We rebuild a SampleDiagrams-like architecture IR locally (the
 * diagram-ir module cannot depend on diagram-demo) and assert that encode -> decode preserves the
 * graph: node/edge counts and ids, plus key scalar fields and enum/serial-name mappings.
 */
class IrJsonRoundTripTest {

    private fun ev(chunk: String, path: String? = null, line: Int? = null) =
        EvidenceRef(sourceChunkId = chunk, path = path, startLine = line, endLine = line)

    // Mirrors SampleDiagrams.architecture(): a CONTAINER diagram with two groups.
    private fun sample(): DiagramIR = DiagramIR(
        diagramId = "dia_arch",
        title = "Database Engine Architecture",
        objective = "Explain the network interface and relational engine for a new engineer.",
        diagramType = DiagramType.CONTAINER,
        scope = DiagramScope(abstractionLevel = AbstractionLevel.MEDIUM),
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
                type = EdgeType.REQUEST,
                label = "submit",
                edgeSourceType = EdgeSourceType.EXPLICIT_CALL,
                evidence = listOf(ev("chk_eng1", "src/engine/parser.kt", 22))
            ),
            IrEdge(
                "e2",
                from = "parser",
                to = "optimizer",
                type = EdgeType.CALLS,
                edgeSourceType = EdgeSourceType.STATIC_IMPORT,
                evidence = listOf(ev("chk_eng2", "src/engine/optimizer.kt", 36))
            ),
            IrEdge(
                "e3",
                from = "optimizer",
                to = "executor",
                type = EdgeType.CALLS,
                edgeSourceType = EdgeSourceType.STATIC_IMPORT,
                evidence = listOf(ev("chk_eng3", "src/engine/executor.kt", 53))
            )
        )
    )

    @Test
    fun compactRoundTripPreservesCountsAndIds() {
        val original = sample()
        val decoded = IrJson.decode(IrJson.encode(original, pretty = false))

        assertEquals(original.nodes.size, decoded.nodes.size, "node count must survive round-trip")
        assertEquals(original.edges.size, decoded.edges.size, "edge count must survive round-trip")
        assertEquals(
            original.groups.size,
            decoded.groups.size,
            "group count must survive round-trip"
        )

        assertEquals(
            original.nodes.map { it.id },
            decoded.nodes.map { it.id },
            "node ids/order preserved"
        )
        assertEquals(
            original.edges.map { it.id },
            decoded.edges.map { it.id },
            "edge ids/order preserved"
        )
        assertEquals(
            original.edges.map { it.from to it.to },
            decoded.edges.map { it.from to it.to },
            "edge endpoints preserved"
        )
    }

    @Test
    fun prettyRoundTripPreservesScalarAndEnumFields() {
        val original = sample()
        // Encode pretty (golden-style), decode with the compact reader.
        val decoded = IrJson.decode(IrJson.encode(original, pretty = true))

        assertEquals(original.diagramId, decoded.diagramId)
        assertEquals(original.title, decoded.title)
        assertEquals(original.objective, decoded.objective)
        assertEquals(original.diagramType, decoded.diagramType)
        assertEquals(original.schemaVersion, decoded.schemaVersion)

        // Enum (serial-name) fidelity on a representative node and edge.
        val parser = decoded.nodes.single { it.id == "parser" }
        assertEquals(NodeType.COMPONENT, parser.type)
        assertEquals(0.88, parser.confidence)
        assertEquals("chk_eng1", parser.evidence.single().sourceChunkId)

        val e1 = decoded.edges.single { it.id == "e1" }
        assertEquals(EdgeType.REQUEST, e1.type)
        assertEquals(EdgeSourceType.EXPLICIT_CALL, e1.edgeSourceType)
        assertEquals("submit", e1.label)
    }

    @Test
    fun fullValueEqualityAfterRoundTrip() {
        val original = sample()
        val decoded = IrJson.decode(IrJson.encode(original, pretty = false))
        // Data-class structural equality is the strongest round-trip guarantee.
        assertEquals(original, decoded, "decoded IR must be structurally equal to the original")
    }

    @Test
    fun decodedSampleStillValidates() {
        val decoded = IrJson.decode(IrJson.encode(sample(), pretty = false))
        val report = IrValidator().validate(decoded)
        assertTrue(
            report.isValid,
            "round-tripped sample must remain structurally valid: ${report.errors}"
        )
    }
}
