/*
 * Copyright (c) 2026, Potaty
 *
 * HTTP tests for the admin diagnostics routes (WS15) over the full Ktor stack against H2. The dev
 * bearer token authenticates as OWNER of the dev workspace, which holds MANAGE_WORKSPACE, so the
 * admin routes are reachable. Covers: job diagnostics (seeded via the graph's JobRepository),
 * a foreign job id -> 404 (tenant scoping / existence), month-to-date usage (seeded via
 * UsageRecorder), and that the /metrics endpoint is reachable WITHOUT a token and emits Prometheus
 * text. Deterministic; no network.
 */

package com.potaty.backend

import com.potaty.backend.cost.CostEstimate
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
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class AdminRoutesTest {

    private val json = Json { ignoreUnknownKeys = true }
    private fun auth() = "Bearer $TEST_TOKEN"

    @Test
    fun jobDiagnosticsForOwnWorkspace() = testApplication {
        val config = testConfig()
        val graph = AppGraph.create(config)
        application { module(config, graph) }

        val workspaceId = UUID.fromString(config.auth.devWorkspaceId)
        val userId = UUID.fromString(config.auth.devUserId)
        val projectId = UUID.fromString(config.auth.devProjectId)

        val job = runBlocking {
            graph.jobs.enqueue(
                workspaceId = workspaceId,
                projectId = projectId,
                jobType = "DIAGRAM_GENERATION",
                idempotencyKey = "admin-itest-1",
                inputJson = "{}",
                createdBy = userId
            )
        }

        val resp = client.get("/api/v1/admin/jobs/${job.id}") {
            header(HttpHeaders.Authorization, auth())
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals(job.id.toString(), body["jobId"]!!.jsonPrimitive.content)
        assertEquals(config.auth.devWorkspaceId, body["workspaceId"]!!.jsonPrimitive.content)
        assertEquals("DIAGRAM_GENERATION", body["jobType"]!!.jsonPrimitive.content)
        assertEquals(3, body["maxAttempts"]!!.jsonPrimitive.content.toInt())
        // The background worker pool may have begun processing the job by now, so assert the
        // status is one of the known wire values rather than pinning it to "queued" (avoids a race).
        val status = body["status"]!!.jsonPrimitive.content
        assertTrue(
            status in setOf("queued", "running", "succeeded", "failed", "needs_input", "cancelled"),
            "unexpected job status '$status'"
        )
    }

    @Test
    fun unknownJobIs404() = testApplication {
        val config = testConfig()
        application { module(config, AppGraph.create(config)) }

        val resp = client.get("/api/v1/admin/jobs/${UUID.randomUUID()}") {
            header(HttpHeaders.Authorization, auth())
        }
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    @Test
    fun adminRoutesRequireAuthentication() = testApplication {
        val config = testConfig()
        application { module(config, AppGraph.create(config)) }

        val resp = client.get("/api/v1/admin/usage")
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun usageReportsMonthToDateCost() = testApplication {
        val config = testConfig()
        val graph = AppGraph.create(config)
        application { module(config, graph) }

        val workspaceId = UUID.fromString(config.auth.devWorkspaceId)
        runBlocking {
            graph.usage.record(
                workspaceId = workspaceId,
                jobId = null,
                provider = "openai",
                model = "gpt-4o-mini",
                stage = "extract",
                inputTokens = 1000,
                outputTokens = 500,
                estimatedCostUsd = 0.25
            )
        }

        val resp = client.get("/api/v1/admin/usage") {
            header(HttpHeaders.Authorization, auth())
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals(workspaceId.toString(), body["workspaceId"]!!.jsonPrimitive.content)
        assertEquals(0.25, body["monthToDateCostUsd"]!!.jsonPrimitive.content.toDouble())
    }

    @Test
    fun pendingExternalSpendRequiresConfirmationAndCanBeChargedByOwner() = testApplication {
        val config = testConfig()
        val graph = AppGraph.create(config)
        application { module(config, graph) }
        val workspaceId = UUID.fromString(config.auth.devWorkspaceId)
        val reservation =
            runBlocking {
                graph.quotaGuard.reserve(
                    workspaceId = workspaceId,
                    reservationKey = "transcription:admin-reconcile",
                    estimate = CostEstimate(1.0, 3.0),
                    requestHash = "sha256:${"d".repeat(64)}",
                    externalOperation = "transcription",
                    externalProvider = "openai",
                    externalModel = "whisper-1",
                    externalStage = "transcription",
                    externalMetadataJson = """{"projectId":"${config.auth.devProjectId}"}"""
                ).also {
                    graph.quotaGuard.markExternalSpendStarted(
                        workspaceId,
                        it.id,
                        Instant.now().minus(Duration.ofMinutes(11))
                    )
                }
            }

        val pending = client.get("/api/v1/admin/external-spend/pending") {
            header(HttpHeaders.Authorization, auth())
        }
        assertEquals(HttpStatusCode.OK, pending.status)
        val pendingItems =
            json.parseToJsonElement(pending.bodyAsText()).jsonObject["items"]!!.jsonArray
        assertEquals(1, pendingItems.size)

        val unconfirmed =
            client.post("/api/v1/admin/external-spend/${reservation.id}/reconcile") {
                header(HttpHeaders.Authorization, auth())
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                      "decision": "charge",
                      "chargeUsd": 1.5,
                      "reason": "Matched provider receipt.",
                      "confirm": false
                    }
                    """.trimIndent()
                )
            }
        assertEquals(HttpStatusCode.BadRequest, unconfirmed.status)

        val databaseOverflow =
            client.post("/api/v1/admin/external-spend/${reservation.id}/reconcile") {
                header(HttpHeaders.Authorization, auth())
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                      "decision": "charge",
                      "chargeUsd": 1000000.0,
                      "reason": "Must fit the PostgreSQL numeric column.",
                      "confirm": true
                    }
                    """.trimIndent()
                )
            }
        assertEquals(HttpStatusCode.BadRequest, databaseOverflow.status)

        val missingReceiptAmount =
            client.post("/api/v1/admin/external-spend/${reservation.id}/reconcile") {
                header(HttpHeaders.Authorization, auth())
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                      "decision": "charge",
                      "reason": "Matched provider receipt but omitted its amount.",
                      "confirm": true
                    }
                    """.trimIndent()
                )
            }
        assertEquals(HttpStatusCode.BadRequest, missingReceiptAmount.status)

        val reconciled =
            client.post("/api/v1/admin/external-spend/${reservation.id}/reconcile") {
                header(HttpHeaders.Authorization, auth())
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                      "decision": "charge",
                      "chargeUsd": 1.5,
                      "reason": "Matched provider receipt.",
                      "confirm": true
                    }
                    """.trimIndent()
                )
            }
        assertEquals(HttpStatusCode.OK, reconciled.status)
        val body = json.parseToJsonElement(reconciled.bodyAsText()).jsonObject
        assertEquals("charge", body["decision"]!!.jsonPrimitive.content)
        assertEquals(1.5, body["chargedUsd"]!!.jsonPrimitive.content.toDouble())
        assertEquals(1.5, runBlocking { graph.usage.sumCostThisMonth(workspaceId) }, 1e-9)

        val after = client.get("/api/v1/admin/external-spend/pending") {
            header(HttpHeaders.Authorization, auth())
        }
        assertEquals(
            0,
            json.parseToJsonElement(after.bodyAsText()).jsonObject["items"]!!.jsonArray.size
        )
    }

    @Test
    fun metricsEndpointIsUnauthenticatedPrometheusText() = testApplication {
        val config = testConfig()
        application { module(config, AppGraph.create(config)) }

        // No Authorization header.
        val resp = client.get("/metrics")
        assertEquals(HttpStatusCode.OK, resp.status)
        val contentType = resp.headers[HttpHeaders.ContentType] ?: ""
        assertTrue(
            contentType.startsWith("text/plain"),
            "Prometheus exposition is text/plain (was '$contentType')"
        )
    }
}
