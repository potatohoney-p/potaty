/*
 * Copyright (c) 2026, Potaty
 *
 * Source ingestion routes (plan 10.1). Normalises + chunks pasted text/markdown/transcripts and
 * persists source + version + chunks. Every handler is tenant-scoped (RBAC + workspace id).
 */

package com.potaty.backend.api

import com.potaty.backend.AppGraph
import com.potaty.backend.auth.Permission
import com.potaty.backend.auth.Rbac
import com.potaty.backend.auth.tenant
import com.potaty.backend.jobs.Idempotency
import com.potaty.backend.persistence.repositories.AtomicSourceRecord
import com.potaty.backend.source.Chunker
import com.potaty.backend.source.SourceSafetyGateway
import com.potaty.backend.source.TranscriptChunker
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.util.UUID
import kotlinx.serialization.Serializable

/** Max pasted-content length accepted in a single request (plan section 12.4, Pro tier). */
private const val MAX_CONTENT_CHARS = 500_000
private const val MAX_DISPLAY_NAME_CHARS = 200
private val TEXT_SOURCE_TYPES = setOf("TEXT_PASTE", "TRANSCRIPT")

@Serializable
private data class SourceIngestionMetadata(
    val kind: String,
    val chunkCount: Int,
    val secretsRedacted: Int,
    val piiWarnings: Int
)

fun Route.sourceRoutes(graph: AppGraph) {
    route("/projects/{projectId}/sources") {
        // POST /api/v1/projects/{projectId}/sources
        post {
            val tenant = call.tenant()
            Rbac.require(tenant, Permission.CREATE_SOURCE)

            val workspaceId = runCatching { UUID.fromString(tenant.workspaceId) }.getOrNull()
                ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("bad_request", "invalid workspace")
                )
            val projectId = call.parameters["projectId"]?.let {
                runCatching { UUID.fromString(it) }.getOrNull()
            }
                ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("bad_request", "missing/invalid projectId")
                )

            val idempotencyKey = call.request.headers["Idempotency-Key"]
                ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("bad_request", "missing Idempotency-Key header")
                )
            if (!isValidMutationIdempotencyKey(idempotencyKey)) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("bad_request", MUTATION_IDEMPOTENCY_KEY_MESSAGE)
                )
            }

            val body = call.receive<CreateSourceRequest>()
            val sourceType = body.sourceType.trim().uppercase()
            if (sourceType !in TEXT_SOURCE_TYPES) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError(
                        "bad_request",
                        "sourceType must be TEXT_PASTE or TRANSCRIPT for this endpoint"
                    )
                )
            }
            val displayName = body.displayName.replace(Regex("""\s+"""), " ").trim()
            if (displayName.length > MAX_DISPLAY_NAME_CHARS) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError(
                        "bad_request",
                        "displayName must contain at most $MAX_DISPLAY_NAME_CHARS characters"
                    )
                )
            }
            val content = body.content
            if (content.isNullOrBlank()) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("bad_request", "content is required for text sources")
                )
            }
            if (content.length > MAX_CONTENT_CHARS) {
                return@post call.respond(
                    HttpStatusCode.PayloadTooLarge,
                    ApiError("payload_too_large", "content exceeds $MAX_CONTENT_CHARS characters")
                )
            }

            val createdBy = runCatching { UUID.fromString(tenant.userId) }.getOrNull()
            val normalizedBody =
                body.copy(sourceType = sourceType, displayName = displayName)
            val requestHash =
                "sha256:" +
                    Idempotency.sha256(
                        graph.json.encodeToString(CreateSourceRequest.serializer(), normalizedBody)
                    )
            val stableIngestionKey = "source:$idempotencyKey"
            val replay =
                graph.sources.findAtomicIngestion(
                    workspaceId = workspaceId,
                    projectId = projectId,
                    sourceType = sourceType,
                    ingestionKey = stableIngestionKey,
                    requestHash = requestHash
                )
            if (replay != null) {
                return@post call.respond(
                    HttpStatusCode.Created,
                    replay.toCreateSourceResponse(graph)
                )
            }

            val safe = SourceSafetyGateway.process(content)
            val transcriptChunks = if (sourceType == "TRANSCRIPT") {
                TranscriptChunker.chunk(safe.canonicalText)
            } else {
                null
            }
            val contentHash = "sha256:" + safe.contentHash
            val chunks = transcriptChunks ?: emptyList()
            val plainChunks =
                if (transcriptChunks == null) Chunker.chunk(safe.canonicalText) else emptyList()
            val metadata =
                SourceIngestionMetadata(
                    kind = if (sourceType == "TRANSCRIPT") "transcript" else "text",
                    chunkCount = if (transcriptChunks != null) chunks.size else plainChunks.size,
                    secretsRedacted = safe.secretFindings.size,
                    piiWarnings = safe.piiFindings.size
                )
            val metadataJson =
                graph.json.encodeToString(SourceIngestionMetadata.serializer(), metadata)
            val stored = if (transcriptChunks != null) {
                graph.sources.createTranscriptAtomic(
                    workspaceId = workspaceId,
                    projectId = projectId,
                    displayName = displayName.ifBlank { "Untitled source" },
                    externalRefJson = "{}",
                    createdBy = createdBy,
                    contentHash = contentHash,
                    metadataJson = metadataJson,
                    chunks = chunks,
                    ingestionKey = stableIngestionKey,
                    requestHash = requestHash
                )
            } else {
                graph.sources.createTextSourceAtomic(
                    workspaceId = workspaceId,
                    projectId = projectId,
                    sourceType = sourceType,
                    displayName = displayName.ifBlank { "Untitled source" },
                    externalRefJson = "{}",
                    createdBy = createdBy,
                    contentHash = contentHash,
                    metadataJson = metadataJson,
                    chunks = plainChunks,
                    ingestionKey = stableIngestionKey,
                    requestHash = requestHash
                )
            }

            call.respond(
                HttpStatusCode.Created,
                stored.toCreateSourceResponse(graph)
            )
        }
    }
}

private fun AtomicSourceRecord.toCreateSourceResponse(graph: AppGraph): CreateSourceResponse {
    val metadata = runCatching {
        graph.json.decodeFromString(SourceIngestionMetadata.serializer(), metadataJson)
    }.getOrElse { throw IllegalStateException("Stored source metadata is invalid", it) }
    check(metadata.chunkCount == chunkCount) { "Stored source chunks are incomplete" }
    return CreateSourceResponse(
        sourceId = sourceId.toString(),
        sourceVersionId = sourceVersionId.toString(),
        contentHash = contentHash,
        status = "normalized",
        secretsRedacted = metadata.secretsRedacted,
        piiWarnings = metadata.piiWarnings
    )
}
