/*
 * Copyright (c) 2026, Potaty
 *
 * WS12 audio transcription tests (plan 14.4 / 4.3). A MockEngine returns a canned OpenAI
 * verbose_json body (no network, no real key). Asserts that:
 *   - the verbose_json is parsed into a TranscriptArtifact whose segments carry ms ranges (start/end
 *     in seconds -> ms) and the speaker when present,
 *   - the request targets /v1/audio/transcriptions with a Bearer auth header (resolved server-side),
 *   - ingesting the artifact creates a TRANSCRIPT source + version + chunks, persisted tenant-scoped
 *     in H2, with speaker / startMs / endMs evidence preserved on the chunks.
 */

package com.potaty.backend

import com.potaty.backend.cost.CostConfig
import com.potaty.backend.llm.auth.ApiKeyCredential
import com.potaty.backend.llm.auth.CredentialStore
import com.potaty.backend.llm.auth.EncryptedSecretRef
import com.potaty.backend.llm.provider.LlmError
import com.potaty.backend.llm.provider.LlmErrorKind
import com.potaty.backend.llm.provider.LlmResult
import com.potaty.backend.llm.provider.ProviderId
import com.potaty.backend.llm.provider.TranscriptArtifact
import com.potaty.backend.llm.provider.TranscriptSegment
import com.potaty.backend.transcription.AudioInput
import com.potaty.backend.transcription.AudioTranscriptionService
import com.potaty.backend.transcription.TranscriptIngestor
import com.potaty.backend.transcription.TranscriptionRequest
import com.potaty.backend.transcription.hasExactlyOneAudioSource
import com.potaty.backend.transcription.hasValidTranscriptionMetadata
import com.potaty.backend.transcription.isDefinitivelyUnbilled
import com.potaty.backend.transcription.isInlineAudioEncodedLengthAllowed
import com.potaty.backend.transcription.safeTranscriptionFailure
import com.potaty.backend.transcription.transcriptionActualCost
import com.potaty.backend.transcription.transcriptionReservationEstimate
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class AudioTranscriptionServiceTest {

    @Test
    fun transcriptionRequestCarriesCredentialIdButNeverEncryptedSecretReference() {
        val encoded =
            Json.encodeToString(
                TranscriptionRequest(
                    credentialId = "deployment-openai",
                    audioBase64 = "YXVkaW8="
                )
            )
        assertTrue("credentialId" in encoded)
        assertTrue("encrypted" !in encoded.lowercase())
        assertTrue("secret" !in encoded.lowercase())
    }

    @Test
    fun audioSourceContractAndEncodedPreflightFailClosed() {
        assertTrue(hasExactlyOneAudioSource("YQ==", null))
        assertTrue(hasExactlyOneAudioSource(null, "object/audio.mp3"))
        assertTrue(!hasExactlyOneAudioSource(null, null))
        assertTrue(!hasExactlyOneAudioSource("YQ==", "object/audio.mp3"))

        val maxEncoded = ((25 * 1024 * 1024 + 2) / 3) * 4
        assertTrue(isInlineAudioEncodedLengthAllowed(maxEncoded))
        assertTrue(!isInlineAudioEncodedLengthAllowed(maxEncoded + 1))
        assertTrue(!isInlineAudioEncodedLengthAllowed(0))

        assertTrue(hasValidTranscriptionMetadata("meeting.m4a", "audio/m4a", "Standup", null))
        assertFalse(hasValidTranscriptionMetadata("bad\r\nname", "audio/mpeg", "Standup", null))
        assertFalse(hasValidTranscriptionMetadata("meeting.exe", "text/html", "Standup", null))
        assertFalse(hasValidTranscriptionMetadata("meeting.mp3", "audio/mpeg", "  ", null))
    }

    @Test
    fun transcriptionCostUsesConservativeReservationAndObservedDuration() {
        val config = CostConfig(monthlyCapUsd = 50.0)
        val reservation = transcriptionReservationEstimate(25 * 1024 * 1024, config)
        assertTrue(reservation.highUsd > 5.0)

        val artifact =
            TranscriptArtifact(
                text = "short",
                segments = listOf(TranscriptSegment(0, 9_000, null, "short"))
            )
        assertEquals(
            config.transcriptionUsdPerMinute,
            transcriptionActualCost(artifact, reservation, config),
            1e-9
        )
    }

    @Test
    fun transcriptionFailureCopyNeverIncludesProviderPayload() {
        assertEquals(
            "transcription provider authentication failed",
            safeTranscriptionFailure(LlmErrorKind.AUTHENTICATION)
        )
        assertEquals(
            "transcription provider returned an invalid response",
            safeTranscriptionFailure(LlmErrorKind.INVALID_OUTPUT)
        )
    }

    @Test
    fun onlyExplicitClientRejectionsAreTreatedAsDefinitelyUnbilled() {
        assertTrue(
            isDefinitivelyUnbilled(
                LlmError(LlmErrorKind.INVALID_REQUEST, "rejected", httpStatus = 400)
            )
        )
        assertTrue(
            isDefinitivelyUnbilled(
                LlmError(LlmErrorKind.RATE_LIMITED, "rejected", httpStatus = 429)
            )
        )
        assertFalse(
            isDefinitivelyUnbilled(
                LlmError(LlmErrorKind.SERVER_ERROR, "uncertain", httpStatus = 500)
            )
        )
        assertFalse(
            isDefinitivelyUnbilled(
                LlmError(LlmErrorKind.TIMEOUT, "uncertain", httpStatus = 408)
            )
        )
    }

    @Test
    fun credentialPreflightRejectsForgedAndEmptyRefsBeforeProviderCall() {
        val rejectingStore =
            object : CredentialStore {
                override fun seal(workspaceId: String, plaintextSecret: String) =
                    EncryptedSecretRef("unused")

                override fun open(workspaceId: String, ref: EncryptedSecretRef): String =
                    error("invalid encrypted reference")
            }
        val emptyStore =
            object : CredentialStore {
                override fun seal(workspaceId: String, plaintextSecret: String) =
                    EncryptedSecretRef("unused")

                override fun open(workspaceId: String, ref: EncryptedSecretRef) = "  "
            }

        assertTrue(
            AudioTranscriptionService("https://api.openai.test", mockClient(), credStore)
                .credentialIsResolvable(apiKey())
        )
        assertFalse(
            AudioTranscriptionService("https://api.openai.test", mockClient(), rejectingStore)
                .credentialIsResolvable(apiKey())
        )
        assertFalse(
            AudioTranscriptionService("https://api.openai.test", mockClient(), emptyStore)
                .credentialIsResolvable(apiKey())
        )
    }

    // CredentialStore stub: returns a fixed key so we can assert the Bearer header is built.
    private val credStore =
        object : CredentialStore {
            override fun seal(workspaceId: String, plaintextSecret: String) =
                EncryptedSecretRef(plaintextSecret)

            override fun open(workspaceId: String, ref: EncryptedSecretRef) = "sk-test-key"
        }

    private fun apiKey() =
        ApiKeyCredential(
            id = "cred-1",
            workspaceId = "ws1",
            provider = ProviderId.OPENAI,
            encryptedApiKeyRef = "ref",
            label = "t",
            createdByUserId = "u1"
        )

    // Canned OpenAI verbose_json: floats in seconds, two segments, one with a speaker field.
    private val verboseJson =
        """
        {
          "task": "transcribe",
          "language": "english",
          "duration": 9.0,
          "text": "Alice: we should ship the API on Friday Bob: the auth service is flaky",
          "segments": [
            {"id":0,"start":0.0,"end":4.5,"speaker":"Alice","text":" Alice: we should ship the API on Friday"},
            {"id":1,"start":4.5,"end":9.0,"speaker":"Bob","text":" Bob: the auth service is flaky"}
          ]
        }
        """
            .trimIndent()

    private fun mockClient(
        capturePath: (String) -> Unit = {},
        captureAuth: (String?) -> Unit = {}
    ): HttpClient =
        HttpClient(
            MockEngine { request ->
                capturePath(request.url.encodedPath)
                captureAuth(request.headers[HttpHeaders.Authorization])
                respond(
                    content = verboseJson,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
        )

    private fun audio() =
        AudioInput(
            bytes = byteArrayOf(0x1, 0x2, 0x3, 0x4),
            fileName = "meeting.mp3",
            mimeType = "audio/mpeg"
        )

    @Test
    fun parsesVerboseJsonSegmentsWithMsAndSpeaker() = runBlocking {
        var seenPath: String? = null
        var seenAuth: String? = null
        val service =
            AudioTranscriptionService(
                "https://api.openai.test",
                mockClient(capturePath = { seenPath = it }, captureAuth = { seenAuth = it }),
                credStore
            )

        val result = service.transcribe(apiKey(), "whisper-1", audio())
        assertTrue(result is LlmResult.Success, "expected success, got $result")
        val artifact = result.value

        // request shaping: multipart POST to the audio endpoint with a server-resolved Bearer key.
        assertEquals("/v1/audio/transcriptions", seenPath)
        assertEquals("Bearer sk-test-key", seenAuth)

        // segments parsed with ms ranges (seconds -> ms) and speaker preserved.
        assertEquals(2, artifact.segments.size)
        assertEquals(0, artifact.segments[0].startMs)
        assertEquals(4_500, artifact.segments[0].endMs)
        assertEquals("Alice", artifact.segments[0].speaker)
        assertEquals(4_500, artifact.segments[1].startMs)
        assertEquals(9_000, artifact.segments[1].endMs)
        assertEquals("Bob", artifact.segments[1].speaker)
        assertTrue(artifact.text.isNotBlank())
    }

    @Test
    fun malformedOrUnboundedVerboseJsonFailsClosed() {
        val service =
            AudioTranscriptionService("https://api.openai.test", mockClient(), credStore)
        assertEquals(null, service.parseVerboseJson("""{"text":{},"segments":[]}"""))
        assertEquals(
            null,
            service.parseVerboseJson(
                """{"text":"ok","segments":[{"text":"x","start":0,"end":0,"speaker":"${
                    "s".repeat(201)
                }"}]}"""
            )
        )
        val clamped =
            service.parseVerboseJson(
                """{"text":"x","segments":[{"text":"x","start":99,"end":-1}]}"""
            )
        assertNotNull(clamped)
        assertEquals(clamped.segments.single().startMs, clamped.segments.single().endMs)
    }

    @Test
    fun httpErrorMapsToFailure() = runBlocking {
        val client =
            HttpClient(MockEngine { respond("rate limited", HttpStatusCode.TooManyRequests) })
        val service = AudioTranscriptionService("https://api.openai.test", client, credStore)
        val result = service.transcribe(apiKey(), "whisper-1", audio())
        assertTrue(result is LlmResult.Failure, "429 should map to a failure")
    }

    @Test
    fun transcribeThenIngestCreatesTranscriptSourceAndChunks() = runBlocking {
        val cfg = testConfig()
        val graph = AppGraph.create(cfg)
        try {
            val workspaceId = UUID.fromString(cfg.auth.devWorkspaceId)
            val userId = UUID.fromString(cfg.auth.devUserId)
            val projectId = UUID.fromString(cfg.auth.devProjectId)

            val service =
                AudioTranscriptionService("https://api.openai.test", mockClient(), credStore)
            val ingestor = TranscriptIngestor(graph.sources)

            val transcriptSecret = "sk-" + "T".repeat(40)
            val rawArtifact =
                (service.transcribe(apiKey(), "whisper-1", audio()) as LlmResult.Success).value
            val artifact =
                rawArtifact.copy(
                    text = rawArtifact.text + " " + transcriptSecret,
                    segments =
                    rawArtifact.segments.mapIndexed { index, segment ->
                        if (index == rawArtifact.segments.lastIndex) {
                            segment.copy(text = segment.text + " api_key=" + transcriptSecret)
                        } else {
                            segment
                        }
                    }
                )
            val result =
                ingestor.ingest(
                    workspaceId = workspaceId,
                    projectId = projectId,
                    artifact = artifact,
                    displayName = "Standup recording",
                    externalRefJson =
                    """{"provider":"openai","kind":"transcription","model":"whisper-1"}""",
                    createdBy = userId
                )

            assertTrue(result.chunkCount > 0, "transcript chunks should be produced")
            assertEquals(2, result.segmentCount)
            assertTrue(result.contentHash.startsWith("sha256:"))

            // a TRANSCRIPT source was persisted, tenant-scoped.
            val source = graph.sources.findSource(workspaceId, result.sourceId)
            assertNotNull(source)
            assertEquals("TRANSCRIPT", source.sourceType)

            // chunks were persisted under the new version and are tenant-scoped.
            val chunks = graph.sources.listChunks(workspaceId, result.sourceVersionId)
            assertEquals(result.chunkCount, chunks.size, "persisted chunk count matches")
            assertTrue(chunks.isNotEmpty())
            assertEquals(listOf("Alice", "Bob"), chunks.map { it.speaker })
            assertEquals(listOf(0, 4_500), chunks.map { it.startMs })
            assertEquals(listOf(4_500, 9_000), chunks.map { it.endMs })
            val storedText = chunks.joinToString("\n") { it.text }
            assertTrue(
                transcriptSecret !in storedText,
                "raw transcript credential must never persist"
            )
            assertTrue(
                "[REDACTED:" in storedText,
                "redaction marker should preserve transcript context"
            )

            // a different workspace cannot read these chunks (tenant isolation).
            val other = graph.sources.listChunks(UUID.randomUUID(), result.sourceVersionId)
            assertTrue(other.isEmpty(), "another workspace sees no chunks")
        } finally {
            graph.stop()
        }
    }

    @Test
    fun rendersSegmentsIntoChunkerRecognisableText() {
        // renderTranscript() is pure (no DB), so reuse a properly-closed H2 graph for the repo dep.
        val graph = AppGraph.create(testConfig())
        try {
            val ingestor = TranscriptIngestor(graph.sources)
            val artifact =
                TranscriptArtifact(
                    text = "full text",
                    segments =
                    listOf(
                        TranscriptSegment(0, 4_500, "Alice", "we ship Friday"),
                        TranscriptSegment(65_000, 70_000, null, "next point")
                    )
                )
            // The ingestor renders "[hh:mm:ss] Speaker: text" so TranscriptChunker recovers
            // structure.
            val rendered = ingestor.renderTranscript(artifact)
            assertTrue(
                rendered.contains("[00:00:00] Alice: we ship Friday"),
                "rendered=\n$rendered"
            )
            assertTrue(rendered.contains("[00:01:05] next point"), "rendered=\n$rendered")
        } finally {
            graph.stop()
        }
    }

    @Test
    fun unsafeProviderSpeakerMetadataIsNotReintroducedAfterSafetyProcessing() = runBlocking {
        val cfg = testConfig()
        val graph = AppGraph.create(cfg)
        try {
            val workspaceId = UUID.fromString(cfg.auth.devWorkspaceId)
            val projectId = UUID.fromString(cfg.auth.devProjectId)
            val userId = UUID.fromString(cfg.auth.devUserId)
            val speakerSecret = "sk-" + "S".repeat(40)
            val artifact =
                TranscriptArtifact(
                    text = "status update",
                    segments =
                    listOf(
                        TranscriptSegment(
                            startMs = 1_250,
                            endMs = 4_750,
                            speaker = "Alice\u202E api_key=$speakerSecret",
                            text = "status update"
                        )
                    )
                )

            val result =
                TranscriptIngestor(graph.sources).ingest(
                    workspaceId = workspaceId,
                    projectId = projectId,
                    artifact = artifact,
                    displayName = "Unsafe speaker fixture",
                    externalRefJson = "{}",
                    createdBy = userId
                )
            val chunks = graph.sources.listChunks(workspaceId, result.sourceVersionId)
            assertTrue(chunks.isNotEmpty())
            assertEquals(1_250, chunks.first().startMs)
            assertEquals(4_750, chunks.first().endMs)
            val persisted =
                chunks.joinToString("\n") { "${it.speaker.orEmpty()} ${it.text}" }
            assertTrue(speakerSecret !in persisted)
            assertTrue('\u202E' !in persisted)
        } finally {
            graph.stop()
        }
    }
}
