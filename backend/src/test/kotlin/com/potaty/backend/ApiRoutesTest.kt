/*
 * Copyright (c) 2026, Potaty
 *
 * HTTP integration tests over the full Ktor stack against embedded H2. Covers the P0 auth fix
 * (401 without a token; success with the dev bearer token), the ingest + enqueue path, and the
 * GET-diagram-version route (seeded deterministically via the pipeline, so no async-worker timing
 * dependence). The worker -> pipeline path itself is covered deterministically in
 * DiagramPipelineTest.jobPipelineAdapterProducesVersion.
 */

package com.potaty.backend

import com.potaty.backend.api.CreateSourceRequest
import com.potaty.backend.api.DiagramJobRequest
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
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ApiRoutesTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun auth() = "Bearer $TEST_TOKEN"

    @Test
    fun unauthenticatedRequestIs401() = testApplication {
        val config = testConfig()
        application { module(config, AppGraph.create(config)) }

        val resp =
            client.post("/api/v1/projects/${UUID.randomUUID()}/sources") {
                contentType(ContentType.Application.Json)
                setBody("""{"sourceType":"TEXT_PASTE","displayName":"x","content":"A -> B"}""")
            }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun sourceIngestionRequiresKeyAndReplaysTextAndTranscriptExactlyOnce() = testApplication {
        val config = testConfig()
        val graph = AppGraph.create(config)
        application { module(config, graph) }
        val projectId = config.auth.devProjectId
        val workspaceId = UUID.fromString(config.auth.devWorkspaceId)
        val textBody =
            """{"sourceType":"TEXT_PASTE","displayName":"demo","content":"A -> B"}"""

        val missingKey = client.post("/api/v1/projects/$projectId/sources") {
            header(HttpHeaders.Authorization, auth())
            contentType(ContentType.Application.Json)
            setBody(textBody)
        }
        assertEquals(HttpStatusCode.BadRequest, missingKey.status)

        val missingGitHubKey = client.post("/api/v1/projects/$projectId/github/index-url") {
            header(HttpHeaders.Authorization, auth())
            contentType(ContentType.Application.Json)
            setBody("""{"repoUrl":"https://github.com/octocat/Hello-World"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, missingGitHubKey.status)

        suspend fun postSource(key: String, body: String) =
            client.post("/api/v1/projects/$projectId/sources") {
                header(HttpHeaders.Authorization, auth())
                header("Idempotency-Key", key)
                contentType(ContentType.Application.Json)
                setBody(body)
            }

        val first = postSource("source-replay-text-1", textBody)
        val replay = postSource("source-replay-text-1", textBody)
        assertEquals(HttpStatusCode.Created, first.status)
        assertEquals(HttpStatusCode.Created, replay.status)
        val firstJson = json.parseToJsonElement(first.bodyAsText()).jsonObject
        val replayJson = json.parseToJsonElement(replay.bodyAsText()).jsonObject
        assertEquals(firstJson["sourceId"], replayJson["sourceId"])
        assertEquals(firstJson["sourceVersionId"], replayJson["sourceVersionId"])

        val conflict = postSource(
            "source-replay-text-1",
            """{"sourceType":"TEXT_PASTE","displayName":"demo","content":"A -> C"}"""
        )
        assertEquals(HttpStatusCode.Conflict, conflict.status)

        val transcriptBody =
            """{"sourceType":"TRANSCRIPT","displayName":"call","content":"Alice: ship it"}"""
        val transcriptFirst = postSource("source-replay-transcript-1", transcriptBody)
        val transcriptReplay = postSource("source-replay-transcript-1", transcriptBody)
        assertEquals(HttpStatusCode.Created, transcriptFirst.status)
        assertEquals(HttpStatusCode.Created, transcriptReplay.status)
        assertEquals(
            json.parseToJsonElement(transcriptFirst.bodyAsText()).jsonObject["sourceId"],
            json.parseToJsonElement(transcriptReplay.bodyAsText()).jsonObject["sourceId"]
        )
        assertEquals(
            2,
            graph.sources.listSources(workspaceId, UUID.fromString(projectId)).size,
            "each logical ingestion must create exactly one source"
        )
    }

    @Test
    fun ingestThenEnqueueOverHttp() = testApplication {
        val config = testConfig()
        application { module(config, AppGraph.create(config)) }
        val projectId = config.auth.devProjectId

        val created =
            client.post("/api/v1/projects/$projectId/sources") {
                header(HttpHeaders.Authorization, auth())
                header("Idempotency-Key", "api-ingest-1")
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                      "sourceType": "TEXT_PASTE",
                      "displayName": "demo",
                      "content": "User -> API Gateway: login\nAPI Gateway -> Postgres: read"
                    }
                    """.trimIndent()
                )
            }
        assertEquals(HttpStatusCode.Created, created.status)
        val sourceVersionId =
            json
                .parseToJsonElement(created.bodyAsText())
                .jsonObject["sourceVersionId"]!!
                .jsonPrimitive
                .content

        // missing Idempotency-Key -> 400
        val noKey =
            client.post("/api/v1/projects/$projectId/diagram-jobs") {
                header(HttpHeaders.Authorization, auth())
                contentType(ContentType.Application.Json)
                setBody(
                    """{"sourceVersionIds":["$sourceVersionId"],"diagramType":"architecture"}"""
                )
            }
        assertEquals(HttpStatusCode.BadRequest, noKey.status)

        // with key -> 202
        val jobResp =
            client.post("/api/v1/projects/$projectId/diagram-jobs") {
                header(HttpHeaders.Authorization, auth())
                header("Idempotency-Key", "itest-key-1")
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
            json
                .parseToJsonElement(jobResp.bodyAsText())
                .jsonObject["jobId"]!!
                .jsonPrimitive
                .content
        assertTrue(jobId.isNotBlank())

        // same Idempotency-Key -> same job (dedup, plan 11.3)
        val dup =
            client.post("/api/v1/projects/$projectId/diagram-jobs") {
                header(HttpHeaders.Authorization, auth())
                header("Idempotency-Key", "itest-key-1")
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
        assertEquals(HttpStatusCode.Accepted, dup.status)
        assertEquals(
            jobId,
            json.parseToJsonElement(dup.bodyAsText()).jsonObject["jobId"]!!.jsonPrimitive.content,
            "idempotent enqueue returns the same job"
        )

        val conflictingReplay =
            client.post("/api/v1/projects/$projectId/diagram-jobs") {
                header(HttpHeaders.Authorization, auth())
                header("Idempotency-Key", "itest-key-1")
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                      "sourceVersionIds": ["$sourceVersionId"],
                      "diagramType": "flowchart",
                      "outputFormats": ["mermaid"]
                    }
                    """.trimIndent()
                )
            }
        assertEquals(HttpStatusCode.Conflict, conflictingReplay.status)
    }

    @Test
    fun safetyPreScanRedactsSecretsAtIngestion() = testApplication {
        val config = testConfig()
        val graph = AppGraph.create(config)
        application { module(config, graph) }
        val projectId = config.auth.devProjectId
        val secret = "sk-" + "A".repeat(40)
        val pemBody = "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQ"
        val content =
            """
            key=$secret
            -----BEGIN PRIVATE KEY-----
            $pemBody
            -----END PRIVATE KEY-----
            User -> API Gateway: login
        """
                .trimIndent()

        val resp =
            client.post("/api/v1/projects/$projectId/sources") {
                header(HttpHeaders.Authorization, auth())
                header("Idempotency-Key", "safety-ingest-1")
                contentType(ContentType.Application.Json)
                setBody(
                    json.encodeToString(
                        CreateSourceRequest.serializer(),
                        CreateSourceRequest("TEXT_PASTE", "x", content)
                    )
                )
            }
        assertEquals(HttpStatusCode.Created, resp.status)
        val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertTrue(
            body["secretsRedacted"]!!.jsonPrimitive.content.toInt() >= 1,
            "the secret should have been stripped before storage"
        )
        val workspaceId = UUID.fromString(config.auth.devWorkspaceId)
        val sourceVersionId = UUID.fromString(body["sourceVersionId"]!!.jsonPrimitive.content)
        val storedText =
            graph.sources.listChunks(workspaceId, sourceVersionId).joinToString("\n") {
                it.text
            }
        assertTrue(secret !in storedText, "raw provider key must never persist")
        assertTrue(pemBody !in storedText, "raw PEM body must never persist")
        assertTrue("END PRIVATE KEY" !in storedText, "PEM trailer must never persist")
    }

    @Test
    fun malformedJsonReturns400() = testApplication {
        val config = testConfig()
        application { module(config, AppGraph.create(config)) }
        val resp =
            client.post("/api/v1/projects/${UUID.randomUUID()}/sources") {
                header(HttpHeaders.Authorization, auth())
                header("Idempotency-Key", "malformed-source-1")
                contentType(ContentType.Application.Json)
                setBody("{ this is not valid json ")
            }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertTrue("this is not valid json" !in resp.bodyAsText())
    }

    @Test
    fun textIngestRejectsUnsupportedSourceKindsAndOversizedNames() = testApplication {
        val config = testConfig()
        application { module(config, AppGraph.create(config)) }
        val projectId = config.auth.devProjectId

        val unsupported =
            client.post("/api/v1/projects/$projectId/sources") {
                header(HttpHeaders.Authorization, auth())
                header("Idempotency-Key", "unsupported-source-1")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"sourceType":"GITHUB_REPO","displayName":"x","content":"A -> B"}"""
                )
            }
        assertEquals(HttpStatusCode.BadRequest, unsupported.status)

        val oversizedName =
            client.post("/api/v1/projects/$projectId/sources") {
                header(HttpHeaders.Authorization, auth())
                header("Idempotency-Key", "oversized-name-1")
                contentType(ContentType.Application.Json)
                setBody(
                    json.encodeToString(
                        CreateSourceRequest.serializer(),
                        CreateSourceRequest("TEXT_PASTE", "x".repeat(201), "A -> B")
                    )
                )
            }
        assertEquals(HttpStatusCode.BadRequest, oversizedName.status)
    }

    @Test
    fun unsupportedRendererFormatIsRejectedBeforeJobEnqueue() = testApplication {
        val config = testConfig()
        application { module(config, AppGraph.create(config)) }

        val response =
            client.post("/api/v1/projects/${UUID.randomUUID()}/diagram-jobs") {
                header(HttpHeaders.Authorization, auth())
                header("Idempotency-Key", "unsupported-format")
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                      "sourceVersionIds": ["${UUID.randomUUID()}"],
                      "diagramType": "architecture",
                      "outputFormats": ["ascii"]
                    }
                    """.trimIndent()
                )
            }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("unsupported outputFormats"))
    }

    @Test
    fun diagramJobRejectsInvalidHeaderAndUnimplementedControlValues() = testApplication {
        val config = testConfig()
        application { module(config, AppGraph.create(config)) }
        val projectId = config.auth.devProjectId
        val sourceId = UUID.randomUUID()

        val invalidKey =
            client.post("/api/v1/projects/$projectId/diagram-jobs") {
                header(HttpHeaders.Authorization, auth())
                header("Idempotency-Key", "invalid key with spaces")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"sourceVersionIds":["$sourceId"],"diagramType":"architecture"}"""
                )
            }
        assertEquals(HttpStatusCode.BadRequest, invalidKey.status)

        val unsupportedControls =
            client.post("/api/v1/projects/$projectId/diagram-jobs") {
                header(HttpHeaders.Authorization, auth())
                header("Idempotency-Key", "valid-key")
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                      "sourceVersionIds": ["$sourceId"],
                      "diagramType": "architecture",
                      "qualityMode": "turbo",
                      "llmProviderPreference": "unknown"
                    }
                    """.trimIndent()
                )
            }
        assertEquals(HttpStatusCode.BadRequest, unsupportedControls.status)
    }

    @Test
    fun getDiagramVersionOverHttp() = testApplication {
        val config = testConfig()
        val graph = AppGraph.create(config)
        application { module(config, graph) }

        // Seed a diagram version deterministically via the pipeline (same graph/db the app uses).
        val workspaceId = UUID.fromString(config.auth.devWorkspaceId)
        val userId = UUID.fromString(config.auth.devUserId)
        val projectId = UUID.fromString(config.auth.devProjectId)
        val text =
            "User -> API Gateway: login\n" +
                "API Gateway calls Billing Service\n" +
                "Billing Service writes Postgres"
        val normalized = SourceNormalizer.normalize(text)
        val source =
            graph.sources.createSource(
                workspaceId,
                projectId,
                "TEXT_PASTE",
                "demo",
                "{}",
                userId
            )
        val version =
            graph.sources.createVersion(
                workspaceId,
                source.id,
                "sha256:" + normalized.contentHash,
                null,
                null,
                "{}"
            )
        graph.sources.saveChunks(workspaceId, version.id, Chunker.chunk(normalized.canonicalText))
        val result =
            graph.diagramPipeline.generate(
                workspaceId,
                projectId,
                userId,
                DiagramJobRequest(
                    listOf(version.id.toString()),
                    DiagramType.ARCHITECTURE,
                    outputFormats = listOf("d2")
                )
            )

        val ver =
            client.get("/api/v1/diagrams/${result.diagramId}/versions/${result.versionId}") {
                header(HttpHeaders.Authorization, auth())
            }
        assertEquals(HttpStatusCode.OK, ver.status)
        val body = json.parseToJsonElement(ver.bodyAsText()).jsonObject
        val renderings = body["renderings"]!!.jsonArray
        assertEquals(1, renderings.size)
        assertEquals("d2", renderings.single().jsonObject["format"]!!.jsonPrimitive.content)
        assertEquals(
            true,
            body["validationReport"]!!.jsonObject["valid"]!!.jsonPrimitive.content.toBoolean()
        )

        // a foreign diagram id is not found (tenant scoping / existence check)
        val missing =
            client.get("/api/v1/diagrams/${UUID.randomUUID()}/versions/${result.versionId}") {
                header(HttpHeaders.Authorization, auth())
            }
        assertEquals(HttpStatusCode.NotFound, missing.status)
    }
}
