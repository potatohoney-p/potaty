/*
 * Copyright (c) 2026, Potaty
 */

package com.potaty.ir

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Quantifies how well a diagram is grounded in source evidence. These metrics gate publication (see
 * plan section 21.3 thresholds: node coverage >= 0.90, edge coverage >= 0.80, critical unsupported
 * claims == 0) and are surfaced in the Workbench and GitHub PR bodies.
 */
@Serializable
data class EvidenceCoverage(
    @SerialName("node_coverage") val nodeCoverage: Double,
    @SerialName("edge_coverage") val edgeCoverage: Double,
    @SerialName("grounded_edge_ratio") val groundedEdgeRatio: Double,
    @SerialName("inferred_edge_count") val inferredEdgeCount: Int,
    @SerialName("unsupported_critical_claims") val unsupportedCriticalClaims: Int,
    @SerialName("low_confidence_node_count") val lowConfidenceNodeCount: Int,
    @SerialName("low_confidence_edge_count") val lowConfidenceEdgeCount: Int,
    /**
     * Confidence-weighted edge coverage. [edgeCoverage] is binary ("does this edge have evidence /
     * is it user-confirmed"); this metric instead sums each covered edge's confidence and divides
     * by the edge count, so a graph full of low-confidence-but-cited edges scores lower than one
     * with the same coverage at high confidence. Range [0,1]; defaults to 0.0 for backward
     * compatibility with callers that construct EvidenceCoverage directly.
     */
    @SerialName("confidence_weighted_edge_coverage")
    val confidenceWeightedEdgeCoverage: Double = 0.0,
    /**
     * Edge-confidence distribution across three fixed bands — low
     * [0,0.3), mid [0.3,0.7), high [0.7,1.0]. Modeled as three Int fields rather than a Map so
     * this @Serializable class stays compatible with the Kotlin/JS serializer-IR linker (a
     * Map<String,Int> field trips the 1.8.20 JS dev-executable validation). Enables fine-grained
     * eval dashboards (plan 21.2).
     */
    @SerialName("low_confidence_bucket") val lowConfidenceBucket: Int = 0,
    @SerialName("mid_confidence_bucket") val midConfidenceBucket: Int = 0,
    @SerialName("high_confidence_bucket") val highConfidenceBucket: Int = 0
) {
    fun meetsThreshold(
        minNodeCoverage: Double = 0.90,
        minEdgeCoverage: Double = 0.80
    ): Boolean =
        nodeCoverage >= minNodeCoverage &&
            edgeCoverage >= minEdgeCoverage &&
            unsupportedCriticalClaims == 0
}

object EvidenceCoverageScorer {

    private const val LOW_CONFIDENCE = 0.7

    fun score(ir: DiagramIR): EvidenceCoverage {
        val nodeCount = ir.nodes.size
        val edgeCount = ir.edges.size

        val coveredNodes =
            ir.nodes.count {
                it.evidence.isNotEmpty() || it.userConfirmed || it.userModified
            }
        val coveredEdgeList =
            ir.edges.filter {
                it.evidence.isNotEmpty() || it.edgeSourceType == EdgeSourceType.USER_CONFIRMED
            }
        val coveredEdges = coveredEdgeList.size
        val groundedEdges = ir.edges.count { it.edgeSourceType.isGrounded }
        val inferredEdges = ir.edges.count { it.edgeSourceType == EdgeSourceType.LLM_INFERRED }

        // Confidence-weighted coverage: a covered edge contributes its confidence, not a flat 1.0.
        val weightedCovered = coveredEdgeList.sumOf { it.confidence }

        return EvidenceCoverage(
            nodeCoverage = ratio(coveredNodes, nodeCount),
            edgeCoverage = ratio(coveredEdges, edgeCount),
            groundedEdgeRatio = ratio(groundedEdges, edgeCount),
            inferredEdgeCount = inferredEdges,
            unsupportedCriticalClaims =
            ir.unsupportedClaims.count { it.severity == Severity.CRITICAL },
            lowConfidenceNodeCount = ir.nodes.count { it.confidence < LOW_CONFIDENCE },
            lowConfidenceEdgeCount = ir.edges.count { it.confidence < LOW_CONFIDENCE },
            confidenceWeightedEdgeCoverage = ratioD(weightedCovered, edgeCount),
            lowConfidenceBucket = ir.edges.count { it.confidence < 0.3 },
            midConfidenceBucket = ir.edges.count { it.confidence >= 0.3 && it.confidence < 0.7 },
            highConfidenceBucket = ir.edges.count { it.confidence >= 0.7 }
        )
    }

    private fun ratio(part: Int, total: Int): Double =
        if (total == 0) 1.0 else (part.toDouble() / total.toDouble())

    private fun ratioD(part: Double, total: Int): Double =
        if (total == 0) 1.0 else (part / total.toDouble())
}
