/*
 * Copyright (c) 2026, Potaty
 */

package com.potaty.ir

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression coverage for the additive P2/P3 hardening of the diagram-ir module:
 *  - [CycleDetector.findCycle] is order-independent (deterministic across nodeIds permutations).
 *  - R009 accepts the new [IrNode.userConfirmed] grounding path.
 *  - R008 warns on inverted evidence ranges and (optionally) dangling source-chunk citations.
 *  - [IrPatcher.applyWithReport] surfaces duplicate ids dropped during the defensive dedup.
 *  - [IrPatchOp.MergeNodes.dropSelfLoops] toggles merged-self-loop removal.
 *  - [EvidenceCoverage] exposes confidence-weighted coverage and per-bucket confidence counts.
 */
class IrFixesTest {

    private fun ev(chunk: String = "chk_1") = EvidenceRef(sourceChunkId = chunk)

    private fun node(
        id: String,
        evidence: List<EvidenceRef> = listOf(ev()),
        userModified: Boolean = false,
        userConfirmed: Boolean = false,
        confidence: Double = 1.0
    ) = IrNode(
        id = id,
        label = "Node $id",
        evidence = evidence,
        userModified = userModified,
        userConfirmed = userConfirmed,
        confidence = confidence
    )

    private fun groundedEdge(
        id: String,
        from: String,
        to: String,
        confidence: Double = 1.0,
        evidence: List<EvidenceRef> = listOf(ev())
    ) = IrEdge(
        id = id,
        from = from,
        to = to,
        confidence = confidence,
        edgeSourceType = EdgeSourceType.EXPLICIT_CALL,
        evidence = evidence
    )

    private fun ir(
        type: DiagramType = DiagramType.ARCHITECTURE,
        nodes: List<IrNode>,
        edges: List<IrEdge> = emptyList()
    ) = DiagramIR(diagramId = "d", title = "t", diagramType = type, nodes = nodes, edges = edges)

    // --- CycleDetector determinism (P2 #7 / #10) -----------------------------

    @Test
    fun findCycleIsOrderIndependent() {
        // a -> b -> c -> a is the only cycle; permuting the nodeIds must still detect it and yield
        // the same cycle node *set* (the returned list may be rotated by start node).
        val edges = listOf("a" to "b", "b" to "c", "c" to "a")
        val permutations = listOf(
            listOf("a", "b", "c"),
            listOf("c", "b", "a"),
            listOf("b", "a", "c"),
            listOf("c", "a", "b")
        )
        val cycleSets = permutations.map { perm ->
            val cycle = CycleDetector.findCycle(perm, edges)
            assertNotNull(cycle, "a cycle must be detected for permutation $perm")
            cycle.toSet()
        }
        val first = cycleSets.first()
        assertTrue(
            cycleSets.all { it == first },
            "cycle node-set must be identical across nodeId orderings, got: $cycleSets"
        )
        assertEquals(setOf("a", "b", "c"), first)
    }

    @Test
    fun findCycleReturnsNullForAcyclicGraphRegardlessOfOrder() {
        val edges = listOf("a" to "b", "b" to "c")
        assertEquals(null, CycleDetector.findCycle(listOf("a", "b", "c"), edges))
        assertEquals(null, CycleDetector.findCycle(listOf("c", "b", "a"), edges))
    }

    @Test
    fun selfLoopIsDetectedAsCycle() {
        val cycle = CycleDetector.findCycle(listOf("a"), listOf("a" to "a"))
        assertEquals(listOf("a", "a"), cycle)
    }

    // --- R009 userConfirmed grounding (P2 #4 / #16) --------------------------

    @Test
    fun userConfirmedNodeWithoutEvidenceSatisfiesR009() {
        val confirmed = IrNode(id = "c", label = "Confirmed", userConfirmed = true)
        val report = IrValidator().validate(ir(nodes = listOf(confirmed)))
        assertFalse(
            report.issues.any { it.code == "IR-R009" },
            "user-confirmed node should satisfy the publish gate"
        )
        assertTrue(report.isPublishable)
    }

    @Test
    fun ungroundedNodeStillBlocksR009() {
        val orphan = IrNode(id = "o", label = "Orphan")
        val report = IrValidator().validate(ir(nodes = listOf(orphan)))
        assertTrue(report.issues.any { it.code == "IR-R009" })
        assertFalse(report.isPublishable)
    }

    // --- R008 evidence-range + dangling citation (P3 #1 / #3) ----------------

    @Test
    fun evidenceRefRangeValidation() {
        assertTrue(EvidenceRef("c", startLine = 1, endLine = 5).isValidRange())
        assertTrue(EvidenceRef("c", startLine = 5, endLine = 5).isValidRange())
        assertTrue(EvidenceRef("c").isValidRange(), "missing endpoints are valid")
        assertFalse(EvidenceRef("c", startLine = 9, endLine = 2).isValidRange())
        assertFalse(EvidenceRef("c", startPage = 4, endPage = 1).isValidRange())
        assertFalse(EvidenceRef("c", startMs = 200, endMs = 100).isValidRange())
    }

    @Test
    fun invertedEvidenceRangeFiresR008Warning() {
        val n = node("a", evidence = listOf(EvidenceRef("chk_1", startLine = 10, endLine = 2)))
        val report = IrValidator().validate(ir(nodes = listOf(n)))
        val r008 = report.warnings.filter { it.code == "IR-R008" }
        assertTrue(r008.any { it.targetId == "a" }, "inverted range should warn under R008")
        // An inverted range is a warning, not an error: it does not block publication on its own.
        assertTrue(report.isPublishable)
    }

    @Test
    fun unknownSourceChunkFiresR008WarningOnlyWhenRegistryProvided() {
        val n = node("a", evidence = listOf(ev("chk_missing")))

        // No registry -> resolvability check skipped (default, existing behavior).
        val lenient = IrValidator().validate(ir(nodes = listOf(n)))
        assertFalse(lenient.issues.any { it.code == "IR-R008" })

        // Registry without the chunk -> dangling-citation warning.
        val strict = IrValidator(knownSourceChunkIds = setOf("chk_present"))
            .validate(ir(nodes = listOf(n)))
        assertTrue(strict.warnings.any { it.code == "IR-R008" && it.targetId == "a" })

        // Registry containing the chunk -> no warning.
        val ok = IrValidator(knownSourceChunkIds = setOf("chk_missing"))
            .validate(ir(nodes = listOf(n)))
        assertFalse(ok.issues.any { it.code == "IR-R008" })
    }

    // --- IrPatcher dedup reporting (P2 #3) -----------------------------------

    @Test
    fun applyWithReportSurfacesDroppedDuplicateNodeIds() {
        // MergeNodes.into.id collides with an existing node 'b' -> dedup drops the duplicate.
        val base = ir(
            nodes = listOf(node("a"), node("b"), node("c")),
            edges = listOf(groundedEdge("e1", "a", "c"))
        )
        val patch = IrPatch(
            operations = listOf(
                IrPatchOp.MergeNodes(
                    nodeIds = listOf("a", "c"),
                    into = IrNode(id = "b", label = "B")
                )
            )
        )
        val result = IrPatcher.applyWithReport(base, patch)
        assertFalse(result.isClean, "a colliding merge target should be reported")
        assertTrue(result.droppedDuplicateNodeIds.contains("b"))
        // apply() returns the same IR, just without the report.
        assertEquals(
            result.ir.nodes.map { it.id },
            IrPatcher.apply(base, patch).nodes.map { it.id }
        )
        // Result still satisfies the unique-id invariant.
        assertEquals(result.ir.nodes.size, result.ir.nodes.map { it.id }.distinct().size)
    }

    @Test
    fun applyWithReportIsCleanForNonCollidingPatch() {
        val base = ir(nodes = listOf(node("a"), node("b")))
        val patch = IrPatch(operations = listOf(IrPatchOp.AddNode(node("c"))))
        val result = IrPatcher.applyWithReport(base, patch)
        assertTrue(result.isClean)
        assertTrue(result.droppedDuplicateNodeIds.isEmpty())
        assertTrue(result.droppedDuplicateEdgeIds.isEmpty())
    }

    // --- MergeNodes dropSelfLoops (P2 #5) ------------------------------------

    @Test
    fun mergeNodesDropsSelfLoopsByDefault() {
        val base = ir(
            nodes = listOf(node("a"), node("b")),
            edges = listOf(groundedEdge("e1", "a", "b"))
        )
        val merged = IrPatcher.apply(
            base,
            IrPatch(
                operations = listOf(
                    IrPatchOp.MergeNodes(listOf("a", "b"), IrNode(id = "ab", label = "AB"))
                )
            )
        )
        assertTrue(
            merged.edges.isEmpty(),
            "the a->b edge becomes a self-loop and is dropped by default"
        )
    }

    @Test
    fun mergeNodesKeepsSelfLoopsWhenDropSelfLoopsFalse() {
        val base = ir(
            nodes = listOf(node("a"), node("b")),
            edges = listOf(groundedEdge("e1", "a", "b"))
        )
        val merged = IrPatcher.apply(
            base,
            IrPatch(
                operations = listOf(
                    IrPatchOp.MergeNodes(
                        nodeIds = listOf("a", "b"),
                        into = IrNode(id = "ab", label = "AB"),
                        dropSelfLoops = false
                    )
                )
            )
        )
        assertEquals(1, merged.edges.size, "self-loop retained when dropSelfLoops=false")
        val e = merged.edges.single()
        assertEquals("ab", e.from)
        assertEquals("ab", e.to)
    }

    // --- EvidenceCoverage additive metrics (P2 #2 / #19) ---------------------

    @Test
    fun confidenceWeightedEdgeCoverageDownweightsLowConfidence() {
        // Both edges are covered (binary edgeCoverage == 1.0) but one is low confidence, so the
        // confidence-weighted metric drops below 1.0.
        val coverage = EvidenceCoverageScorer.score(
            ir(
                nodes = listOf(node("a"), node("b")),
                edges = listOf(
                    groundedEdge("e1", "a", "b", confidence = 1.0),
                    groundedEdge("e2", "a", "b", confidence = 0.4)
                )
            )
        )
        assertEquals(1.0, coverage.edgeCoverage, "binary coverage: both edges have evidence")
        assertEquals((1.0 + 0.4) / 2.0, coverage.confidenceWeightedEdgeCoverage)
    }

    @Test
    fun confidenceByBucketCountsEdges() {
        val coverage = EvidenceCoverageScorer.score(
            ir(
                nodes = listOf(node("a"), node("b")),
                edges = listOf(
                    groundedEdge("e1", "a", "b", confidence = 0.1), // low
                    groundedEdge("e2", "a", "b", confidence = 0.5), // mid
                    groundedEdge("e3", "a", "b", confidence = 0.9), // high
                    groundedEdge("e4", "a", "b", confidence = 0.7) // high (boundary inclusive)
                )
            )
        )
        assertEquals(1, coverage.lowConfidenceBucket)
        assertEquals(1, coverage.midConfidenceBucket)
        assertEquals(2, coverage.highConfidenceBucket)
    }

    @Test
    fun userConfirmedNodeCountsTowardCoverage() {
        val coverage = EvidenceCoverageScorer.score(
            ir(
                nodes = listOf(
                    node("a", evidence = emptyList(), userConfirmed = true),
                    node("b", evidence = emptyList())
                )
            )
        )
        assertEquals(0.5, coverage.nodeCoverage, "user-confirmed node counts as covered")
    }

    @Test
    fun emptyGraphBucketsAreZeroAndWeightedCoverageIsOne() {
        val coverage = EvidenceCoverageScorer.score(ir(nodes = emptyList(), edges = emptyList()))
        assertEquals(1.0, coverage.confidenceWeightedEdgeCoverage, "0/0 weighted coverage -> 1.0")
        assertEquals(0, coverage.lowConfidenceBucket)
        assertEquals(0, coverage.midConfidenceBucket)
        assertEquals(0, coverage.highConfidenceBucket)
    }
}
