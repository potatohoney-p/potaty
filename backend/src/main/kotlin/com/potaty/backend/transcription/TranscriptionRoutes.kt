/*
 * Copyright (c) 2026, Potaty
 *
 * Transcription routes (plan 14.4 audio input; 4.3 source pipeline; section 10 contract). One
 * endpoint:
 *
 *   POST /api/v1/projects/{projectId}/transcription   (TENANT-SCOPED, RBAC CREATE_SOURCE)
 *     Accepts an audio payload + the workspace LLM credential to bill the transcription against,
 *     transcribes it via AudioTranscriptionService (OpenAI multipart), then ingests the resulting
 *     transcript text as a TRANSCRIPT source (normalize + TranscriptChunker + persist), preserving
 *     speaker / timestamp evidence. Returns the new sourceVersionId the diagram pipeline can ground on.
 *
 * AUDIO INPUT: the request carries the audio inline as base64 ("audioBase64") today; an
 * object-store key ("audioObjectKey") is accepted in the contract but server-side fetch is left to
 * a follow-up (the storage layer is configured out of band — same pattern as GitHub re-index
 * scheduling). Exactly one of the two must be present.
 *
 * CREDENTIALS: the request references a workspace credential by id only. The active row and its
 * opaque CredentialStore ref are resolved SERVER-SIDE, and the plaintext key is opened only inside
 * AudioTranscriptionService immediately before the provider call. Neither secret nor ciphertext
 * appears in the request, response, browser storage, or logs; revoked/cross-tenant ids fail closed.
 *
 * The handler is null-safe when transcription is not configured (503 instead of NPE).
 */

package com.potaty.backend.transcription

import com.potaty.backend.api.ApiError
import com.potaty.backend.auth.Permission
import com.potaty.backend.auth.Rbac
import com.potaty.backend.auth.tenant
import com.potaty.backend.cost.CostConfig
import com.potaty.backend.cost.CostEstimate
import com.potaty.backend.cost.QuotaGuard
import com.potaty.backend.llm.auth.ApiKeyCredential
import com.potaty.backend.llm.provider.LlmError
import com.potaty.backend.llm.provider.LlmErrorKind
import com.potaty.backend.llm.provider.LlmResult
import com.potaty.backend.llm.provider.TranscriptArtifact
import com.potaty.backend.usage.UsageRecorder
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.ByteReadChannel
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Largest base64-decoded audio payload accepted inline in a single request (plan 12.4 tiering). */
private const val MAX_AUDIO_BYTES = 25 * 1024 * 1024 // 25 MiB (OpenAI's audio file cap)
private const val MAX_AUDIO_BASE64_CHARS = ((MAX_AUDIO_BYTES + 2) / 3) * 4
internal const val MAX_TRANSCRIPTION_REQUEST_BYTES = MAX_AUDIO_BASE64_CHARS + (64 * 1024)
private const val MAX_CREDENTIAL_ID_CHARS = 200
private const val MAX_AUDIO_OBJECT_KEY_CHARS = 1_024
private const val MAX_AUDIO_FILE_NAME_CHARS = 255
private const val MAX_AUDIO_MIME_TYPE_CHARS = 100
private const val MAX_TRANSCRIPT_DISPLAY_NAME_CHARS = 200
private const val MAX_IDEMPOTENCY_KEY_CHARS = 200
private val IDEMPOTENCY_KEY_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,199}$")

@Serializable
data class TranscriptionRequest(
    /** Workspace LLM credential id this transcription is billed against. */
    val credentialId: String,
    /** Audio inline as base64. Exactly one of [audioBase64] / [audioObjectKey] must be present. */
    val audioBase64: String? = null,
    /** Object-store key for the audio (server-side fetch is a follow-up; see file header). */
    val audioObjectKey: String? = null,
    /** Content type for the multipart file part (e.g. "audio/mpeg"). */
    val mimeType: String = "audio/mpeg",
    /** A filename so the API can sniff the container; defaults from [mimeType]. */
    val fileName: String? = null,
    /** Human label for the resulting TRANSCRIPT source. */
    val displayName: String = "Audio transcript"
)

internal sealed class TranscriptionBodyReadResult {
    data class Success(val request: TranscriptionRequest) : TranscriptionBodyReadResult()

    object TooLarge : TranscriptionBodyReadResult()

    object Invalid : TranscriptionBodyReadResult()
}

/** Resolves an active, tenant-owned API credential without exposing its encrypted ref to clients. */
fun interface TranscriptionCredentialResolver {
    suspend fun resolve(workspaceId: UUID, credentialId: String): ApiKeyCredential?
}

@Serializable
data class TranscriptionResponse(
    val sourceId: String,
    val sourceVersionId: String,
    val contentHash: String,
    val chunkCount: Int,
    val segmentCount: Int,
    val status: String
)

@Serializable
internal data class TranscriptionAttemptOutcome(
    val statusCode: Int,
    val response: TranscriptionResponse? = null,
    val error: ApiError? = null
)

/**
 * Mounts the transcription route. [service] and [transcriptionModel] are null when transcription is
 * not configured (no transcription model in the model-routing table); the handler then returns 503
 * so the rest of the API still runs out-of-the-box on H2/dev.
 */
internal fun Route.transcriptionRoutes(
    service: AudioTranscriptionService?,
    credentialResolver: TranscriptionCredentialResolver,
    ingestor: TranscriptIngestor,
    completer: TranscriptionCompleter,
    projectExists: suspend (workspaceId: UUID, projectId: UUID) -> Boolean,
    transcriptionModel: String?,
    quotaGuard: QuotaGuard,
    usage: UsageRecorder,
    costConfig: CostConfig,
    json: Json = Json { ignoreUnknownKeys = true },
    clock: () -> Instant = Instant::now
) {
    route("/projects/{projectId}/transcription") {
        // POST /api/v1/projects/{projectId}/transcription
        post {
            val tenant = call.tenant()
            Rbac.require(tenant, Permission.CREATE_SOURCE)

            val svc = service
            val model = transcriptionModel
            if (svc == null || model.isNullOrBlank()) {
                return@post call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    ApiError(
                        "not_configured",
                        "transcription is not configured for this deployment"
                    )
                )
            }

            val workspaceId = tenant.workspaceId.toUuidOrNull()
                ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("bad_request", "invalid workspace")
                )
            val projectId = call.parameters["projectId"]?.toUuidOrNull()
                ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("bad_request", "missing/invalid projectId")
                )

            // Reject stale and cross-tenant project ids before resolving credentials, reserving
            // quota, or invoking a billable provider. Not-found deliberately hides ownership.
            if (!projectExists(workspaceId, projectId)) {
                return@post call.respond(
                    HttpStatusCode.NotFound,
                    ApiError("not_found", "project was not found")
                )
            }

            val idempotencyKey = call.request.headers["Idempotency-Key"]
                ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("bad_request", "missing Idempotency-Key header")
                )
            if (
                idempotencyKey.length > MAX_IDEMPOTENCY_KEY_CHARS ||
                !IDEMPOTENCY_KEY_PATTERN.matches(idempotencyKey)
            ) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError(
                        "bad_request",
                        "Idempotency-Key must use 1-$MAX_IDEMPOTENCY_KEY_CHARS " +
                            "letters, digits, period, underscore, colon, or hyphen"
                    )
                )
            }

            if (call.request.contentType().withoutParameters() != ContentType.Application.Json) {
                return@post call.respond(
                    HttpStatusCode.UnsupportedMediaType,
                    ApiError("unsupported_media_type", "Content-Type must be application/json")
                )
            }
            val contentLengthHeader = call.request.headers[HttpHeaders.ContentLength]
            val declaredContentLength = contentLengthHeader?.toLongOrNull()
            if (contentLengthHeader != null && declaredContentLength == null) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("bad_request", "Content-Length is invalid")
                )
            }
            val body =
                when (
                    val read =
                        readBoundedTranscriptionRequest(
                            input = call.receiveChannel(),
                            declaredContentLength = declaredContentLength,
                            json = json
                        )
                ) {
                    is TranscriptionBodyReadResult.Success -> read.request
                    TranscriptionBodyReadResult.TooLarge ->
                        return@post call.respond(
                            HttpStatusCode.PayloadTooLarge,
                            ApiError(
                                "payload_too_large",
                                "transcription request exceeds the inline upload limit"
                            )
                        )
                    TranscriptionBodyReadResult.Invalid ->
                        return@post call.respond(
                            HttpStatusCode.BadRequest,
                            ApiError("bad_request", "transcription request is invalid")
                        )
                }
            if (body.credentialId.isBlank()) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("bad_request", "credentialId is required")
                )
            }
            if (body.credentialId.length > MAX_CREDENTIAL_ID_CHARS) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("bad_request", "credential id is invalid")
                )
            }
            if (
                !hasValidTranscriptionMetadata(
                    body.fileName,
                    body.mimeType,
                    body.displayName,
                    body.audioObjectKey
                )
            ) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("bad_request", "audio metadata is invalid")
                )
            }

            // Exactly one audio source. Object-store fetch is a documented follow-up.
            val inlineAudio = body.audioBase64
            val hasInlineAudio = !inlineAudio.isNullOrBlank()
            if (!hasExactlyOneAudioSource(inlineAudio, body.audioObjectKey)) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError(
                        "bad_request",
                        "exactly one of audioBase64 or audioObjectKey is required"
                    )
                )
            }
            if (!hasInlineAudio) {
                return@post call.respond(
                    HttpStatusCode.NotImplemented,
                    ApiError(
                        "not_implemented",
                        "audioObjectKey fetch is not yet wired; send audioBase64"
                    )
                )
            }

            // Reject the encoded upper bound before allocating the decoded byte array.
            val encodedAudio = requireNotNull(inlineAudio)
            if (!isInlineAudioEncodedLengthAllowed(encodedAudio.length)) {
                return@post call.respond(
                    HttpStatusCode.PayloadTooLarge,
                    ApiError("payload_too_large", "audio exceeds $MAX_AUDIO_BYTES bytes")
                )
            }
            val audioBytes = runCatching { Base64.getDecoder().decode(encodedAudio) }.getOrNull()
                ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("bad_request", "audioBase64 is not valid base64")
                )
            if (audioBytes.isEmpty()) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("bad_request", "audio payload is empty")
                )
            }
            if (audioBytes.size > MAX_AUDIO_BYTES) {
                return@post call.respond(
                    HttpStatusCode.PayloadTooLarge,
                    ApiError("payload_too_large", "audio exceeds $MAX_AUDIO_BYTES bytes")
                )
            }

            val createdBy = tenant.userId.toUuidOrNull()
            val audio = AudioInput(
                bytes = audioBytes,
                fileName = body.fileName?.ifBlank { null } ?: defaultFileName(body.mimeType),
                mimeType = body.mimeType.ifBlank { "audio/mpeg" }.lowercase()
            )

            val costEstimate = transcriptionReservationEstimate(audioBytes.size, costConfig)
            val requestHash =
                transcriptionRequestHash(
                    projectId = projectId,
                    credentialId = body.credentialId,
                    audio = audio,
                    displayName = body.displayName.trim(),
                    provider = "openai",
                    model = model,
                    stage = "transcription"
                )
            val reservationNow = clock()
            val reservation =
                quotaGuard.reserve(
                    workspaceId = workspaceId,
                    reservationKey = "transcription:$idempotencyKey",
                    estimate = costEstimate,
                    now = reservationNow,
                    requestHash = requestHash,
                    externalOperation = "transcription",
                    externalProvider = "openai",
                    externalModel = model,
                    externalStage = "transcription",
                    externalMetadataJson =
                    buildJsonObject {
                        put("projectId", projectId.toString())
                        put("credentialId", body.credentialId)
                        put("fileName", audio.fileName)
                        put("mimeType", audio.mimeType)
                    }.toString()
                )
            reservation.externalResultJson?.let { stored ->
                val outcome = runCatching {
                    json.decodeFromString<TranscriptionAttemptOutcome>(stored)
                }.getOrNull()
                    ?: return@post call.respond(
                        HttpStatusCode.Conflict,
                        ApiError(
                            "idempotency_state_invalid",
                            "the earlier transcription result requires operator review"
                        )
                    )
                return@post call.respondStoredOutcome(outcome)
            }
            if (reservation.releasedAt != null) {
                return@post call.respond(
                    HttpStatusCode.Conflict,
                    ApiError(
                        "external_spend_reconciled",
                        "this transcription attempt was reconciled; use a new idempotency key"
                    )
                )
            }

            val checkpoint: TranscriptionCheckpoint
            val checkpointJson: String
            val processingToken: UUID
            val storedCheckpoint = reservation.externalCheckpointJson
            if (storedCheckpoint != null) {
                checkpointJson = storedCheckpoint
                checkpoint =
                    runCatching { json.decodeFromString<TranscriptionCheckpoint>(storedCheckpoint) }
                        .getOrNull()
                        ?.takeIf { it.requestHash == requestHash }
                        ?: return@post call.respond(
                            HttpStatusCode.Conflict,
                            ApiError(
                                "idempotency_state_invalid",
                                "the earlier transcription checkpoint requires operator review"
                            )
                        )
                val leaseExpiresAt = reservation.processingLeaseExpiresAt
                    ?: return@post call.respond(
                        HttpStatusCode.Conflict,
                        ApiError(
                            "idempotency_state_invalid",
                            "the earlier transcription checkpoint requires operator review"
                        )
                    )
                if (leaseExpiresAt > reservationNow) {
                    return@post call.respond(
                        HttpStatusCode.Conflict,
                        ApiError(
                            "external_spend_processing",
                            "this transcription attempt is still inside its processing lease"
                        )
                    )
                }
                processingToken =
                    quotaGuard.claimExternalCheckpoint(
                        workspaceId = workspaceId,
                        reservationId = reservation.id,
                        now = reservationNow
                    )
            } else {
                if (reservation.externalSpendStartedAt != null) {
                    return@post call.respond(
                        HttpStatusCode.Conflict,
                        ApiError(
                            "external_spend_pending",
                            "this transcription attempt is pending accounting reconciliation"
                        )
                    )
                }

                // Credential availability only matters before a fresh provider call. A completed
                // result or durable checkpoint must remain replayable after key rotation/revocation.
                val credential = credentialResolver.resolve(workspaceId, body.credentialId)
                    ?: return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ApiError("bad_request", "credential is unavailable or revoked")
                    )
                if (!svc.credentialIsResolvable(credential)) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ApiError(
                            "bad_request",
                            "credential could not be resolved for this workspace"
                        )
                    )
                }

                // Cross the durable accounting fence before the external call. From this point
                // the reservation remains quota-visible until a terminal transaction or an
                // owner-only reconciliation releases it.
                processingToken =
                    quotaGuard.markExternalSpendStarted(
                        workspaceId,
                        reservation.id,
                        clock()
                    )
                when (val providerResult = svc.transcribe(credential, model, audio)) {
                    is LlmResult.Failure -> {
                        val status = statusForTranscriptionFailure(providerResult.error.kind)
                        if (isDefinitivelyUnbilled(providerResult.error)) {
                            val error =
                                ApiError(
                                    "transcription_failed",
                                    safeTranscriptionFailure(providerResult.error.kind)
                                )
                            val outcome =
                                TranscriptionAttemptOutcome(
                                    statusCode = status.value,
                                    error = error
                                )
                            usage.recordAndSettleReservation(
                                workspaceId = workspaceId,
                                reservationId = reservation.id,
                                processingToken = processingToken,
                                provider = "openai",
                                model = model,
                                stage = "transcription",
                                inputTokens = 0,
                                outputTokens = 0,
                                estimatedCostUsd = 0.0,
                                externalResultJson = json.encodeToString(outcome),
                                at = clock()
                            )
                            return@post call.respond(status, error)
                        }
                        return@post call.respond(
                            status,
                            ApiError(
                                "transcription_outcome_uncertain",
                                "the provider outcome is uncertain and requires owner review"
                            )
                        )
                    }
                    is LlmResult.Success -> {
                        val prepared = ingestor.prepare(providerResult.value)
                        checkpoint =
                            TranscriptionCheckpoint(
                                requestHash = requestHash,
                                bookedCostUsd =
                                transcriptionActualCost(
                                    providerResult.value,
                                    costEstimate,
                                    costConfig
                                ),
                                prepared = prepared
                            )
                        checkpointJson = json.encodeToString(checkpoint)
                        quotaGuard.saveExternalCheckpoint(
                            workspaceId = workspaceId,
                            reservationId = reservation.id,
                            token = processingToken,
                            checkpointJson = checkpointJson,
                            now = clock()
                        )
                    }
                }
            }

            val externalRef =
                externalRef(
                    body.audioObjectKey,
                    body.fileName ?: audio.fileName,
                    model,
                    requestHash
                )
            val response =
                completer.complete(
                    TranscriptionCompletionCommand(
                        workspaceId = workspaceId,
                        projectId = projectId,
                        reservationId = reservation.id,
                        processingToken = processingToken,
                        requestHash = requestHash,
                        checkpointJson = checkpointJson,
                        checkpoint = checkpoint,
                        displayName = body.displayName.trim(),
                        externalRefJson = externalRef,
                        createdBy = createdBy,
                        provider = "openai",
                        model = model,
                        stage = "transcription",
                        at = clock()
                    )
                )
            call.respond(HttpStatusCode.Created, response)
        }
    }
}

/**
 * Reads and decodes the JSON body under a hard byte ceiling before serialization allocates an
 * attacker-controlled string. The streamed check also covers HTTP/1.1 chunked and HTTP/2 bodies
 * where Content-Length is absent.
 */
internal suspend fun readBoundedTranscriptionRequest(
    input: ByteReadChannel,
    declaredContentLength: Long?,
    json: Json,
    maxBytes: Int = MAX_TRANSCRIPTION_REQUEST_BYTES
): TranscriptionBodyReadResult {
    require(maxBytes > 0) { "maxBytes must be positive" }
    if (declaredContentLength != null) {
        if (declaredContentLength < 0) return TranscriptionBodyReadResult.Invalid
        if (declaredContentLength > maxBytes.toLong()) {
            return TranscriptionBodyReadResult.TooLarge
        }
    }

    val initialCapacity =
        declaredContentLength
            ?.toInt()
            ?.coerceAtMost(64 * 1024)
            ?.coerceAtLeast(32)
            ?: 8 * 1024
    val output = ByteArrayOutputStream(initialCapacity)
    val buffer = ByteArray(8 * 1024)
    var totalBytes = 0
    while (true) {
        val read = input.readAvailable(buffer, 0, buffer.size)
        if (read < 0) break
        if (read == 0) {
            if (input.isClosedForRead) break
            input.awaitContent()
            continue
        }
        totalBytes += read
        if (totalBytes > maxBytes) return TranscriptionBodyReadResult.TooLarge
        output.write(buffer, 0, read)
    }

    val bodyText =
        runCatching {
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(output.toByteArray()))
                .toString()
        }.getOrNull() ?: return TranscriptionBodyReadResult.Invalid
    val request =
        runCatching { json.decodeFromString<TranscriptionRequest>(bodyText) }.getOrNull()
            ?: return TranscriptionBodyReadResult.Invalid
    return TranscriptionBodyReadResult.Success(request)
}

internal fun hasExactlyOneAudioSource(inlineAudio: String?, objectKey: String?): Boolean =
    !inlineAudio.isNullOrBlank() xor !objectKey.isNullOrBlank()

internal fun isInlineAudioEncodedLengthAllowed(encodedChars: Int): Boolean =
    encodedChars in 1..MAX_AUDIO_BASE64_CHARS

internal fun hasValidTranscriptionMetadata(
    fileName: String?,
    mimeType: String,
    displayName: String,
    objectKey: String?
): Boolean {
    val normalizedMime = mimeType.ifBlank { "audio/mpeg" }.lowercase()
    val safeMime =
        normalizedMime.length <= MAX_AUDIO_MIME_TYPE_CHARS &&
            normalizedMime in SUPPORTED_AUDIO_MIME_TYPES
    val safeFileName =
        fileName == null ||
            (
                fileName.length <= MAX_AUDIO_FILE_NAME_CHARS &&
                    fileName.none(Char::isISOControl)
                )
    val safeDisplayName =
        displayName.trim().isNotEmpty() &&
            displayName.length <= MAX_TRANSCRIPT_DISPLAY_NAME_CHARS
    val safeObjectKey =
        objectKey == null ||
            (
                objectKey.length <= MAX_AUDIO_OBJECT_KEY_CHARS &&
                    objectKey.none(Char::isISOControl)
                )
    return safeMime && safeFileName && safeDisplayName && safeObjectKey
}

internal fun transcriptionReservationEstimate(
    audioBytes: Int,
    config: CostConfig
): CostEstimate {
    val estimatedSeconds =
        (audioBytes.coerceAtLeast(0).toDouble() * 8.0) /
            config.transcriptionReservationBitrateBps.toDouble()
    val highMinutes = (estimatedSeconds / 60.0).coerceAtLeast(1.0)
    val high = highMinutes * config.transcriptionUsdPerMinute
    return CostEstimate(
        lowUsd = config.transcriptionUsdPerMinute.coerceAtMost(high),
        highUsd = high
    )
}

internal fun transcriptionActualCost(
    artifact: TranscriptArtifact,
    fallback: CostEstimate,
    config: CostConfig
): Double {
    val durationMs = artifact.segments.maxOfOrNull { it.endMs } ?: 0
    if (durationMs <= 0) return fallback.highUsd
    val minutes = (durationMs.toDouble() / 60_000.0).coerceAtLeast(1.0)
    return minutes * config.transcriptionUsdPerMinute
}

internal fun safeTranscriptionFailure(kind: LlmErrorKind): String =
    when (kind) {
        LlmErrorKind.AUTHENTICATION -> "transcription provider authentication failed"
        LlmErrorKind.RATE_LIMITED -> "transcription provider is temporarily rate limited"
        LlmErrorKind.QUOTA_EXCEEDED -> "transcription provider quota is exhausted"
        LlmErrorKind.INVALID_REQUEST -> "the audio or transcription request was rejected"
        LlmErrorKind.INVALID_OUTPUT -> "transcription provider returned an invalid response"
        LlmErrorKind.NETWORK -> "transcription provider is temporarily unreachable"
        else -> "transcription could not be completed"
    }

private fun defaultFileName(mimeType: String): String = when {
    mimeType.contains("wav") -> "audio.wav"
    mimeType.contains("mp4") || mimeType.contains("m4a") -> "audio.m4a"
    mimeType.contains("webm") -> "audio.webm"
    mimeType.contains("ogg") -> "audio.ogg"
    else -> "audio.mp3"
}

private fun externalRef(
    objectKey: String?,
    fileName: String,
    model: String,
    requestHash: String
): String =
    buildJsonObject {
        put("provider", "openai")
        put("kind", "transcription")
        objectKey?.let { put("audioObjectKey", it) }
        put("fileName", fileName)
        put("model", model)
        put("attemptHash", requestHash)
    }.toString()

internal fun transcriptionRequestHash(
    projectId: UUID,
    credentialId: String,
    audio: AudioInput,
    displayName: String,
    provider: String,
    model: String,
    stage: String
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fun update(value: ByteArray) {
        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value.size).array())
        digest.update(value)
    }
    update("potaty-transcription-v2".toByteArray(Charsets.UTF_8))
    update(provider.toByteArray(Charsets.UTF_8))
    update(model.toByteArray(Charsets.UTF_8))
    update(stage.toByteArray(Charsets.UTF_8))
    update(projectId.toString().toByteArray(Charsets.UTF_8))
    update(credentialId.toByteArray(Charsets.UTF_8))
    update(audio.fileName.toByteArray(Charsets.UTF_8))
    update(audio.mimeType.toByteArray(Charsets.UTF_8))
    update(displayName.toByteArray(Charsets.UTF_8))
    update(audio.bytes)
    return "sha256:" + digest.digest().joinToString("") { "%02x".format(it) }
}

internal fun isDefinitivelyUnbilled(error: LlmError): Boolean =
    error.httpStatus in DEFINITIVELY_UNBILLED_HTTP_STATUSES

private fun statusForTranscriptionFailure(kind: LlmErrorKind): HttpStatusCode =
    when (kind) {
        LlmErrorKind.INVALID_REQUEST -> HttpStatusCode.BadRequest
        LlmErrorKind.RATE_LIMITED -> HttpStatusCode.TooManyRequests
        LlmErrorKind.QUOTA_EXCEEDED -> HttpStatusCode.PaymentRequired
        else -> HttpStatusCode.BadGateway
    }

private suspend fun ApplicationCall.respondStoredOutcome(outcome: TranscriptionAttemptOutcome) {
    val status = runCatching { HttpStatusCode.fromValue(outcome.statusCode) }.getOrNull()
        ?: return respond(
            HttpStatusCode.Conflict,
            ApiError("idempotency_state_invalid", "the stored transcription result is invalid")
        )
    when {
        outcome.response != null && outcome.error == null -> respond(status, outcome.response)
        outcome.error != null && outcome.response == null -> respond(status, outcome.error)
        else -> respond(
            HttpStatusCode.Conflict,
            ApiError("idempotency_state_invalid", "the stored transcription result is invalid")
        )
    }
}

private fun String.toUuidOrNull(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()

private val SUPPORTED_AUDIO_MIME_TYPES =
    setOf(
        "application/octet-stream",
        "application/ogg",
        "audio/m4a",
        "audio/mp3",
        "audio/mp4",
        "audio/mpeg",
        "audio/ogg",
        "audio/wav",
        "audio/webm",
        "audio/x-m4a",
        "audio/x-wav",
        "video/mp4"
    )

/** Explicit provider rejections that occur before accepted processing; 5xx/408 stay uncertain. */
private val DEFINITIVELY_UNBILLED_HTTP_STATUSES =
    setOf(400, 401, 402, 403, 404, 405, 413, 415, 422, 429)
