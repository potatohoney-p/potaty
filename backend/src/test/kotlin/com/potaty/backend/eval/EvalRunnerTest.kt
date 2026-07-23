/*
 * Copyright (c) 2026, Potaty
 *
 * WS14 eval gate test (plan section 18 / 21.3). Runs the in-code [EvalCorpus] through the grounded
 * deterministic pipeline against an embedded H2 [AppGraph] and asserts the corpus meets the publish
 * gate: node coverage >= 0.90, edge coverage >= 0.80, forbidden-claim count == 0. Also asserts the
 * extractor recovers every required node/edge (recall == 1.0) and invents nothing forbidden.
 *
 * Fully offline and deterministic: no LLM, no network. Mirrors DiagramPipelineTest's H2 setup.
 */

package com.potaty.backend.eval

import com.potaty.backend.AppGraph
import com.potaty.backend.testConfig
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class EvalRunnerTest {

    @Test
    fun corpusMeetsPublishGate() = runBlocking {
        val config = testConfig()
        val graph = AppGraph.create(config)
        try {
            val workspaceId = UUID.fromString(config.auth.devWorkspaceId)
            val userId = UUID.fromString(config.auth.devUserId)
            val projectId = UUID.fromString(config.auth.devProjectId)

            val report = EvalRunner.runCorpus(graph, workspaceId, projectId, userId)

            assertEquals(
                EvalCorpus.ALL.size,
                report.perFixture.size,
                "every fixture should be scored"
            )
            assertTrue(
                report.gatePassed,
                "deterministic pipeline must clear the eval gate; " +
                    "failures=${report.failureReasons()}"
            )
            assertEquals(
                0,
                report.totalForbiddenClaims,
                "grounded extractor must not hallucinate forbidden claims"
            )
        } finally {
            graph.stop()
        }
    }

    @Test
    fun eachFixtureRecoversRequiredStructure() = runBlocking {
        val config = testConfig()
        val graph = AppGraph.create(config)
        try {
            val workspaceId = UUID.fromString(config.auth.devWorkspaceId)
            val userId = UUID.fromString(config.auth.devUserId)
            val projectId = UUID.fromString(config.auth.devProjectId)

            for (fixture in EvalCorpus.ALL) {
                val m = EvalRunner.runFixture(graph, fixture, workspaceId, projectId, userId)

                // Coverage thresholds (plan 18.1 / 21.3).
                assertTrue(
                    m.nodeCoverage >= EvalMetricsCalculator.MIN_NODE_COVERAGE,
                    "[${fixture.id}] node coverage ${m.nodeCoverage} below " +
                        EvalMetricsCalculator.MIN_NODE_COVERAGE
                )
                assertTrue(
                    m.edgeCoverage >= EvalMetricsCalculator.MIN_EDGE_COVERAGE,
                    "[${fixture.id}] edge coverage ${m.edgeCoverage} below " +
                        EvalMetricsCalculator.MIN_EDGE_COVERAGE
                )

                // Recall: every required node + edge was recovered by the grounded pass.
                assertEquals(
                    emptyList(),
                    m.missingNodeLabels,
                    "[${fixture.id}] all required nodes should be present"
                )
                assertEquals(
                    emptyList(),
                    m.missingEdges,
                    "[${fixture.id}] all required edges should be present"
                )
                assertEquals(
                    1.0,
                    m.entities.recall,
                    1e-9,
                    "[${fixture.id}] entity recall should be perfect"
                )
                assertEquals(
                    1.0,
                    m.relations.recall,
                    1e-9,
                    "[${fixture.id}] relation recall should be perfect"
                )

                // Faithfulness: no forbidden claims, no unsupported critical claims, and the gate
                // holds.
                assertEquals(
                    emptyList(),
                    m.forbiddenClaimsFound,
                    "[${fixture.id}] no forbidden claims should appear"
                )
                assertEquals(
                    0,
                    m.unsupportedCriticalClaims,
                    "[${fixture.id}] no unsupported critical claims"
                )
                assertTrue(
                    m.gatePassed,
                    "[${fixture.id}] gate should pass; reasons=${m.failureReasons()}"
                )
            }
        } finally {
            graph.stop()
        }
    }
}
