/*
 * Copyright (c) 2026, Potaty
 *
 * H2-backed tests for usage recording and durable, atomic per-workspace quota reservations.
 */

package com.potaty.backend

import com.potaty.backend.config.EnvConfig
import com.potaty.backend.cost.CostConfig
import com.potaty.backend.cost.CostEstimate
import com.potaty.backend.cost.CostReservationStateConflictException
import com.potaty.backend.cost.QuotaExceededException
import com.potaty.backend.cost.QuotaGuard
import com.potaty.backend.jobs.JobStatus
import com.potaty.backend.persistence.AuditEventsTable
import com.potaty.backend.persistence.CostReservationsTable
import com.potaty.backend.persistence.Database
import com.potaty.backend.persistence.TenantIntegrityException
import com.potaty.backend.persistence.TransactionContext
import com.potaty.backend.persistence.bootstrapDevelopmentIdentity
import com.potaty.backend.persistence.repositories.JobRepository
import com.potaty.backend.usage.ExternalSpendDecision
import com.potaty.backend.usage.UsageRecorder
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update

class QuotaGuardTest {
    private val now: Instant = Instant.parse("2026-06-15T12:00:00Z")

    @Test
    fun sumCostThisMonthIsTenantScopedMonthBoundedAndPreservesCachedTokens() = runBlocking {
        fixture().use { f ->
            val otherWs = UUID.randomUUID()
            val recorded =
                f.usage.record(
                    f.workspaceId,
                    null,
                    "anthropic",
                    "m",
                    "extract",
                    100,
                    50,
                    0.10,
                    cachedInputTokens = 25,
                    at = now
                )
            assertEquals(25, recorded.cachedInputTokens)
            f.usage.record(
                f.workspaceId,
                null,
                "anthropic",
                "m",
                "plan",
                100,
                50,
                0.25,
                at = now.minus(2, ChronoUnit.DAYS)
            )
            f.usage.record(
                f.workspaceId,
                null,
                "anthropic",
                "m",
                "old",
                100,
                50,
                5.00,
                at = now.minus(40, ChronoUnit.DAYS)
            )
            f.usage.record(otherWs, null, "anthropic", "m", "extract", 100, 50, 9.99, at = now)

            assertEquals(0.35, f.usage.sumCostThisMonth(f.workspaceId, now), 1e-9)
            assertEquals(0.0, f.usage.sumCostThisMonth(UUID.randomUUID(), now), 1e-9)
        }
    }

    @Test
    fun reservationAdmitsUnderCapAndReleases() = runBlocking {
        fixture(cap = 10.0).use { f ->
            f.usage.record(
                f.workspaceId,
                null,
                "anthropic",
                "m",
                "extract",
                100,
                50,
                2.00,
                at = now
            )
            val reservation =
                f.guard.reserve(
                    f.workspaceId,
                    "job-a",
                    CostEstimate(lowUsd = 0.5, highUsd = 3.0),
                    now
                )
            assertEquals(2.00, reservation.check.monthToDateUsd, 1e-9)
            assertEquals(3.00, reservation.check.reservedUsd, 1e-9)
            assertEquals(5.00, reservation.check.projectedUsd, 1e-9)
            assertEquals(10.0, reservation.check.capUsd)

            f.guard.release(f.workspaceId, reservation.id, now.plusSeconds(1))
            assertEquals(0.0, f.guard.snapshot(f.workspaceId, now.plusSeconds(2)).reservedUsd, 1e-9)
        }
    }

    @Test
    fun reservationThrowsWhenProjectedExceedsCapAndUsesHighEnd() = runBlocking {
        fixture(cap = 10.0).use { f ->
            f.usage.record(
                f.workspaceId,
                null,
                "anthropic",
                "m",
                "extract",
                100,
                50,
                8.00,
                at = now
            )
            val ex =
                assertFailsWith<QuotaExceededException> {
                    f.guard.reserve(
                        f.workspaceId,
                        "job-b",
                        CostEstimate(lowUsd = 1.0, highUsd = 3.0),
                        now
                    )
                }
            assertEquals(f.workspaceId, ex.workspaceId)
            assertEquals(8.00, ex.monthToDateUsd, 1e-9)
            assertEquals(0.0, ex.reservedUsd, 1e-9)
            assertEquals(3.00, ex.estimateHighUsd, 1e-9)
            assertEquals(10.0, ex.capUsd, 1e-9)
        }
    }

    @Test
    fun reservationKeyIsIdempotent() = runBlocking {
        fixture(cap = 10.0).use { f ->
            val first =
                f.guard.reserve(f.workspaceId, "same", CostEstimate(1.0, 3.0), now)
            val replay =
                f.guard.reserve(f.workspaceId, "same", CostEstimate(1.0, 3.0), now)
            assertEquals(first.id, replay.id)
            assertTrue(first.acquired)
            assertEquals(false, replay.acquired)
            assertEquals(3.0, f.guard.snapshot(f.workspaceId, now).reservedUsd, 1e-9)
        }
    }

    @Test
    fun releasedReservationReactivationRechecksCurrentCap() = runBlocking {
        fixture(cap = 10.0).use { f ->
            val first = f.guard.reserve(f.workspaceId, "reactivate", CostEstimate(1.0, 3.0), now)
            f.guard.release(f.workspaceId, first.id, now.plusSeconds(1))
            f.usage.record(
                f.workspaceId,
                null,
                "openai",
                "m",
                "later-spend",
                0,
                0,
                9.0,
                at = now.plusSeconds(2)
            )

            assertFailsWith<QuotaExceededException> {
                f.guard.reserve(
                    f.workspaceId,
                    "reactivate",
                    CostEstimate(1.0, 3.0),
                    now.plusSeconds(3)
                )
            }
            assertEquals(0.0, f.guard.snapshot(f.workspaceId, now.plusSeconds(4)).reservedUsd)
        }
    }

    @Test
    fun expiredUnattachedReservationCanBeSafelyReactivated() = runBlocking {
        fixture(cap = 10.0).use { f ->
            val first = f.guard.reserve(f.workspaceId, "expired", CostEstimate(1.0, 3.0), now)
            val afterExpiry = now.plus(25, ChronoUnit.HOURS)
            assertEquals(0.0, f.guard.snapshot(f.workspaceId, afterExpiry).reservedUsd)

            val reactivated =
                f.guard.reserve(
                    f.workspaceId,
                    "expired",
                    CostEstimate(1.0, 4.0),
                    afterExpiry
                )
            assertEquals(first.id, reactivated.id)
            assertTrue(reactivated.acquired)
            assertEquals(4.0, reactivated.check.reservedUsd, 1e-9)
        }
    }

    @Test
    fun attachedReservationFollowsAuthoritativeJobStateBeyondTtl() = runBlocking {
        fixture(cap = 10.0).use { f ->
            val reservation =
                f.guard.reserve(f.workspaceId, "attached", CostEstimate(1.0, 3.0), now)
            val job =
                f.jobs.enqueue(
                    workspaceId = f.workspaceId,
                    projectId = null,
                    jobType = "DIAGRAM_GENERATION",
                    idempotencyKey = "attached-job",
                    inputJson = "{}",
                    createdBy = null
                )
            f.guard.attachToJob(f.workspaceId, reservation.id, job.id, now)

            val afterExpiry = now.plus(25, ChronoUnit.HOURS)
            assertEquals(3.0, f.guard.snapshot(f.workspaceId, afterExpiry).reservedUsd, 1e-9)

            f.jobs.markStatus(f.workspaceId, job.id, JobStatus.FAILED)
            assertEquals(0.0, f.guard.snapshot(f.workspaceId, afterExpiry).reservedUsd, 1e-9)
        }
    }

    @Test
    fun externalSpendReservationSurvivesExpiryUntilUsageSettlesAtomically() = runBlocking {
        fixture(cap = 10.0).use { f ->
            val reservation =
                f.guard.reserve(
                    workspaceId = f.workspaceId,
                    reservationKey = "external",
                    estimate = CostEstimate(1.0, 3.0),
                    now = now,
                    requestHash = "sha256:${"a".repeat(64)}",
                    externalOperation = "transcription",
                    externalProvider = "openai",
                    externalModel = "whisper-1",
                    externalStage = "transcription"
                )
            val processingToken =
                f.guard.markExternalSpendStarted(
                    f.workspaceId,
                    reservation.id,
                    now.plusSeconds(1)
                )

            val afterOrdinaryExpiry = now.plus(25, ChronoUnit.HOURS)
            f.guard.release(f.workspaceId, reservation.id, now.plusSeconds(2))
            assertEquals(
                3.0,
                f.guard.snapshot(f.workspaceId, afterOrdinaryExpiry).reservedUsd,
                1e-9
            )

            f.usage.recordAndSettleReservation(
                workspaceId = f.workspaceId,
                reservationId = reservation.id,
                processingToken = processingToken,
                provider = "openai",
                model = "whisper-1",
                stage = "transcription",
                inputTokens = 0,
                outputTokens = 0,
                estimatedCostUsd = 1.25,
                externalResultJson = """{"statusCode":201}""",
                at = now.plusSeconds(2)
            )
            val settled = f.guard.snapshot(f.workspaceId, now.plusSeconds(3))
            assertEquals(1.25, settled.monthToDateUsd, 1e-9)
            assertEquals(0.0, settled.reservedUsd, 1e-9)
        }
    }

    @Test
    fun usageInsertFailureLeavesExternalSpendReservationCountedBeyondExpiry() = runBlocking {
        fixture(cap = 10.0).use { f ->
            val duplicateEventId = UUID.randomUUID()
            val failingUsage = UsageRecorder(f.txc) { duplicateEventId }
            failingUsage.record(
                workspaceId = f.workspaceId,
                jobId = null,
                provider = "test",
                model = "seed",
                stage = "collision",
                inputTokens = 0,
                outputTokens = 0,
                estimatedCostUsd = 0.0,
                at = now
            )
            val reservation =
                f.guard.reserve(
                    workspaceId = f.workspaceId,
                    reservationKey = "failed-accounting",
                    estimate = CostEstimate(1.0, 3.0),
                    now = now,
                    requestHash = "sha256:${"b".repeat(64)}",
                    externalOperation = "transcription",
                    externalProvider = "openai",
                    externalModel = "whisper-1",
                    externalStage = "transcription"
                )
            val processingToken =
                f.guard.markExternalSpendStarted(
                    f.workspaceId,
                    reservation.id,
                    now.plusSeconds(1)
                )

            assertFails {
                failingUsage.recordAndSettleReservation(
                    workspaceId = f.workspaceId,
                    reservationId = reservation.id,
                    processingToken = processingToken,
                    provider = "openai",
                    model = "whisper-1",
                    stage = "transcription",
                    inputTokens = 0,
                    outputTokens = 0,
                    estimatedCostUsd = 1.25,
                    externalResultJson = """{"statusCode":201}""",
                    at = now.plusSeconds(2)
                )
            }

            val afterOrdinaryExpiry = now.plus(25, ChronoUnit.HOURS)
            val snapshot = f.guard.snapshot(f.workspaceId, afterOrdinaryExpiry)
            assertEquals(3.0, snapshot.reservedUsd, 1e-9)
            assertEquals(0.0, snapshot.monthToDateUsd, 1e-9)
        }
    }

    @Test
    fun ownerReconciliationChargesReleasesAndAuditsPendingExternalSpendAtomically() = runBlocking {
        fixture(cap = 10.0).use { f ->
            val reservation =
                f.guard.reserve(
                    workspaceId = f.workspaceId,
                    reservationKey = "reconcile",
                    estimate = CostEstimate(1.0, 3.0),
                    now = now,
                    requestHash = "sha256:${"c".repeat(64)}",
                    externalOperation = "transcription",
                    externalProvider = "openai",
                    externalModel = "whisper-1",
                    externalStage = "transcription"
                )
            f.guard.markExternalSpendStarted(f.workspaceId, reservation.id, now.plusSeconds(1))
            assertEquals(
                0,
                f.guard.listPendingExternalSpend(
                    workspaceId = f.workspaceId,
                    now = now.plusSeconds(2)
                ).size
            )
            assertFailsWith<CostReservationStateConflictException> {
                f.usage.reconcileExternalSpend(
                    workspaceId = f.workspaceId,
                    reservationId = reservation.id,
                    actorUserId = f.userId,
                    decision = ExternalSpendDecision.RELEASE,
                    chargeUsd = null,
                    reason = "Provider call is still active.",
                    at = now.plusSeconds(2)
                )
            }
            val reconciliationTime = now.plus(11, ChronoUnit.MINUTES)
            assertEquals(
                1,
                f.guard.listPendingExternalSpend(
                    workspaceId = f.workspaceId,
                    now = reconciliationTime
                ).size
            )

            val auditSecret = "sk-" + "R".repeat(32)
            assertFailsWith<IllegalArgumentException> {
                f.usage.reconcileExternalSpend(
                    workspaceId = f.workspaceId,
                    reservationId = reservation.id,
                    actorUserId = f.userId,
                    decision = ExternalSpendDecision.CHARGE,
                    chargeUsd = 1_000_000.0,
                    reason = "This value exceeds the database numeric boundary.",
                    at = reconciliationTime
                )
            }
            assertFailsWith<IllegalArgumentException> {
                f.usage.reconcileExternalSpend(
                    workspaceId = f.workspaceId,
                    reservationId = reservation.id,
                    actorUserId = f.userId,
                    decision = ExternalSpendDecision.CHARGE,
                    chargeUsd = null,
                    reason = "Provider receipt amount must be explicit.",
                    at = reconciliationTime
                )
            }
            val reconciled =
                f.usage.reconcileExternalSpend(
                    workspaceId = f.workspaceId,
                    reservationId = reservation.id,
                    actorUserId = f.userId,
                    decision = ExternalSpendDecision.CHARGE,
                    chargeUsd = 1.75,
                    reason = "Matched the provider billing receipt; token $auditSecret",
                    at = reconciliationTime
                )

            assertEquals(1.75, reconciled.chargedUsd, 1e-9)
            assertEquals(0, f.guard.listPendingExternalSpend(f.workspaceId).size)
            val snapshot = f.guard.snapshot(f.workspaceId, reconciliationTime.plusSeconds(1))
            assertEquals(1.75, snapshot.monthToDateUsd, 1e-9)
            assertEquals(0.0, snapshot.reservedUsd, 1e-9)
            val auditPayloads =
                f.txc.tx {
                    AuditEventsTable.select {
                        (AuditEventsTable.workspaceId eq f.workspaceId) and
                            (AuditEventsTable.resourceId eq reservation.id)
                    }.map { it[AuditEventsTable.payload] }
                }
            assertEquals(1, auditPayloads.size)
            assertFalse(auditSecret in auditPayloads.single())
            assertTrue("[REDACTED:openai_api_key]" in auditPayloads.single())
        }
    }

    @Test
    fun durableCheckpointIsResumableButNeverOperatorReconcilable() = runBlocking {
        fixture(cap = 10.0).use { f ->
            val reservation =
                f.guard.reserve(
                    workspaceId = f.workspaceId,
                    reservationKey = "checkpoint-not-reconcilable",
                    estimate = CostEstimate(1.0, 3.0),
                    now = now,
                    requestHash = "sha256:${"e".repeat(64)}",
                    externalOperation = "transcription",
                    externalProvider = "openai",
                    externalModel = "whisper-1",
                    externalStage = "transcription"
                )
            val token =
                f.guard.markExternalSpendStarted(
                    f.workspaceId,
                    reservation.id,
                    now.plusSeconds(1)
                )
            f.guard.saveExternalCheckpoint(
                workspaceId = f.workspaceId,
                reservationId = reservation.id,
                token = token,
                checkpointJson = """{"requestHash":"sha256:${"e".repeat(64)}"}""",
                now = now.plusSeconds(2)
            )

            val afterLease = now.plus(11, ChronoUnit.MINUTES)
            assertEquals(
                0,
                f.guard.listPendingExternalSpend(f.workspaceId, now = afterLease).size
            )
            assertFailsWith<TenantIntegrityException> {
                f.usage.reconcileExternalSpend(
                    workspaceId = f.workspaceId,
                    reservationId = reservation.id,
                    actorUserId = f.userId,
                    decision = ExternalSpendDecision.RELEASE,
                    chargeUsd = null,
                    reason = "Checkpoint must be completed by its original request.",
                    at = afterLease
                )
            }
            assertTrue(
                f.guard
                    .reserve(
                        workspaceId = f.workspaceId,
                        reservationKey = "checkpoint-not-reconcilable",
                        estimate = CostEstimate(1.0, 3.0),
                        now = afterLease,
                        requestHash = "sha256:${"e".repeat(64)}",
                        externalOperation = "transcription",
                        externalProvider = "openai",
                        externalModel = "whisper-1",
                        externalStage = "transcription"
                    ).externalCheckpointJson != null
            )
        }
    }

    @Test
    fun databaseRejectsUnauditedReleaseOfFencedExternalSpend() = runBlocking {
        fixture(cap = 10.0).use { f ->
            val reservation =
                f.guard.reserve(
                    workspaceId = f.workspaceId,
                    reservationKey = "constraint-release",
                    estimate = CostEstimate(1.0, 3.0),
                    now = now,
                    requestHash = "sha256:${"e".repeat(64)}",
                    externalOperation = "transcription",
                    externalProvider = "openai",
                    externalModel = "whisper-1",
                    externalStage = "transcription"
                )
            f.guard.markExternalSpendStarted(f.workspaceId, reservation.id, now.plusSeconds(1))

            assertFails {
                f.txc.tx {
                    CostReservationsTable.update({
                        (CostReservationsTable.workspaceId eq f.workspaceId) and
                            (CostReservationsTable.id eq reservation.id)
                    }) {
                        it[releasedAt] = now.plusSeconds(2)
                    }
                }
            }
            assertEquals(3.0, f.guard.snapshot(f.workspaceId, now.plusSeconds(3)).reservedUsd)
        }
    }

    @Test
    fun parallelAdmissionsCannotOverbookWorkspaceCap() = runBlocking {
        fixture(cap = 5.0).use { f ->
            val outcomes =
                listOf("parallel-a", "parallel-b").map { key ->
                    async(Dispatchers.Default) {
                        runCatching {
                            f.guard.reserve(
                                f.workspaceId,
                                key,
                                CostEstimate(lowUsd = 1.0, highUsd = 3.0),
                                now
                            )
                        }
                    }
                }.awaitAll()

            assertEquals(1, outcomes.count { it.isSuccess })
            assertEquals(1, outcomes.count { it.isFailure })
            assertIs<QuotaExceededException>(outcomes.single { it.isFailure }.exceptionOrNull())
            assertEquals(3.0, f.guard.snapshot(f.workspaceId, now).reservedUsd, 1e-9)
        }
    }

    @Test
    fun disabledCapStillTracksReservation() = runBlocking {
        fixture(cap = 0.0).use { f ->
            val result =
                f.guard.reserve(
                    f.workspaceId,
                    "unlimited",
                    CostEstimate(lowUsd = 100.0, highUsd = 500.0),
                    now
                )
            assertEquals(null, result.check.capUsd)
            assertEquals(500.0, result.check.reservedUsd, 1e-9)
        }
    }

    @Test
    fun capAndTranscriptionDefaultsParseFromEnvironment() {
        val defaults = CostConfig.fromEnv(EnvConfig.of(emptyMap()))
        assertEquals(CostConfig.DEFAULT_MONTHLY_CAP_USD, defaults.monthlyCapUsd, 1e-9)
        assertEquals(
            CostConfig.DEFAULT_TRANSCRIPTION_USD_PER_MINUTE,
            defaults.transcriptionUsdPerMinute,
            1e-9
        )
        val configured =
            CostConfig.fromEnv(
                EnvConfig.of(
                    mapOf(
                        "POTATY_WORKSPACE_MONTHLY_COST_CAP_USD" to "125.5",
                        "POTATY_TRANSCRIPTION_USD_PER_MINUTE" to "0.01",
                        "POTATY_TRANSCRIPTION_RESERVATION_BITRATE_BPS" to "8000"
                    )
                )
            )
        assertEquals(125.5, configured.monthlyCapUsd, 1e-9)
        assertEquals(0.01, configured.transcriptionUsdPerMinute, 1e-9)
        assertEquals(8000, configured.transcriptionReservationBitrateBps)

        val nonFinite =
            CostConfig.fromEnv(
                EnvConfig.of(
                    mapOf(
                        "POTATY_WORKSPACE_MONTHLY_COST_CAP_USD" to "NaN",
                        "POTATY_TRANSCRIPTION_USD_PER_MINUTE" to "Infinity"
                    )
                )
            )
        assertEquals(CostConfig.DEFAULT_MONTHLY_CAP_USD, nonFinite.monthlyCapUsd, 1e-9)
        assertEquals(
            CostConfig.DEFAULT_TRANSCRIPTION_USD_PER_MINUTE,
            nonFinite.transcriptionUsdPerMinute,
            1e-9
        )
    }

    private suspend fun fixture(cap: Double = 10.0): Fixture {
        val config = testConfig()
        val database = Database.connect(config.database)
        val txc = TransactionContext(database.exposed)
        bootstrapDevelopmentIdentity(txc, config.auth)
        return Fixture(
            database = database,
            txc = txc,
            usage = UsageRecorder(txc),
            jobs = JobRepository(txc),
            guard = QuotaGuard(txc, CostConfig(monthlyCapUsd = cap)),
            workspaceId = UUID.fromString(config.auth.devWorkspaceId),
            userId = UUID.fromString(config.auth.devUserId)
        )
    }

    private data class Fixture(
        val database: Database,
        val txc: TransactionContext,
        val usage: UsageRecorder,
        val jobs: JobRepository,
        val guard: QuotaGuard,
        val workspaceId: UUID,
        val userId: UUID
    ) : AutoCloseable {
        override fun close() = database.close()
    }
}
