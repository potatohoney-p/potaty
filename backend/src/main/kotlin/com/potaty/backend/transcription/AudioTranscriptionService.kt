/*
 * Copyright (c) 2026, Potaty
 *
 * Audio transcription service (plan 14.4 audio input; 4.3 source pipeline). STANDALONE service —
 * it does NOT live on OpenAiProvider (whose transcribe() stays UNSUPPORTED). WS12 keeps the
 * multipart audio path here so it can evolve (object-store fetch, diarization, chunked uploads)
 * without touching the chat/embeddings provider.
 *
 * Transport: POST multipart/form-data to {openAiBaseUrl}/v1/audio/transcriptions with
 *   - a "file" part (the audio bytes + filename so the API can sniff the container),
 *   - "model"           = the transcription model (config-driven; e.g. whisper-1),
 *   - "response_format" = "verbose_json" (so we get per-segment start/end + text).
 * Authorization: Bearer <api key>, resolved SERVER-SIDE from the CredentialStore immediately
 * before the call (the plaintext key never leaves this method — same rule as OpenAiProvider).
 *
 * Parsing: verbose_json -> TranscriptArtifact (reusing the existing provider types in
 * llm/provider/LlmProvider.kt). OpenAI's verbose_json segment carries float "start"/"end" in
 * SECONDS and "text"; it does NOT diarize, so segment.speaker is null here. Speaker turns are
 * recovered downstream from the transcript text by TranscriptChunker (see TranscriptionRoutes).
 *
 * Errors are mapped through the shared ProviderErrorMapper / LlmError taxonomy so callers can
 * reason about retry vs fatal exactly as they do for the LLM providers.
 */

package com.potaty.backend.transcription

import com.potaty.backend.llm.auth.ApiKeyCredential
import com.potaty.backend.llm.auth.CredentialStore
import com.potaty.backend.llm.auth.EncryptedSecretRef
import com.potaty.backend.llm.auth.LlmCredential
import com.potaty.backend.llm.provider.LlmError
import com.potaty.backend.llm.provider.LlmErrorKind
import com.potaty.backend.llm.provider.LlmResult
import com.potaty.backend.llm.provider.ProviderErrorMapper
import com.potaty.backend.llm.provider.TokenUsage
import com.potaty.backend.llm.provider.TranscriptArtifact
import com.potaty.backend.llm.provider.TranscriptSegment
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The audio payload + content type a caller hands to the service. */
data class AudioInput(
    /** Raw audio bytes (fetched from object storage or uploaded). */
    val bytes: ByteArray,
    /** A filename so the OpenAI API can detect the container (e.g. "audio.m4a"). */
    val fileName: String,
    /** MIME type for the multipart file part (e.g. "audio/mpeg", "audio/wav"). */
    val mimeType: String
) {
    // Value-equality on bytes (data class default compares the array reference).
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioInput) return false
        return bytes.contentEquals(other.bytes) &&
            fileName == other.fileName &&
            mimeType == other.mimeType
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + fileName.hashCode()
        result = 31 * result + mimeType.hashCode()
        return result
    }
}

class AudioTranscriptionService(
    private val baseUrl: String,
    private val httpClient: HttpClient,
    private val credentialStore: CredentialStore
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Transcribes [audio] with [model] using [credential]. Returns a [TranscriptArtifact] (full
     * text + per-segment ms ranges) or a classified [LlmError]. Network/parse failures never throw.
     */
    suspend fun transcribe(
        credential: LlmCredential,
        model: String,
        audio: AudioInput
    ): LlmResult<TranscriptArtifact> =
        try {
            val authHeader = authHeader(credential)
            val response =
                httpClient.post("$baseUrl/v1/audio/transcriptions") {
                    header(HttpHeaders.Authorization, authHeader)
                    setBody(multipartBody(model, audio))
                }
            val text = response.bodyAsText()
            if (response.status.value !in 200..299) {
                LlmResult.Failure(ProviderErrorMapper.fromHttpStatus(response.status.value, text))
            } else {
                when (val parsed = parseVerboseJson(text)) {
                    null ->
                        LlmResult.Failure(
                            LlmError(
                                LlmErrorKind.INVALID_OUTPUT,
                                "OpenAI transcription response was not valid verbose_json"
                            )
                        )
                    else -> LlmResult.Success(parsed, TokenUsage())
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            LlmResult.Failure(
                LlmError(LlmErrorKind.NETWORK, t.message ?: "network error", retryable = true)
            )
        }

    /**
     * Resolves the Bearer header server-side. OpenAI audio is API-key only (same contract as
     * OpenAiProvider): a ChatGPT subscription / provider-OAuth token is not accepted.
     */
    internal fun authHeader(credential: LlmCredential): String {
        return "Bearer ${resolveApiKey(credential)}"
    }

    /** Rejects forged, cross-tenant, or empty credential refs before reserving provider spend. */
    internal fun credentialIsResolvable(credential: LlmCredential): Boolean =
        runCatching { resolveApiKey(credential) }.isSuccess

    private fun resolveApiKey(credential: LlmCredential): String {
        val apiKey =
            (credential as? ApiKeyCredential)
                ?: error(
                    "OpenAI transcription requires an ApiKeyCredential. " +
                        "ChatGPT subscriptions are not API credentials and provider OAuth " +
                        "is not supported for OpenAI."
                )
        return credentialStore
            .open(
                apiKey.workspaceId,
                EncryptedSecretRef(apiKey.encryptedApiKeyRef)
            )
            .trim()
            .also { require(it.isNotEmpty()) { "resolved transcription credential is empty" } }
    }

    private fun multipartBody(model: String, audio: AudioInput): MultiPartFormDataContent =
        MultiPartFormDataContent(
            formData {
                append(
                    key = "file",
                    value = audio.bytes,
                    headers =
                    Headers.build {
                        append(HttpHeaders.ContentType, audio.mimeType)
                        append(
                            HttpHeaders.ContentDisposition,
                            "filename=\"${sanitizeFileName(audio.fileName)}\""
                        )
                    }
                )
                append("model", model)
                append("response_format", "verbose_json")
            }
        )

    /**
     * Parses an OpenAI verbose_json transcription body into a [TranscriptArtifact]. Segment
     * "start"/"end" are floats in SECONDS -> converted to ms. Missing/empty segments fall back to a
     * single segment covering the whole text. Returns null only when there is no usable text at
     * all.
     */
    internal fun parseVerboseJson(body: String): TranscriptArtifact? {
        if (body.length > MAX_PROVIDER_RESPONSE_CHARS) return null
        val root =
            runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val fullText =
            runCatching { root["text"]?.jsonPrimitive?.content?.trim() }
                .getOrNull()
                .orEmpty()
        if (fullText.length > MAX_TRANSCRIPT_CHARS) return null

        val segmentsJson = runCatching { root["segments"]?.jsonArray }.getOrNull()
        if (segmentsJson != null && segmentsJson.size > MAX_TRANSCRIPT_SEGMENTS) return null
        val segments = mutableListOf<TranscriptSegment>()
        segmentsJson?.forEach { element ->
            val obj = runCatching { element.jsonObject }.getOrNull() ?: return null
            val segText =
                runCatching { obj["text"]?.jsonPrimitive?.content?.trim() }
                    .getOrNull()
                    .orEmpty()
            if (segText.isEmpty()) return@forEach
            if (segText.length > MAX_SEGMENT_TEXT_CHARS) return null
            val startMs = secondsToMs(obj["start"])
            val endMs = secondsToMs(obj["end"]).coerceAtLeast(startMs)
            val speaker =
                runCatching { obj["speaker"]?.jsonPrimitive?.content?.trim() }
                    .getOrNull()
                    ?.takeIf { it.isNotEmpty() }
            if (speaker != null && speaker.length > MAX_SPEAKER_CHARS) return null
            segments +=
                TranscriptSegment(
                    startMs = startMs,
                    endMs = endMs,
                    // OpenAI whisper-1 verbose_json does not diarize; speaker is recovered later
                    // from the transcript text by TranscriptChunker. Carry any bounded value.
                    speaker = speaker,
                    text = segText
                )
        }

        val resolvedText =
            when {
                fullText.isNotEmpty() -> fullText
                segments.isNotEmpty() -> segments.joinToString("\n") { it.text }
                else -> return null
            }
        if (resolvedText.length > MAX_TRANSCRIPT_CHARS) return null

        val resolvedSegments = segments.ifEmpty {
            listOf(TranscriptSegment(startMs = 0, endMs = 0, speaker = null, text = resolvedText))
        }
        return TranscriptArtifact(text = resolvedText, segments = resolvedSegments)
    }

    private fun secondsToMs(element: JsonElement?): Int {
        val prim = runCatching { element?.jsonPrimitive }.getOrNull() ?: return 0
        val asDouble = prim.doubleOrNull
        if (asDouble != null && asDouble.isFinite()) {
            return (asDouble * 1000.0)
                .coerceIn(0.0, MAX_TRANSCRIPT_DURATION_MS.toDouble())
                .toInt()
        }
        // Defensive: some encoders emit integers; coerce through int.
        val asInt = prim.intOrNull
        return if (asInt != null) {
            asInt.toLong()
                .times(1000L)
                .coerceIn(0L, MAX_TRANSCRIPT_DURATION_MS.toLong())
                .toInt()
        } else {
            0
        }
    }

    /** Strip path separators / quotes from a filename used in a Content-Disposition header. */
    private fun sanitizeFileName(name: String): String =
        name.substringAfterLast('/').substringAfterLast('\\').replace("\"", "").ifBlank { "audio" }

    private companion object {
        const val MAX_PROVIDER_RESPONSE_CHARS = 5 * 1024 * 1024
        const val MAX_TRANSCRIPT_CHARS = 2 * 1024 * 1024
        const val MAX_TRANSCRIPT_SEGMENTS = 20_000
        const val MAX_SEGMENT_TEXT_CHARS = 20_000
        const val MAX_SPEAKER_CHARS = 200
        const val MAX_TRANSCRIPT_DURATION_MS = 24 * 60 * 60 * 1000
    }
}
