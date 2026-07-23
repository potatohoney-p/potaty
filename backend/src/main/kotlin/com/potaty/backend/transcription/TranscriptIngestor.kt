/*
 * Copyright (c) 2026, Potaty
 *
 * Transcript ingestion (plan 4.3 source pipeline; 12.1/12.2 normalize + chunk; 14.2 transcript
 * evidence). Given a transcribed [TranscriptArtifact], this turns it into a first-class TRANSCRIPT
 * source the diagram pipeline can ground on:
 *
 *   1. Render the artifact to a canonical transcript text. Each segment becomes one line, prefixed
 *      with its timestamp and (when known) its speaker — i.e. "[hh:mm:ss] Speaker: text" — so the
 *      structure the TranscriptChunker recognises is present in the stored text and the chunker can
 *      re-derive speaker turns + time ranges. (OpenAI whisper-1 doesn't diarize, so speaker is
 *      usually absent and lines render as "[hh:mm:ss] text".)
 *   2. Normalize + redact through SourceSafetyGateway for safe storage and a stable content hash.
 *   3. Chunk with TranscriptChunker, then restore the exact provider segment timestamps when the
 *      one-line-per-segment representation maps one-to-one. This avoids losing sub-second
 *      precision or the final segment's end time during the safe text round trip.
 *   4. Persist Source ("TRANSCRIPT") + SourceVersion + chunks via SourceRepository — the SAME
 *      tenant-scoped persistence the paste / GitHub paths use, and the SAME source_chunks table
 *      whose start_ms / end_ms / speaker columns exist precisely for this.
 *
 * Everything is workspace-scoped; the ingestor never reads or writes outside the given workspaceId.
 */

package com.potaty.backend.transcription

import com.potaty.backend.llm.provider.TranscriptArtifact
import com.potaty.backend.persistence.repositories.SourceRepository
import com.potaty.backend.source.SourceSafetyGateway
import com.potaty.backend.source.TranscriptChunker
import java.util.UUID
import kotlinx.serialization.Serializable

/** Ids + counts the caller (route/job) returns to the client after ingesting a transcript. */
data class TranscriptIngestResult(
    val sourceId: UUID,
    val sourceVersionId: UUID,
    val contentHash: String,
    val chunkCount: Int,
    val segmentCount: Int
)

@Serializable
data class PreparedTranscript(
    val contentHash: String,
    val segmentCount: Int,
    val chunks: List<com.potaty.backend.source.TranscriptChunk>
)

class TranscriptIngestor(
    private val sources: SourceRepository
) {
    /**
     * Ingests [artifact] as a TRANSCRIPT source under [workspaceId]/[projectId]. [displayName] is
     * the human label; [externalRefJson] records provenance (e.g. the audio object key + model).
     */
    suspend fun ingest(
        workspaceId: UUID,
        projectId: UUID,
        artifact: TranscriptArtifact,
        displayName: String,
        externalRefJson: String,
        createdBy: UUID?
    ): TranscriptIngestResult {
        return ingestPrepared(
            workspaceId = workspaceId,
            projectId = projectId,
            prepared = prepare(artifact),
            displayName = displayName,
            externalRefJson = externalRefJson,
            createdBy = createdBy,
            ingestionKey = null
        )
    }

    /** Redacts and chunks once; this bounded form is safe to persist as a retry checkpoint. */
    fun prepare(artifact: TranscriptArtifact): PreparedTranscript {
        val transcriptText = renderTranscript(artifact)
        val safe = SourceSafetyGateway.process(transcriptText)
        val parsedChunks = TranscriptChunker.chunk(safe.canonicalText)
        val chunks = if (parsedChunks.size == artifact.segments.size) {
            parsedChunks.mapIndexed { index, chunk ->
                val segment = artifact.segments[index]
                chunk.copy(
                    startMs = segment.startMs,
                    endMs = segment.endMs
                )
            }
        } else {
            parsedChunks
        }
        val prepared =
            PreparedTranscript(
                contentHash = "sha256:" + safe.contentHash,
                segmentCount = artifact.segments.size,
                chunks = chunks
            )
        validatePrepared(prepared)
        return prepared
    }

    /** Atomically persists a prepared transcript, replaying an existing ingestion key if present. */
    suspend fun ingestPrepared(
        workspaceId: UUID,
        projectId: UUID,
        prepared: PreparedTranscript,
        displayName: String,
        externalRefJson: String,
        createdBy: UUID?,
        ingestionKey: String?
    ): TranscriptIngestResult {
        validatePrepared(prepared)
        val stored = sources.createTranscriptAtomic(
            workspaceId = workspaceId,
            projectId = projectId,
            displayName = displayName,
            externalRefJson = externalRefJson,
            createdBy = createdBy,
            contentHash = prepared.contentHash,
            metadataJson = metadata(prepared.segmentCount, prepared.chunks.size),
            chunks = prepared.chunks,
            ingestionKey = ingestionKey
        )

        return TranscriptIngestResult(
            sourceId = stored.sourceId,
            sourceVersionId = stored.sourceVersionId,
            contentHash = stored.contentHash,
            chunkCount = stored.chunkCount,
            segmentCount = prepared.segmentCount
        )
    }

    internal fun validatePrepared(prepared: PreparedTranscript) {
        require(PREPARED_HASH_PATTERN.matches(prepared.contentHash)) {
            "prepared transcript content hash is invalid"
        }
        require(prepared.segmentCount >= 0) { "prepared transcript segment count is invalid" }
        require(prepared.chunks.size <= MAX_PREPARED_CHUNKS) {
            "prepared transcript has too many chunks"
        }
        require(prepared.chunks.sumOf { it.text.length } <= MAX_PREPARED_TEXT_CHARS) {
            "prepared transcript is too large"
        }
        prepared.chunks.forEachIndexed { index, chunk ->
            require(chunk.chunkIndex == index) { "prepared transcript chunk order is invalid" }
            require(chunk.startLine >= 1 && chunk.endLine >= chunk.startLine) {
                "prepared transcript chunk range is invalid"
            }
            require(chunk.startMs == null || chunk.startMs >= 0) {
                "prepared transcript start time is invalid"
            }
            require(chunk.endMs == null || chunk.endMs >= (chunk.startMs ?: 0)) {
                "prepared transcript end time is invalid"
            }
        }
    }

    /**
     * Renders a [TranscriptArtifact] into canonical transcript text the TranscriptChunker can parse:
     * one line per segment, "[hh:mm:ss] " timestamp prefix (when the segment has a real start), then
     * "Speaker: " when known, then the segment text. Falls back to the artifact's full text when no
     * segments are present.
     */
    internal fun renderTranscript(artifact: TranscriptArtifact): String {
        if (artifact.segments.isEmpty()) return artifact.text
        return artifact.segments.joinToString("\n") { seg ->
            val sb = StringBuilder()
            // start==0 is rendered too (it's a legitimate timestamp), but only when at least one
            // segment is timed; a single 0,0 fallback segment from the parser will still render
            // "[00:00:00] " which the chunker tolerates.
            sb.append('[').append(formatHms(seg.startMs)).append("] ")
            val speaker = seg.speaker?.trim()
            if (!speaker.isNullOrEmpty()) sb.append(speaker).append(": ")
            sb.append(seg.text.trim())
            sb.toString()
        }
    }

    /** ms -> "hh:mm:ss" for a stable, chunker-recognisable bracketed timestamp. */
    private fun formatHms(ms: Int): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0)
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return "%02d:%02d:%02d".format(h, m, s)
    }

    private fun metadata(segmentCount: Int, chunkCount: Int): String =
        """{"kind":"transcript","segmentCount":$segmentCount,"chunkCount":$chunkCount}"""

    private companion object {
        val PREPARED_HASH_PATTERN = Regex("^sha256:[0-9a-f]{64}$")
        const val MAX_PREPARED_CHUNKS = 100_000
        const val MAX_PREPARED_TEXT_CHARS = 2 * 1024 * 1024
    }
}
