/*
 * Copyright (c) 2026, Potaty
 *
 * Transactional crash-recovery tests for checkpointed transcription completion.
 */

package com.potaty.backend

import com.potaty.backend.cost.CostConfig
import com.potaty.backend.cost.CostEstimate
import com.potaty.backend.cost.QuotaGuard
import com.potaty.backend.llm.provider.TranscriptArtifact
import com.potaty.backend.llm.provider.TranscriptSegment
import com.potaty.backend.persistence.Database
import com.potaty.backend.persistence.IdentityRepository
import com.potaty.backend.persistence.TransactionContext
import com.potaty.backend.persistence.bootstrapDevelopmentIdentity
import com.potaty.backend.persistence.repositories.SourceRepository
import com.potaty.backend.transcription.TranscriptIngestor
import com.potaty.backend.transcription.TranscriptionCheckpoint
import com.potaty.backend.transcription.TranscriptionCompletionCommand
import com.potaty.backend.transcription.TranscriptionCompletionService
import com.potaty.backend.usage.UsageRecorder
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class TranscriptionCompletionServiceTest {

    @Test
    fun usageFailureRollsBackSourceAndCheckpointRetryCompletesExactlyOnce() = runBlocking {
        val config = testConfig()
        val database = Database.connect(config.database)
        val txc = TransactionContext(database.exposed)
        bootstrapDevelopmentIdentity(txc, config.auth)
        val workspaceId = UUID.fromString(config.auth.devWorkspaceId)
        val projectId = UUID.fromString(config.auth.devProjectId)
        val userId = UUID.fromString(config.auth.devUserId)
        val identities = IdentityRepository(txc)
        val sources = SourceRepository(txc, identities)
        val ingestor = TranscriptIngestor(sources)
        val usage = UsageRecorder(txc)
        val guard = QuotaGuard(txc, CostConfig(monthlyCapUsd = 10.0))
        val json =
            Json {
                encodeDefaults = true
                ignoreUnknownKeys = true
            }
        val now = Instant.parse("2026-06-15T12:00:00Z")
        val requestHash = "sha256:${"f".repeat(64)}"
        val metadataJson = """{"projectId":"$projectId"}"""

        try {
            val reservation =
                guard.reserve(
                    workspaceId = workspaceId,
                    reservationKey = "transcription:atomic-completion",
                    estimate = CostEstimate(1.0, 3.0),
                    now = now,
                    requestHash = requestHash,
                    externalOperation = "transcription",
                    externalProvider = "openai",
                    externalModel = "whisper-1",
                    externalStage = "transcription",
                    externalMetadataJson = metadataJson
                )
            val token =
                guard.markExternalSpendStarted(workspaceId, reservation.id, now.plusSeconds(1))
            val prepared =
                ingestor.prepare(
                    TranscriptArtifact(
                        text = "Alice: ship the API",
                        segments =
                        listOf(
                            TranscriptSegment(
                                startMs = 0,
                                endMs = 1_000,
                                speaker = "Alice",
                                text = "ship the API"
                            )
                        )
                    )
                )
            val checkpoint =
                TranscriptionCheckpoint(
                    requestHash = requestHash,
                    bookedCostUsd = 1.25,
                    prepared = prepared
                )
            val checkpointJson = json.encodeToString(checkpoint)
            guard.saveExternalCheckpoint(
                workspaceId,
                reservation.id,
                token,
                checkpointJson,
                now.plusSeconds(2)
            )

            val duplicateEventId = UUID.randomUUID()
            val failingUsage = UsageRecorder(txc) { duplicateEventId }
            failingUsage.record(
                workspaceId = workspaceId,
                jobId = null,
                provider = "test",
                model = "seed",
                stage = "collision",
                inputTokens = 0,
                outputTokens = 0,
                estimatedCostUsd = 0.0,
                at = now
            )
            val command =
                TranscriptionCompletionCommand(
                    workspaceId = workspaceId,
                    projectId = projectId,
                    reservationId = reservation.id,
                    processingToken = token,
                    requestHash = requestHash,
                    checkpointJson = checkpointJson,
                    checkpoint = checkpoint,
                    displayName = "Release meeting",
                    externalRefJson =
                    """{"provider":"openai","attemptHash":"$requestHash"}""",
                    createdBy = userId,
                    provider = "openai",
                    model = "whisper-1",
                    stage = "transcription",
                    at = now.plusSeconds(3)
                )
            val failingCompletion =
                TranscriptionCompletionService(txc, sources, ingestor, failingUsage, json)

            assertFails { failingCompletion.complete(command) }
            assertEquals(0, sources.listSources(workspaceId, projectId).size)
            assertEquals(3.0, guard.snapshot(workspaceId, now.plusSeconds(4)).reservedUsd)
            val pendingReplay =
                guard.reserve(
                    workspaceId = workspaceId,
                    reservationKey = "transcription:atomic-completion",
                    estimate = CostEstimate(1.0, 3.0),
                    now = now.plusSeconds(4),
                    requestHash = requestHash,
                    externalOperation = "transcription",
                    externalProvider = "openai",
                    externalModel = "whisper-1",
                    externalStage = "transcription",
                    externalMetadataJson = metadataJson
                )
            assertNotNull(pendingReplay.externalCheckpointJson)

            val completion = TranscriptionCompletionService(txc, sources, ingestor, usage, json)
            val completed = completion.complete(command)
            val replayed = completion.complete(command)
            assertEquals(completed, replayed)
            assertEquals(1, sources.listSources(workspaceId, projectId).size)
            assertEquals(1.25, usage.sumCostThisMonth(workspaceId, now.plusSeconds(5)), 1e-9)
            assertEquals(0.0, guard.snapshot(workspaceId, now.plusSeconds(5)).reservedUsd, 1e-9)

            val settledReplay =
                guard.reserve(
                    workspaceId = workspaceId,
                    reservationKey = "transcription:atomic-completion",
                    estimate = CostEstimate(1.0, 3.0),
                    now = now.plusSeconds(5),
                    requestHash = requestHash,
                    externalOperation = "transcription",
                    externalProvider = "openai",
                    externalModel = "whisper-1",
                    externalStage = "transcription",
                    externalMetadataJson = metadataJson
                )
            assertNull(settledReplay.externalCheckpointJson)
            assertNotNull(settledReplay.externalResultJson)
        } finally {
            database.close()
        }
    }
}
