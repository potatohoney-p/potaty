/*
 * Copyright (c) 2026, Potaty
 */

package com.potaty.backend

import com.potaty.backend.github.publishBlockReason
import com.potaty.backend.ir.ValidationReport
import com.potaty.backend.ir.toApiDto
import com.potaty.ir.DiagramIR
import com.potaty.ir.DiagramType
import com.potaty.ir.EdgeSourceType
import com.potaty.ir.EvidenceCoverage
import com.potaty.ir.EvidenceCoverageScorer
import com.potaty.ir.EvidenceRef
import com.potaty.ir.IrEdge
import com.potaty.ir.IrJson
import com.potaty.ir.IrNode
import com.potaty.ir.IrValidator
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class GitHubPublishGateTest {

    @Test
    fun validButUnderCoveredDiagramIsNotPublishable() {
        val evidence = EvidenceRef(
            sourceChunkId = "chunk-1",
            startLine = 1,
            endLine = 1,
            quote = "A and B",
            quoteHash = "hash"
        )
        val ir = DiagramIR(
            diagramId = "coverage-gate",
            title = "Under-covered",
            diagramType = DiagramType.ARCHITECTURE,
            nodes = listOf(
                IrNode(id = "a", label = "A", evidence = listOf(evidence)),
                IrNode(id = "b", label = "B", evidence = listOf(evidence))
            ),
            edges = listOf(
                IrEdge(
                    id = "inferred",
                    from = "a",
                    to = "b",
                    confidence = 0.55,
                    edgeSourceType = EdgeSourceType.LLM_INFERRED
                )
            )
        )
        val report = IrValidator().validate(ir).toApiDto()
        val coverage = EvidenceCoverageScorer.score(ir)

        assertTrue(report.valid, "visible low-confidence inference is structurally valid")
        assertFalse(coverage.meetsThreshold(), "inferred edge has no publishable evidence coverage")
        assertNotNull(publishBlockReason(report, coverage))
    }

    @Test
    fun invalidDiagramIsRevalidatedAndRejectedBeforeGitHubAuthentication() = testApplication {
        val base = testConfig()
        val config = base.copy(
            github = base.github.copy(appId = "1", privateKeyPem = "deliberately-invalid-key")
        )
        val graph = AppGraph.create(config)
        application { module(config, graph) }

        val workspaceId = UUID.fromString(config.auth.devWorkspaceId)
        val projectId = UUID.fromString(config.auth.devProjectId)
        val evidence = EvidenceRef(
            sourceChunkId = "chunk-1",
            startLine = 1,
            endLine = 1,
            quote = "duplicate nodes",
            quoteHash = "hash"
        )
        val ir = DiagramIR(
            diagramId = UUID.randomUUID().toString(),
            title = "Unverified",
            diagramType = DiagramType.ARCHITECTURE,
            nodes = listOf(
                IrNode(id = "duplicate", label = "First", evidence = listOf(evidence)),
                IrNode(id = "duplicate", label = "Second", evidence = listOf(evidence))
            )
        )
        val coverage = EvidenceCoverageScorer.score(ir)
        val diagram = graph.diagrams.createDiagram(
            workspaceId,
            projectId,
            ir.title,
            ir.diagramType.name.lowercase(),
            UUID.fromString(config.auth.devUserId)
        )
        val version = graph.diagrams.appendVersion(
            workspaceId = workspaceId,
            diagramId = diagram.id,
            cause = "GENERATED",
            irJson = IrJson.encode(ir, pretty = false),
            // A stale/forged persisted report must not make an invalid canonical IR publishable.
            validationReportJson = graph.json.encodeToString(
                ValidationReport.serializer(),
                ValidationReport.ok()
            ),
            evidenceCoverageJson = graph.json.encodeToString(
                EvidenceCoverage.serializer(),
                coverage
            ),
            sourceSnapshotJson = "[]",
            modelTraceJson = "[]",
            rendererVersion = "test",
            layoutEngineVersion = "test",
            createdBy = UUID.fromString(config.auth.devUserId)
        )

        val response = client.post(
            "/api/v1/diagrams/${diagram.id}/versions/${version.id}/github/pr"
        ) {
            header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN")
            contentType(ContentType.Application.Json)
            setBody("""{"owner":"octo","repo":"demo","baseBranch":"main"}""")
        }

        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        val body = graph.json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("publish_blocked", body["error"]?.jsonPrimitive?.content)
    }
}
