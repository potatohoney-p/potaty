/*
 * Copyright (c) 2026, Potaty
 *
 * Cross-tenant isolation over the full Ktor stack against embedded H2 (plan 20.5). Two
 * workspaces share one process and one database; each holds its own bearer token. Workspace A
 * creates a diagram (via the deterministic pipeline) and a job (over HTTP); workspace B must be
 * unable to observe ANY of it:
 *
 *   - GET diagram version  -> 404 for B (B's workspace filter excludes A's version)
 *   - GET job              -> 404 for B
 *   - repository reads      -> null for B (source / version / diagram / job are workspace-scoped)
 *
 * The two tokens are issued into the SAME SessionStore (so both resolve under the one app), but
 * each is bound to a DIFFERENT workspace id — exactly how production workspace-scoped tokens work
 * (see SessionStore docs). Isolation is therefore proven by the data layer, not by using separate
 * databases.
 */

package com.potaty.backend

import com.potaty.backend.api.DiagramJobRequest
import com.potaty.backend.auth.TenantContext
import com.potaty.backend.auth.WorkspaceRole
import com.potaty.backend.source.Chunker
import com.potaty.backend.source.SourceNormalizer
import com.potaty.ir.DiagramType
import io.ktor.client.request.get
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class TenantIsolationTest {

    private val json = Json { ignoreUnknownKeys = true }

    // Workspace B's identity. Workspace A is the seeded dev token (TEST_TOKEN / config.auth.dev*).
    private val wsBWorkspaceId = UUID.fromString("00000000-0000-0000-0000-0000000000bb")
    private val wsBUserId = UUID.fromString("00000000-0000-0000-0000-0000000000cc")

    private fun bearer(token: String) = "Bearer $token"

    @Test
    fun workspaceBCannotReadWorkspaceAsDiagramVersion() = testApplication {
        val config = testConfig()
        val graph = AppGraph.create(config)
        application { module(config, graph) }

        // Issue a second, workspace-B-scoped token into the same session store the app uses.
        val tokenB =
            graph.sessionStore.issue(
                TenantContext(
                    workspaceId = wsBWorkspaceId.toString(),
                    userId = wsBUserId.toString(),
                    role = WorkspaceRole.OWNER
                )
            )

        // --- Workspace A seeds a diagram via the pipeline (same graph/db the app serves) ---
        val workspaceA = UUID.fromString(config.auth.devWorkspaceId)
        val userA = UUID.fromString(config.auth.devUserId)
        val projectA = UUID.fromString(config.auth.devProjectId)
        val text =
            "User -> API Gateway: login\n" +
                "API Gateway calls Billing Service\n" +
                "Billing Service writes Postgres"
        val normalized = SourceNormalizer.normalize(text)
        val source =
            graph.sources.createSource(
                workspaceA,
                projectA,
                "TEXT_PASTE",
                "demo",
                "{}",
                userA
            )
        val version =
            graph.sources.createVersion(
                workspaceA,
                source.id,
                "sha256:" + normalized.contentHash,
                null,
                null,
                "{}"
            )
        graph.sources.saveChunks(workspaceA, version.id, Chunker.chunk(normalized.canonicalText))
        val result =
            graph.diagramPipeline.generate(
                workspaceA,
                projectA,
                userA,
                DiagramJobRequest(
                    listOf(version.id.toString()),
                    DiagramType.ARCHITECTURE,
                    outputFormats = listOf("mermaid")
                )
            )

        // --- Workspace A can read its own version (control) ---
        val aReads =
            client.get("/api/v1/diagrams/${result.diagramId}/versions/${result.versionId}") {
                header(HttpHeaders.Authorization, bearer(TEST_TOKEN))
            }
        assertEquals(
            HttpStatusCode.OK,
            aReads.status,
            "owner workspace must read its own diagram version"
        )

        // --- Workspace B is denied (404, not 403/200): the row is invisible to it ---
        val bReads =
            client.get("/api/v1/diagrams/${result.diagramId}/versions/${result.versionId}") {
                header(HttpHeaders.Authorization, bearer(tokenB))
            }
        assertEquals(
            HttpStatusCode.NotFound,
            bReads.status,
            "workspace B must NOT be able to read workspace A's diagram version"
        )

        // --- And the repository itself is workspace-scoped (no cross-tenant read path) ---
        assertNull(
            graph.diagrams.findVersion(wsBWorkspaceId, result.diagramId, result.versionId),
            "diagram version must be invisible to workspace B at the data layer"
        )
        assertNull(
            graph.diagrams.findDiagram(wsBWorkspaceId, result.diagramId),
            "diagram must be invisible to workspace B at the data layer"
        )
        // Teardown is handled by the ApplicationStopping hook registered in module()
        // (graph.stop()).
    }

    @Test
    fun sourceAndJobReadsAreWorkspaceIsolated() = testApplication {
        val config = testConfig()
        val graph = AppGraph.create(config)
        application { module(config, graph) }

        val tokenB =
            graph.sessionStore.issue(
                TenantContext(
                    workspaceId = wsBWorkspaceId.toString(),
                    userId = wsBUserId.toString(),
                    role = WorkspaceRole.OWNER
                )
            )

        val workspaceA = UUID.fromString(config.auth.devWorkspaceId)
        val projectA = UUID.fromString(config.auth.devProjectId)

        // --- Workspace A ingests a source over HTTP ---
        val created =
            client.post("/api/v1/projects/$projectA/sources") {
                header(HttpHeaders.Authorization, bearer(TEST_TOKEN))
                header("Idempotency-Key", "tenant-source-1")
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                      "sourceType": "TEXT_PASTE",
                      "displayName": "demo",
                      "content": "User -> API Gateway: login"
                    }
                    """.trimIndent()
                )
            }
        assertEquals(HttpStatusCode.Created, created.status)
        val createdBody = json.parseToJsonElement(created.bodyAsText()).jsonObject
        val sourceId = UUID.fromString(createdBody["sourceId"]!!.jsonPrimitive.content)
        val sourceVersionId = createdBody["sourceVersionId"]!!.jsonPrimitive.content

        // --- Workspace A enqueues a job over HTTP ---
        val jobResp =
            client.post("/api/v1/projects/$projectA/diagram-jobs") {
                header(HttpHeaders.Authorization, bearer(TEST_TOKEN))
                header("Idempotency-Key", "iso-key-1")
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                      "sourceVersionIds": ["$sourceVersionId"],
                      "diagramType": "architecture",
                      "outputFormats": ["mermaid"]
                    }
                    """.trimIndent()
                )
            }
        assertEquals(HttpStatusCode.Accepted, jobResp.status)
        val jobId =
            UUID.fromString(
                json
                    .parseToJsonElement(jobResp.bodyAsText())
                    .jsonObject["jobId"]!!
                    .jsonPrimitive
                    .content
            )

        // --- Workspace A can poll its own job (control) ---
        val aJob =
            client.get("/api/v1/jobs/$jobId") {
                header(HttpHeaders.Authorization, bearer(TEST_TOKEN))
            }
        assertEquals(HttpStatusCode.OK, aJob.status, "owner workspace must read its own job")

        // --- Workspace B cannot see workspace A's job (404) ---
        val bJob =
            client.get("/api/v1/jobs/$jobId") {
                header(HttpHeaders.Authorization, bearer(tokenB))
            }
        assertEquals(
            HttpStatusCode.NotFound,
            bJob.status,
            "workspace B must NOT see workspace A's job"
        )

        // --- Repository-level isolation: source, version chunks, and job are scoped ---
        assertTrue(
            graph.sources.findSource(workspaceA, sourceId) != null,
            "workspace A owns the source"
        )
        assertNull(
            graph.sources.findSource(wsBWorkspaceId, sourceId),
            "workspace B must not read workspace A's source"
        )
        assertTrue(
            graph.sources.listChunks(workspaceA, UUID.fromString(sourceVersionId)).isNotEmpty(),
            "workspace A sees its own chunks"
        )
        assertTrue(
            graph.sources.listChunks(wsBWorkspaceId, UUID.fromString(sourceVersionId)).isEmpty(),
            "workspace B must not read workspace A's chunks"
        )
        assertNull(
            graph.jobs.findById(wsBWorkspaceId, jobId),
            "workspace B must not read workspace A's job at the data layer"
        )
        // Teardown is handled by the ApplicationStopping hook registered in module()
        // (graph.stop()).
    }
}
