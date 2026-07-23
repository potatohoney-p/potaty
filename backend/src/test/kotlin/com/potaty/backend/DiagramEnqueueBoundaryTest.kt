/*
 * Copyright (c) 2026, Potaty
 */

package com.potaty.backend

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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DiagramEnqueueBoundaryTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun excessiveSourceVersionListIsRejectedBeforeLookup() = testApplication {
        val config = testConfig()
        application { module(config, AppGraph.create(config)) }
        val ids = (1..101).joinToString(",") { "\"${UUID.randomUUID()}\"" }

        val response =
            client.post("/api/v1/projects/${config.auth.devProjectId}/diagram-jobs") {
                header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN")
                header("Idempotency-Key", "too-many-sources")
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                      "sourceVersionIds": [$ids],
                      "diagramType": "architecture",
                      "outputFormats": ["mermaid"]
                    }
                    """.trimIndent()
                )
            }

        assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
    }

    @Test
    fun duplicateSourceVersionIdsAreRejected() = testApplication {
        val config = testConfig()
        application { module(config, AppGraph.create(config)) }
        val duplicate = UUID.randomUUID()

        val response =
            client.post("/api/v1/projects/${config.auth.devProjectId}/diagram-jobs") {
                header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN")
                header("Idempotency-Key", "duplicate-sources")
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                      "sourceVersionIds": ["$duplicate", "$duplicate"],
                      "diagramType": "architecture",
                      "outputFormats": ["mermaid"]
                    }
                    """.trimIndent()
                )
            }

        assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
    }

    @Test
    fun malformedSourceVersionIdIsRejected() = testApplication {
        val config = testConfig()
        application { module(config, AppGraph.create(config)) }

        val response =
            client.post("/api/v1/projects/${config.auth.devProjectId}/diagram-jobs") {
                header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN")
                header("Idempotency-Key", "malformed-source")
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                      "sourceVersionIds": ["not-a-uuid"],
                      "diagramType": "architecture",
                      "outputFormats": ["mermaid"]
                    }
                    """.trimIndent()
                )
            }

        assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
    }

    @Test
    fun missingSourceVersionInCallerWorkspaceIsRejected() = testApplication {
        val config = testConfig()
        application { module(config, AppGraph.create(config)) }

        val response =
            client.post("/api/v1/projects/${config.auth.devProjectId}/diagram-jobs") {
                header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN")
                header("Idempotency-Key", "missing-source")
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                      "sourceVersionIds": ["${UUID.randomUUID()}"],
                      "diagramType": "architecture",
                      "outputFormats": ["mermaid"]
                    }
                    """.trimIndent()
                )
            }

        assertEquals(HttpStatusCode.NotFound, response.status, response.bodyAsText())
    }

    @Test
    fun sourceVersionCannotBeReusedThroughAnotherProjectPath() = testApplication {
        val config = testConfig()
        val graph = AppGraph.create(config)
        application { module(config, graph) }

        val sourceResponse =
            client.post("/api/v1/projects/${config.auth.devProjectId}/sources") {
                header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN")
                header("Idempotency-Key", "cross-project-source-1")
                contentType(ContentType.Application.Json)
                setBody("""{"sourceType":"TEXT_PASTE","displayName":"owned","content":"A -> B"}""")
            }
        assertEquals(HttpStatusCode.Created, sourceResponse.status, sourceResponse.bodyAsText())
        val sourceVersionId =
            json
                .parseToJsonElement(sourceResponse.bodyAsText())
                .jsonObject["sourceVersionId"]!!
                .jsonPrimitive
                .content

        val response =
            client.post("/api/v1/projects/${UUID.randomUUID()}/diagram-jobs") {
                header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN")
                header("Idempotency-Key", "cross-project-source")
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

        assertEquals(HttpStatusCode.NotFound, response.status, response.bodyAsText())
    }
}
