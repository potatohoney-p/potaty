/*
 * Copyright (c) 2026, Potaty
 */

package com.potaty.backend

import com.potaty.backend.jobs.JobStatus
import com.potaty.backend.jobs.isTerminal
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class JobRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun terminalStatusContractCoversEveryNoSpendState() {
        assertEquals(false, JobStatus.QUEUED.isTerminal)
        assertEquals(false, JobStatus.RUNNING.isTerminal)
        assertEquals(true, JobStatus.SUCCEEDED.isTerminal)
        assertEquals(true, JobStatus.FAILED.isTerminal)
        assertEquals(true, JobStatus.NEEDS_INPUT.isTerminal)
        assertEquals(true, JobStatus.CANCELLED.isTerminal)
    }

    @Test
    fun pollingReturnsPersistedTimelineAndDerivedProgress() = testApplication {
        val config = testConfig()
        val graph = AppGraph.create(config)
        val workspaceId = UUID.fromString(config.auth.devWorkspaceId)
        val projectId = UUID.fromString(config.auth.devProjectId)
        val userId = UUID.fromString(config.auth.devUserId)
        val job = runBlocking {
            graph.jobs.enqueue(
                workspaceId = workspaceId,
                projectId = projectId,
                jobType = "DIAGRAM_GENERATION",
                idempotencyKey = "job-events-${UUID.randomUUID()}",
                inputJson = "{}",
                createdBy = userId
            ).also { queued ->
                // NEEDS_INPUT is stable while the test server starts; queued/running rows are
                // intentionally eligible for worker recovery and would make this assertion race.
                graph.jobs.markStatus(
                    workspaceId,
                    queued.id,
                    JobStatus.NEEDS_INPUT,
                    errorJson = """{"needsInput":"Add named steps or explicit relationships"}"""
                )
                graph.jobs.recordEvent(
                    workspaceId,
                    queued.id,
                    "STAGE_PROGRESS",
                    "SOURCE_NORMALIZER",
                    "loading sources"
                )
                graph.jobs.recordEvent(
                    workspaceId,
                    queued.id,
                    "STAGE_PROGRESS",
                    "IR_VALIDATOR",
                    "validating evidence"
                )
            }
        }
        application { module(config, graph) }

        val response = client.get("/api/v1/jobs/${job.id}") {
            header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("IR_VALIDATOR", body["currentStage"]!!.jsonPrimitive.content)
        assertEquals(2, body["events"]!!.jsonArray.size)
        assertEquals(
            "loading sources",
            body["events"]!!.jsonArray.first().jsonObject["message"]!!.jsonPrimitive.content
        )
        val progress = body["progress"]!!.jsonPrimitive.content.toDouble()
        assertEquals(1.0, progress)
        assertEquals(
            "Add named steps or explicit relationships",
            body["reason"]!!.jsonPrimitive.content
        )
    }
}
