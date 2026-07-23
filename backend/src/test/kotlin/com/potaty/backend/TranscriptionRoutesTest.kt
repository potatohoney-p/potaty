/*
 * Copyright (c) 2026, Potaty
 *
 * HTTP orchestration tests for the transcription spend boundary.
 */

package com.potaty.backend

import com.potaty.backend.api.ApiError
import com.potaty.backend.auth.installSessionAuth
import com.potaty.backend.cost.CostReservationConflictException
import com.potaty.backend.cost.CostReservationStateConflictException
import com.potaty.backend.llm.auth.ApiKeyCredential
import com.potaty.backend.llm.auth.EnvelopeCredentialStore
import com.potaty.backend.llm.provider.ProviderId
import com.potaty.backend.transcription.AudioInput
import com.potaty.backend.transcription.AudioTranscriptionService
import com.potaty.backend.transcription.TranscriptionBodyReadResult
import com.potaty.backend.transcription.TranscriptionCompleter
import com.potaty.backend.transcription.TranscriptionCredentialResolver
import com.potaty.backend.transcription.readBoundedTranscriptionRequest
import com.potaty.backend.transcription.transcriptionRequestHash
import com.potaty.backend.transcription.transcriptionRoutes
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteReadChannel
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import io.ktor.client.engine.mock.respond as mockRespond

class TranscriptionRoutesTest {

    @Test
    fun requestBodyIsBoundedBeforeJsonDeserializationWithOrWithoutContentLength() = runBlocking {
        val json = Json { ignoreUnknownKeys = true }
        val valid = """{"credentialId":"c","audioBase64":"YQ=="}"""
        val decoded =
            readBoundedTranscriptionRequest(
                input = ByteReadChannel(valid),
                declaredContentLength = valid.toByteArray().size.toLong(),
                json = json,
                maxBytes = 128
            )
        assertEquals(
            "c",
            assertIs<TranscriptionBodyReadResult.Success>(decoded).request.credentialId
        )

        assertEquals(
            TranscriptionBodyReadResult.TooLarge,
            readBoundedTranscriptionRequest(
                input = ByteReadChannel(valid),
                declaredContentLength = 129,
                json = json,
                maxBytes = 128
            )
        )
        assertEquals(
            TranscriptionBodyReadResult.TooLarge,
            readBoundedTranscriptionRequest(
                input = ByteReadChannel(ByteArray(129) { 'x'.code.toByte() }),
                declaredContentLength = null,
                json = json,
                maxBytes = 128
            )
        )
    }

    @Test
    fun canonicalRequestHashIncludesProviderModelAndStage() {
        val projectId = UUID.randomUUID()
        val audio = AudioInput("audio".toByteArray(), "meeting.mp3", "audio/mpeg")
        fun hash(provider: String, model: String, stage: String) =
            transcriptionRequestHash(
                projectId = projectId,
                credentialId = "deployment-openai",
                audio = audio,
                displayName = "Release meeting",
                provider = provider,
                model = model,
                stage = stage
            )

        val baseline = hash("openai", "whisper-1", "transcription")
        assertNotEquals(baseline, hash("openai", "gpt-4o-transcribe", "transcription"))
        assertNotEquals(baseline, hash("other", "whisper-1", "transcription"))
        assertNotEquals(baseline, hash("openai", "whisper-1", "alternate-stage"))
    }

    @Test
    fun unknownOrCrossTenantProjectIsRejectedBeforeCredentialResolutionAndProviderSpend() =
        testApplication {
            val config = testConfig()
            val graph = AppGraph.create(config)
            val providerCalls = AtomicInteger()
            val resolverCalls = AtomicInteger()
            val providerClient =
                io.ktor.client.HttpClient(
                    MockEngine {
                        providerCalls.incrementAndGet()
                        respondOk("""{"text":"must not run","segments":[]}""")
                    }
                )
            val service =
                AudioTranscriptionService(
                    "https://api.openai.test",
                    providerClient,
                    EnvelopeCredentialStore(config.security.credentialMasterKeyRef)
                )

            application {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                install(StatusPages) {
                    exception<CostReservationConflictException> { call, cause ->
                        call.respond(
                            HttpStatusCode.Conflict,
                            ApiError("idempotency_conflict", cause.message ?: "conflict")
                        )
                    }
                    exception<CostReservationStateConflictException> { call, cause ->
                        call.respond(
                            HttpStatusCode.Conflict,
                            ApiError("external_spend_conflict", cause.message ?: "conflict")
                        )
                    }
                }
                installSessionAuth(graph.sessionStore)
                routing {
                    route("/api/v1") {
                        transcriptionRoutes(
                            service = service,
                            credentialResolver =
                            TranscriptionCredentialResolver { _, _ ->
                                resolverCalls.incrementAndGet()
                                null
                            },
                            ingestor = graph.transcriptIngestor,
                            completer = graph.transcriptionCompleter,
                            projectExists = { _, _ -> false },
                            transcriptionModel = "whisper-1",
                            quotaGuard = graph.quotaGuard,
                            usage = graph.usage,
                            costConfig = graph.costConfig
                        )
                    }
                }
            }

            try {
                val response =
                    client.post(
                        "/api/v1/projects/${UUID.randomUUID()}/transcription"
                    ) {
                        header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN")
                        contentType(ContentType.Application.Json)
                        setBody(
                            """
                            {
                              "credentialId": "deployment-openai",
                              "audioBase64": "YXVkaW8=",
                              "mimeType": "audio/mpeg"
                            }
                            """.trimIndent()
                        )
                    }
                assertEquals(HttpStatusCode.NotFound, response.status)
                assertEquals(0, resolverCalls.get())
                assertEquals(0, providerCalls.get())
                assertEquals(
                    0.0,
                    graph.usage.sumCostThisMonth(UUID.fromString(config.auth.devWorkspaceId))
                )
            } finally {
                providerClient.close()
                graph.stop()
            }
        }

    @Test
    fun checkpointResumesAfterLeaseAndReplaysWithoutCallingProviderAgain() =
        testApplication {
            val config = testConfig()
            val graph = AppGraph.create(config)
            val workspaceId = UUID.fromString(config.auth.devWorkspaceId)
            val credentialStore =
                EnvelopeCredentialStore(config.security.credentialMasterKeyRef)
            val credential =
                ApiKeyCredential(
                    id = "deployment-openai",
                    workspaceId = workspaceId.toString(),
                    provider = ProviderId.OPENAI,
                    encryptedApiKeyRef =
                    credentialStore.seal(workspaceId.toString(), "sk-synthetic-test-key").value,
                    label = "test",
                    createdByUserId = config.auth.devUserId
                )
            val providerCalls = AtomicInteger()
            val completionCalls = AtomicInteger()
            val credentialAvailable = AtomicBoolean(true)
            val credentialResolutionCalls = AtomicInteger()
            val now = AtomicReference(Instant.parse("2026-06-15T12:00:00Z"))
            val providerClient =
                io.ktor.client.HttpClient(
                    MockEngine {
                        providerCalls.incrementAndGet()
                        respondOk(
                            """
                            {
                              "text":"Alice: ship the API",
                              "segments":[
                                {"id":0,"start":0.0,"end":1.0,"speaker":"Alice","text":"ship the API"}
                              ]
                            }
                            """.trimIndent()
                        )
                    }
                )
            val service =
                AudioTranscriptionService(
                    "https://api.openai.test",
                    providerClient,
                    credentialStore
                )
            val flakyCompleter =
                TranscriptionCompleter { command ->
                    if (completionCalls.incrementAndGet() == 1) {
                        error("synthetic completion interruption")
                    }
                    graph.transcriptionCompleter.complete(command)
                }

            application {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                install(StatusPages) {
                    exception<CostReservationConflictException> { call, cause ->
                        call.respond(
                            HttpStatusCode.Conflict,
                            ApiError("idempotency_conflict", cause.message ?: "conflict")
                        )
                    }
                    exception<CostReservationStateConflictException> { call, cause ->
                        call.respond(
                            HttpStatusCode.Conflict,
                            ApiError("external_spend_conflict", cause.message ?: "conflict")
                        )
                    }
                    exception<IllegalStateException> { call, _ ->
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            ApiError("internal_error", "synthetic completion interruption")
                        )
                    }
                }
                installSessionAuth(graph.sessionStore)
                routing {
                    route("/api/v1") {
                        transcriptionRoutes(
                            service = service,
                            credentialResolver =
                            TranscriptionCredentialResolver { _, _ ->
                                credentialResolutionCalls.incrementAndGet()
                                credential.takeIf { credentialAvailable.get() }
                            },
                            ingestor = graph.transcriptIngestor,
                            completer = flakyCompleter,
                            projectExists = { _, _ -> true },
                            transcriptionModel = "whisper-1",
                            quotaGuard = graph.quotaGuard,
                            usage = graph.usage,
                            costConfig = graph.costConfig,
                            clock = { now.get() }
                        )
                    }
                }
            }

            suspend fun send(audioBase64: String) =
                client.post(
                    "/api/v1/projects/${config.auth.devProjectId}/transcription"
                ) {
                    header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN")
                    header("Idempotency-Key", "transcription-replay-1")
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "credentialId":"deployment-openai",
                          "audioBase64":"$audioBase64",
                          "mimeType":"audio/mpeg",
                          "fileName":"meeting.mp3",
                          "displayName":"Release meeting"
                        }
                        """.trimIndent()
                    )
                }

            try {
                val first = send("YXVkaW8=")
                assertEquals(HttpStatusCode.InternalServerError, first.status)
                assertEquals(1, providerCalls.get())
                assertEquals(1, credentialResolutionCalls.get())
                credentialAvailable.set(false)

                val stillProcessing = send("YXVkaW8=")
                assertEquals(HttpStatusCode.Conflict, stillProcessing.status)
                assertEquals(1, providerCalls.get())

                now.set(now.get().plus(Duration.ofMinutes(11)))
                val resumed = send("YXVkaW8=")
                assertEquals(HttpStatusCode.Created, resumed.status)
                val completedBody = resumed.bodyAsText()

                val replay = send("YXVkaW8=")
                assertEquals(HttpStatusCode.Created, replay.status)
                assertEquals(completedBody, replay.bodyAsText())
                assertEquals(1, providerCalls.get())
                assertEquals(1, credentialResolutionCalls.get())
                assertEquals(2, completionCalls.get())
                assertEquals(
                    1,
                    graph.sources
                        .listSources(
                            workspaceId,
                            UUID.fromString(config.auth.devProjectId)
                        ).size
                )

                val conflict = send("YXVkaW8y")
                assertEquals(HttpStatusCode.Conflict, conflict.status)
                assertEquals(1, providerCalls.get())
            } finally {
                providerClient.close()
                graph.stop()
            }
        }

    @Test
    fun definitiveProviderRejectionReplaysAtZeroCost() =
        testApplication {
            val config = testConfig()
            val graph = AppGraph.create(config)
            val workspaceId = UUID.fromString(config.auth.devWorkspaceId)
            val projectId = UUID.fromString(config.auth.devProjectId)
            val credentialStore =
                EnvelopeCredentialStore(config.security.credentialMasterKeyRef)
            val credential =
                ApiKeyCredential(
                    id = "deployment-openai",
                    workspaceId = workspaceId.toString(),
                    provider = ProviderId.OPENAI,
                    encryptedApiKeyRef =
                    credentialStore.seal(workspaceId.toString(), "sk-synthetic-test-key").value,
                    label = "test",
                    createdByUserId = config.auth.devUserId
                )
            val providerCalls = AtomicInteger()
            val providerClient =
                io.ktor.client.HttpClient(
                    MockEngine {
                        providerCalls.incrementAndGet()
                        mockRespond(
                            content = """{"error":"rejected"}""",
                            status = HttpStatusCode.BadRequest
                        )
                    }
                )
            val service =
                AudioTranscriptionService(
                    "https://api.openai.test",
                    providerClient,
                    credentialStore
                )

            application {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                install(StatusPages) {
                    exception<CostReservationConflictException> { call, cause ->
                        call.respond(
                            HttpStatusCode.Conflict,
                            ApiError("idempotency_conflict", cause.message ?: "conflict")
                        )
                    }
                    exception<CostReservationStateConflictException> { call, cause ->
                        call.respond(
                            HttpStatusCode.Conflict,
                            ApiError("external_spend_conflict", cause.message ?: "conflict")
                        )
                    }
                }
                installSessionAuth(graph.sessionStore)
                routing {
                    route("/api/v1") {
                        transcriptionRoutes(
                            service = service,
                            credentialResolver =
                            TranscriptionCredentialResolver { _, _ -> credential },
                            ingestor = graph.transcriptIngestor,
                            completer = graph.transcriptionCompleter,
                            projectExists = { _, _ -> true },
                            transcriptionModel = "whisper-1",
                            quotaGuard = graph.quotaGuard,
                            usage = graph.usage,
                            costConfig = graph.costConfig
                        )
                    }
                }
            }

            suspend fun send() =
                client.post("/api/v1/projects/$projectId/transcription") {
                    header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN")
                    header("Idempotency-Key", "transcription-rejected-1")
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "credentialId": "deployment-openai",
                          "audioBase64": "YXVkaW8=",
                          "mimeType": "audio/mpeg"
                        }
                        """.trimIndent()
                    )
                }

            try {
                val first = send()
                val replay = send()
                assertEquals(HttpStatusCode.BadRequest, first.status)
                assertEquals(HttpStatusCode.BadRequest, replay.status)
                assertEquals(first.bodyAsText(), replay.bodyAsText())
                assertEquals(1, providerCalls.get())
                assertEquals(0.0, graph.usage.sumCostThisMonth(workspaceId), 1e-9)
                assertEquals(0.0, graph.quotaGuard.snapshot(workspaceId).reservedUsd, 1e-9)
                assertEquals(0, graph.sources.listSources(workspaceId, projectId).size)
            } finally {
                providerClient.close()
                graph.stop()
            }
        }
}
