/*
 * Copyright (c) 2026, Potaty
 *
 * WS14 evaluation metrics (plan section 18.1 + 21.3). Given a produced [DiagramIR] and the
 * ground-truth expectations of an [EvalFixture], compute:
 *
 *  - entity precision / recall (did we recover the important nodes without inventing extras?),
 *  - relation precision / recall (did we recover the important directed edges?),
 *  - node / edge evidence coverage (reuses the shared [EvidenceCoverageScorer]),
 *  - forbidden-claim count (hallucinations of explicitly-absent entities/relations),
 *
 * and a pass/fail gate against the plan 18.1/21.3 thresholds:
 *    node coverage >= 0.90, edge coverage >= 0.80, forbidden-claim count == 0.
 *
 * Matching is on a canonical label form (trim, whitespace-collapse, lowercase) so it lines up with
 * the deterministic extractor's own canonicalization, independent of node-id sanitization.
 */

package com.potaty.backend.eval

import com.potaty.ir.DiagramIR
import com.potaty.ir.EvidenceCoverageScorer

/** Precision/recall pair plus the raw confusion counts that produced it. */
data class PrecisionRecall(
    val truePositives: Int,
    val falsePositives: Int,
    val falseNegatives: Int
) {
    val precision: Double
        get() = ratio(truePositives, truePositives + falsePositives)

    val recall: Double
        get() = ratio(truePositives, truePositives + falseNegatives)

    val f1: Double
        get() {
            val p = precision
            val r = recall
            return if (p + r == 0.0) 0.0 else 2.0 * p * r / (p + r)
        }

    private fun ratio(part: Int, total: Int): Double =
        if (total == 0) 1.0 else part.toDouble() / total.toDouble()
}

/** The full metric bundle for a single fixture. [gatePassed] is the plan 18.1/21.3 publish gate. */
data class EvalMetrics(
    val fixtureId: String,
    val entities: PrecisionRecall,
    val relations: PrecisionRecall,
    val nodeCoverage: Double,
    val edgeCoverage: Double,
    val unsupportedCriticalClaims: Int,
    val forbiddenClaimCount: Int,
    /** The specific forbidden substrings that leaked into the IR (for diagnostics). */
    val forbiddenClaimsFound: List<String>,
    /** Required node labels that were NOT produced (recall misses), for diagnostics. */
    val missingNodeLabels: List<String>,
    /** Required edges that were NOT produced (recall misses), for diagnostics. */
    val missingEdges: List<ExpectedEdge>,
    val minNodeCoverage: Double,
    val minEdgeCoverage: Double
) {
    /** Plan 18.1/21.3 gate: node coverage, edge coverage, and zero forbidden claims. */
    val gatePassed: Boolean
        get() =
            nodeCoverage >= minNodeCoverage &&
                edgeCoverage >= minEdgeCoverage &&
                forbiddenClaimCount == 0 &&
                unsupportedCriticalClaims == 0

    /** Human-readable failure summary; empty when [gatePassed] is true. */
    fun failureReasons(): List<String> {
        if (gatePassed) return emptyList()
        val reasons = mutableListOf<String>()
        if (nodeCoverage < minNodeCoverage) {
            reasons +=
                "node coverage $nodeCoverage < $minNodeCoverage (missing nodes=$missingNodeLabels)"
        }
        if (edgeCoverage < minEdgeCoverage) {
            reasons +=
                "edge coverage $edgeCoverage < $minEdgeCoverage (missing edges=$missingEdges)"
        }
        if (forbiddenClaimCount != 0) {
            reasons += "forbidden claims present: $forbiddenClaimsFound"
        }
        if (unsupportedCriticalClaims != 0) {
            reasons += "unsupported critical claims: $unsupportedCriticalClaims"
        }
        return reasons
    }
}

object EvalMetricsCalculator {

    const val MIN_NODE_COVERAGE: Double = 0.90
    const val MIN_EDGE_COVERAGE: Double = 0.80

    /**
     * Computes [EvalMetrics] for [ir] against [fixture]'s ground truth.
     *
     * Entity precision/recall is measured against [EvalFixture.requiredNodeLabels]: required labels
     * that appear are true positives, required labels that are missing are false negatives, and any
     * produced node label that is not required is a false positive (an "extra" entity). Relation
     * precision/recall mirrors this over directed (from,to) pairs.
     */
    fun compute(ir: DiagramIR, fixture: EvalFixture): EvalMetrics {
        val producedNodeLabels = ir.nodes.map { canonical(it.label) }.toSet()
        val labelById = ir.nodes.associate { it.id to canonical(it.label) }

        // ----- entity precision / recall -----
        val requiredNodes = fixture.requiredNodeLabels.map { canonical(it) }.toSet()
        val nodeTp = requiredNodes.count { it in producedNodeLabels }
        val nodeFn = requiredNodes.count { it !in producedNodeLabels }
        // An "extra" produced node that is not in the required set is a false positive.
        val nodeFp = producedNodeLabels.count { it !in requiredNodes }
        val missingNodeLabels =
            fixture.requiredNodeLabels.filter { canonical(it) !in producedNodeLabels }

        // ----- relation precision / recall (directed endpoint labels) -----
        val producedEdges: Set<Pair<String, String>> =
            ir.edges
                .mapNotNull { e ->
                    val f = labelById[e.from]
                    val t = labelById[e.to]
                    if (f != null && t != null) f to t else null
                }
                .toSet()
        val requiredEdges =
            fixture.requiredEdges.map { canonical(it.from) to canonical(it.to) }.toSet()
        val edgeTp = requiredEdges.count { it in producedEdges }
        val edgeFn = requiredEdges.count { it !in producedEdges }
        val edgeFp = producedEdges.count { it !in requiredEdges }
        val missingEdges =
            fixture.requiredEdges.filter {
                (canonical(it.from) to canonical(it.to)) !in producedEdges
            }

        // ----- forbidden claims (hallucination guard) -----
        // A forbidden substring leaks if it appears in any node label or any edge endpoint/label.
        val haystacks = buildList {
            ir.nodes.forEach { add(canonical(it.label)) }
            ir.edges.forEach { e ->
                e.label?.let { add(canonical(it)) }
            }
        }
        val forbiddenFound =
            fixture.forbiddenClaims.filter { claim ->
                val needle = canonical(claim)
                needle.isNotEmpty() && haystacks.any { it.contains(needle) }
            }

        // ----- evidence coverage (shared scorer) -----
        val coverage = EvidenceCoverageScorer.score(ir)

        return EvalMetrics(
            fixtureId = fixture.id,
            entities =
            PrecisionRecall(
                truePositives = nodeTp,
                falsePositives = nodeFp,
                falseNegatives = nodeFn
            ),
            relations =
            PrecisionRecall(
                truePositives = edgeTp,
                falsePositives = edgeFp,
                falseNegatives = edgeFn
            ),
            nodeCoverage = coverage.nodeCoverage,
            edgeCoverage = coverage.edgeCoverage,
            unsupportedCriticalClaims = coverage.unsupportedCriticalClaims,
            forbiddenClaimCount = forbiddenFound.size,
            forbiddenClaimsFound = forbiddenFound,
            missingNodeLabels = missingNodeLabels,
            missingEdges = missingEdges,
            minNodeCoverage = MIN_NODE_COVERAGE,
            minEdgeCoverage = MIN_EDGE_COVERAGE
        )
    }

    /**
     * Canonical label form used for all matching: collapse internal whitespace, trim, lowercase.
     * Mirrors the deterministic extractor's own normalization so eval expectations written in
     * natural casing line up with produced node labels.
     */
    fun canonical(s: String): String = s.replace(Regex("""\s+"""), " ").trim().lowercase()
}

/**
 * Aggregate of per-fixture [EvalMetrics] across the whole corpus, with a corpus-level gate that is
 * the conjunction of every fixture's gate.
 */
data class EvalReport(val perFixture: List<EvalMetrics>) {
    val gatePassed: Boolean
        get() = perFixture.isNotEmpty() && perFixture.all { it.gatePassed }

    /** Mean entity F1 across fixtures. */
    val meanEntityF1: Double
        get() = if (perFixture.isEmpty()) 0.0 else perFixture.map { it.entities.f1 }.average()

    val meanRelationF1: Double
        get() = if (perFixture.isEmpty()) 0.0 else perFixture.map { it.relations.f1 }.average()

    val totalForbiddenClaims: Int
        get() = perFixture.sumOf { it.forbiddenClaimCount }

    /** Per-fixture failure summary lines for any fixture that failed the gate. */
    fun failureReasons(): List<String> = perFixture.flatMap { m ->
        m.failureReasons().map { "[${m.fixtureId}] $it" }
    }
}
