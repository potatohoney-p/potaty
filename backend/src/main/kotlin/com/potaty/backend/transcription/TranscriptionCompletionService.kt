/*
 * Copyright (c) 2026, Potaty
 *
 * Crash-safe completion of a checkpointed transcription. Source, version, chunks, usage, replay
 * outcome, and reservation release commit in one transaction under a rotating processing token.
 */

package com.potaty.backend.transcription

import com.potaty.backend.cost.CostReservationStateConflictException
import com.potaty.backend.persistence.CostReservationsTable
import com.potaty.backend.persistence.TenantIntegrityException
import com.potaty.backend.persistence.TransactionContext
import com.potaty.backend.persistence.jsonDocumentsEqual
import com.potaty.backend.persistence.repositories.SourceRepository
import com.potaty.backend.usage.UsageRecorder
import io.ktor.http.HttpStatusCode
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select

@Serializable
internal data class TranscriptionCheckpoint(
    val schemaVersion: Int = 1,
    val requestHash: String,
    val bookedCostUsd: Double,
    val prepared: PreparedTranscript
)

internal data class TranscriptionCompletionCommand(
    val workspaceId: UUID,
    val projectId: UUID,
    val reservationId: UUID,
    val processingToken: UUID,
    val requestHash: String,
    val checkpointJson: String,
    val checkpoint: TranscriptionCheckpoint,
    val displayName: String,
    val externalRefJson: String,
    val createdBy: UUID?,
    val provider: String,
    val model: String,
    val stage: String,
    val at: Instant
)

internal fun interface TranscriptionCompleter {
    suspend fun complete(command: TranscriptionCompletionCommand): TranscriptionResponse
}

internal class TranscriptionCompletionService(
    private val txc: TransactionContext,
    private val sources: SourceRepository,
    private val ingestor: TranscriptIngestor,
    private val usage: UsageRecorder,
    private val json: Json
) : TranscriptionCompleter {

    override suspend fun complete(command: TranscriptionCompletionCommand): TranscriptionResponse {
        validate(command)
        return txc.tx {
            val reservation =
                CostReservationsTable
                    .select {
                        (CostReservationsTable.workspaceId eq command.workspaceId) and
                            (CostReservationsTable.id eq command.reservationId)
                    }
                    .forUpdate()
                    .limit(1)
                    .singleOrNull()
                    ?: throw TenantIntegrityException("Cost reservation not found")

            if (reservation[CostReservationsTable.requestHash] != command.requestHash) {
                throw CostReservationStateConflictException(
                    "Transcription checkpoint request hash no longer matches"
                )
            }
            reservation[CostReservationsTable.externalResult]?.let { stored ->
                val replay =
                    runCatching { json.decodeFromString<TranscriptionAttemptOutcome>(stored) }
                        .getOrNull()
                        ?.response
                        ?: throw CostReservationStateConflictException(
                            "Stored transcription completion is invalid"
                        )
                return@tx replay
            }
            if (
                reservation[CostReservationsTable.releasedAt] != null ||
                reservation[CostReservationsTable.jobId] != null ||
                reservation[CostReservationsTable.externalSpendStartedAt] == null ||
                reservation[CostReservationsTable.processingToken] != command.processingToken ||
                !jsonDocumentsEqual(
                    reservation[CostReservationsTable.externalCheckpoint],
                    command.checkpointJson
                ) ||
                reservation[CostReservationsTable.externalProvider] != command.provider ||
                reservation[CostReservationsTable.externalModel] != command.model ||
                reservation[CostReservationsTable.externalStage] != command.stage
            ) {
                throw CostReservationStateConflictException(
                    "Transcription completion fence is no longer active"
                )
            }

            val stored =
                sources.createTranscriptInCurrentTransaction(
                    workspaceId = command.workspaceId,
                    projectId = command.projectId,
                    displayName = command.displayName,
                    externalRefJson = command.externalRefJson,
                    createdBy = command.createdBy,
                    contentHash = command.checkpoint.prepared.contentHash,
                    metadataJson =
                    metadata(
                        command.checkpoint.prepared.segmentCount,
                        command.checkpoint.prepared.chunks.size
                    ),
                    chunks = command.checkpoint.prepared.chunks,
                    ingestionKey = command.requestHash
                )
            val response =
                TranscriptionResponse(
                    sourceId = stored.sourceId.toString(),
                    sourceVersionId = stored.sourceVersionId.toString(),
                    contentHash = stored.contentHash,
                    chunkCount = stored.chunkCount,
                    segmentCount = command.checkpoint.prepared.segmentCount,
                    status = "transcribed"
                )
            val outcome =
                TranscriptionAttemptOutcome(
                    statusCode = HttpStatusCode.Created.value,
                    response = response
                )
            usage.recordAndSettleReservationInCurrentTransaction(
                workspaceId = command.workspaceId,
                reservationId = command.reservationId,
                processingToken = command.processingToken,
                provider = command.provider,
                model = command.model,
                stage = command.stage,
                inputTokens = 0,
                outputTokens = 0,
                estimatedCostUsd = command.checkpoint.bookedCostUsd,
                externalResultJson = json.encodeToString(outcome),
                at = command.at
            )
            response
        }
    }

    private fun validate(command: TranscriptionCompletionCommand) {
        require(command.checkpoint.schemaVersion == 1) { "unsupported checkpoint schema" }
        require(command.checkpoint.requestHash == command.requestHash) {
            "checkpoint request hash does not match"
        }
        require(
            command.checkpoint.bookedCostUsd.isFinite() &&
                command.checkpoint.bookedCostUsd >= 0.0
        ) {
            "checkpoint cost is invalid"
        }
        ingestor.validatePrepared(command.checkpoint.prepared)
    }

    private fun metadata(segmentCount: Int, chunkCount: Int): String =
        """{"kind":"transcript","segmentCount":$segmentCount,"chunkCount":$chunkCount}"""
}
