/*
 * Copyright (c) 2026, Potaty
 */

package com.potaty.ir

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit coverage for [IrValidator]. Each test isolates a single rule so that the *only* error code
 * present is the one under test (apart from the explicit cross-checks). Helper builders keep every
 * node/edge grounded with evidence so unrelated publish gates (R009/R010) do not fire by accident.
 */
class IrValidatorTest {

    private val validator = IrValidator()

    private fun ev(chunk: String = "chk_1") = EvidenceRef(sourceChunkId = chunk)

    /** A node that is always grounded (has evidence) so it never trips R009 on its own. */
    private fun node(
        id: String,
        label: String = "Node $id",
        confidence: Double = 1.0,
        evidence: List<EvidenceRef> = listOf(ev()),
        userModified: Boolean = false
    ) = IrNode(
        id = id,
        label = label,
        confidence = confidence,
        evidence = evidence,
        userModified = userModified
    )

    /** An edge grounded via a deterministic source + evidence so it never trips R010 on its own. */
    private fun edge(
        id: String,
        from: String,
        to: String,
        confidence: Double = 1.0
    ) = IrEdge(
        id = id,
        from = from,
        to = to,
        confidence = confidence,
        edgeSourceType = EdgeSourceType.EXPLICIT_CALL,
        evidence = listOf(ev())
    )

    private fun ir(
        type: DiagramType = DiagramType.ARCHITECTURE,
        nodes: List<IrNode>,
        edges: List<IrEdge> = emptyList()
    ) = DiagramIR(
        diagramId = "dia_test",
        title = "Test",
        diagramType = type,
        nodes = nodes,
        edges = edges
    )

    private fun codes(report: ValidationReport): Set<String> = report.issues.map { it.code }.toSet()

    @Test
    fun validIrPassesWithNoErrorsAndIsPublishable() {
        val report = validator.validate(
            ir(
                nodes = listOf(node("a"), node("b")),
                edges = listOf(edge("e1", "a", "b"))
            )
        )

        assertTrue(report.isValid, "expected valid IR to have no errors, got: ${report.errors}")
        assertTrue(report.isPublishable, "expected valid grounded IR to be publishable")
        assertTrue(report.errors.isEmpty(), "expected no error issues, got: ${report.errors}")
    }

    @Test
    fun emptyDiagramIsInvalidAndCannotBePublished() {
        val report = validator.validate(ir(nodes = emptyList()))

        assertTrue("IR-R014" in codes(report))
        assertFalse(report.isValid)
        assertFalse(report.isPublishable)
    }

    @Test
    fun duplicateNodeIdFiresR002() {
        val report = validator.validate(
            ir(nodes = listOf(node("dup"), node("dup")))
        )

        assertTrue("IR-R002" in codes(report), "expected IR-R002 for duplicate node id")
        val issue = report.errors.single { it.code == "IR-R002" }
        assertEquals("dup", issue.targetId)
        assertFalse(report.isValid, "duplicate node id must invalidate the diagram")
    }

    @Test
    fun danglingEdgeEndpointFiresR005() {
        val report = validator.validate(
            ir(
                nodes = listOf(node("a")),
                // 'ghost' target does not exist
                edges = listOf(edge("e1", "a", "ghost"))
            )
        )

        assertTrue("IR-R005" in codes(report), "expected IR-R005 for missing edge endpoint")
        val issue = report.errors.single { it.code == "IR-R005" }
        assertEquals("e1", issue.targetId)
        assertFalse(report.isValid)
    }

    @Test
    fun outOfRangeConfidenceFiresR007() {
        val report = validator.validate(
            ir(
                nodes = listOf(node("a", confidence = 1.5), node("b")),
                edges = listOf(edge("e1", "a", "b", confidence = -0.2))
            )
        )

        val r007 = report.errors.filter { it.code == "IR-R007" }
        assertEquals(2, r007.size, "expected R007 for both the bad node and bad edge confidence")
        assertTrue(r007.any { it.targetId == "a" }, "expected R007 on node 'a'")
        assertTrue(r007.any { it.targetId == "e1" }, "expected R007 on edge 'e1'")
        assertFalse(report.isValid)
    }

    @Test
    fun nodeWithoutEvidenceIsNotPublishableR009() {
        // Node 'orphan' has no evidence and is not user-modified -> publish gate R009.
        val orphan = IrNode(id = "orphan", label = "Orphan")
        val report = validator.validate(
            ir(nodes = listOf(orphan, node("b")))
        )

        assertTrue("IR-R009" in codes(report), "expected IR-R009 for ungrounded node")
        val issue = report.issues.single { it.code == "IR-R009" }
        assertEquals("orphan", issue.targetId)
        assertTrue(issue.blocksPublish, "R009 must block publication")
        assertFalse(report.isPublishable, "an ungrounded node must make the diagram unpublishable")
    }

    @Test
    fun userModifiedNodeWithoutEvidenceSatisfiesR009() {
        // user_modified true is an alternate grounding path; R009 must NOT fire.
        val confirmed = IrNode(id = "confirmed", label = "Confirmed", userModified = true)
        val report = validator.validate(ir(nodes = listOf(confirmed)))

        assertFalse(
            "IR-R009" in codes(report),
            "user-modified node should satisfy the publish gate"
        )
    }

    @Test
    fun secretLikeLabelFiresR012() {
        // 'api_key=ABCD1234...' matches the api_key=<value> sensitive pattern.
        val leaky = node("leaky", label = "api_key=ABCD1234XYZ")
        val report = validator.validate(ir(nodes = listOf(leaky)))

        assertTrue("IR-R012" in codes(report), "expected IR-R012 for secret-like label")
        val issue = report.issues.single { it.code == "IR-R012" }
        assertEquals("leaky", issue.targetId)
        assertTrue(issue.blocksPublish, "R012 must block publication")
        assertFalse(report.isPublishable)
    }

    @Test
    fun cycleInFlowchartFiresR011() {
        // a -> b -> c -> a is a cycle; FLOWCHART is an acyclic diagram type.
        val report = validator.validate(
            ir(
                type = DiagramType.FLOWCHART,
                nodes = listOf(node("a"), node("b"), node("c")),
                edges = listOf(
                    edge("e1", "a", "b"),
                    edge("e2", "b", "c"),
                    edge("e3", "c", "a")
                )
            )
        )

        assertTrue(
            "IR-R011" in codes(report),
            "expected IR-R011 for a cycle in an acyclic diagram type"
        )
        assertFalse(report.isValid)
    }

    @Test
    fun cycleAllowedInNonAcyclicDiagramType() {
        // The same cycle in ARCHITECTURE (not an acyclic type) must NOT fire R011.
        val report = validator.validate(
            ir(
                type = DiagramType.ARCHITECTURE,
                nodes = listOf(node("a"), node("b"), node("c")),
                edges = listOf(
                    edge("e1", "a", "b"),
                    edge("e2", "b", "c"),
                    edge("e3", "c", "a")
                )
            )
        )

        assertFalse("IR-R011" in codes(report), "cycles are permitted in non-acyclic diagram types")
    }
}
