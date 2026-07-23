/*
 * Copyright (c) 2026, Potaty
 *
 * Tenant-scoped persistence for cost/usage events (WS6; plan 3.4 cost tracking, 21.1).
 * EVERY method takes workspaceId and filters by it (plan 20.5) — there is no cross-workspace
 * read path. Records one row per LLM call (or estimate) and sums a workspace's month-to-date
 * spend for the quota guard and cost dashboard.
 */

package com.potaty.backend.usage

import com.potaty.backend.cost.CostReservationStateConflictException
import com.potaty.backend.persistence.AuditEventsTable
import com.potaty.backend.persistence.CostReservationsTable
import com.potaty.backend.persistence.TenantIntegrityException
import com.potaty.backend.persistence.TransactionContext
import com.potaty.backend.persistence.UsageEventsTable
import com.potaty.backend.persistence.WorkspaceMembersTable
import com.potaty.backend.security.RedactionFinding
import com.potaty.backend.security.Redactor
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update

data class UsageEventRecord(
    val id: UUID,
    val workspaceId: UUID,
    val jobId: UUID?,
    val provider: String,
    val model: String,
    val stage: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val cachedInputTokens: Int,
    val estimatedCostUsd: Double
)

enum class ExternalSpendDecision {
    CHARGE,
    RELEASE
}

/** Largest value representable by PostgreSQL's authoritative numeric(12,6) usage column. */
const val MAX_RECONCILIATION_CHARGE_USD = 999_999.999_999

data class ExternalSpendReconciliationRecord(
    val reservationId: UUID,
    val workspaceId: UUID,
    val decision: ExternalSpendDecision,
    val chargedUsd: Double,
    val reconciledAt: Instant,
    val reconciledBy: UUID
)

class UsageRecorder(
    private val txc: TransactionContext,
    private val eventIdFactory: () -> UUID = UUID::randomUUID
) {

    /** Inserts a usage event for [workspaceId] and returns it with its generated id. */
    suspend fun record(
        workspaceId: UUID,
        jobId: UUID?,
        provider: String,
        model: String,
        stage: String,
        inputTokens: Int,
        outputTokens: Int,
        estimatedCostUsd: Double,
        cachedInputTokens: Int = 0,
        at: Instant = Instant.now()
    ): UsageEventRecord = txc.tx {
        validateUsage(inputTokens, outputTokens, cachedInputTokens, estimatedCostUsd)
        val newId = eventIdFactory()
        UsageEventsTable.insert {
            it[id] = newId
            it[UsageEventsTable.workspaceId] = workspaceId
            it[UsageEventsTable.jobId] = jobId
            it[UsageEventsTable.provider] = provider
            it[UsageEventsTable.model] = model
            it[UsageEventsTable.stage] = stage
            it[UsageEventsTable.inputTokens] = inputTokens
            it[UsageEventsTable.outputTokens] = outputTokens
            it[UsageEventsTable.cachedInputTokens] = cachedInputTokens
            it[UsageEventsTable.estimatedCostUsd] = estimatedCostUsd
            it[createdAt] = at
        }
        UsageEventRecord(
            id = newId,
            workspaceId = workspaceId,
            jobId = jobId,
            provider = provider,
            model = model,
            stage = stage,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            cachedInputTokens = cachedInputTokens,
            estimatedCostUsd = estimatedCostUsd
        )
    }

    /**
     * Atomically converts a provider-fenced reservation into durable usage. If either the usage
     * insert or reservation release fails, the whole transaction rolls back and the previously
     * fenced reservation remains active beyond its normal expiry, conservatively preserving quota.
     */
    @Suppress("LongParameterList")
    suspend fun recordAndSettleReservation(
        workspaceId: UUID,
        reservationId: UUID,
        processingToken: UUID,
        provider: String,
        model: String,
        stage: String,
        inputTokens: Int,
        outputTokens: Int,
        estimatedCostUsd: Double,
        externalResultJson: String,
        cachedInputTokens: Int = 0,
        at: Instant = Instant.now()
    ): UsageEventRecord = txc.tx {
        recordAndSettleReservationInCurrentTransaction(
            workspaceId = workspaceId,
            reservationId = reservationId,
            processingToken = processingToken,
            provider = provider,
            model = model,
            stage = stage,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            estimatedCostUsd = estimatedCostUsd,
            externalResultJson = externalResultJson,
            cachedInputTokens = cachedInputTokens,
            at = at
        )
    }

    /** Same operation for coordinators that already own the surrounding database transaction. */
    @Suppress("LongParameterList")
    internal fun recordAndSettleReservationInCurrentTransaction(
        workspaceId: UUID,
        reservationId: UUID,
        processingToken: UUID,
        provider: String,
        model: String,
        stage: String,
        inputTokens: Int,
        outputTokens: Int,
        estimatedCostUsd: Double,
        externalResultJson: String,
        cachedInputTokens: Int = 0,
        at: Instant
    ): UsageEventRecord {
        validateUsage(inputTokens, outputTokens, cachedInputTokens, estimatedCostUsd)
        validateExternalResult(externalResultJson)
        val reservation =
            CostReservationsTable
                .select {
                    (CostReservationsTable.workspaceId eq workspaceId) and
                        (CostReservationsTable.id eq reservationId)
                }
                .forUpdate()
                .limit(1)
                .singleOrNull()
                ?: throw TenantIntegrityException("Cost reservation not found")
        if (
            reservation[CostReservationsTable.releasedAt] != null ||
            reservation[CostReservationsTable.externalSpendStartedAt] == null ||
            reservation[CostReservationsTable.jobId] != null ||
            reservation[CostReservationsTable.processingToken] != processingToken ||
            reservation[CostReservationsTable.externalProvider] != provider ||
            reservation[CostReservationsTable.externalModel] != model ||
            reservation[CostReservationsTable.externalStage] != stage
        ) {
            throw TenantIntegrityException("Cost reservation is not pending external settlement")
        }

        val newId = eventIdFactory()
        UsageEventsTable.insert {
            it[id] = newId
            it[UsageEventsTable.workspaceId] = workspaceId
            it[UsageEventsTable.jobId] = null
            it[UsageEventsTable.provider] = provider
            it[UsageEventsTable.model] = model
            it[UsageEventsTable.stage] = stage
            it[UsageEventsTable.inputTokens] = inputTokens
            it[UsageEventsTable.outputTokens] = outputTokens
            it[UsageEventsTable.cachedInputTokens] = cachedInputTokens
            it[UsageEventsTable.estimatedCostUsd] = estimatedCostUsd
            it[createdAt] = at
        }
        val released =
            CostReservationsTable.update({
                (CostReservationsTable.workspaceId eq workspaceId) and
                    (CostReservationsTable.id eq reservationId) and
                    (CostReservationsTable.processingToken eq processingToken) and
                    CostReservationsTable.releasedAt.isNull()
            }) {
                it[releasedAt] = at
                it[externalResult] = externalResultJson
                it[externalCheckpoint] = null
                it[externalCheckpointedAt] = null
                it[CostReservationsTable.processingToken] = null
                it[processingLeaseExpiresAt] = null
            }
        if (released != 1) {
            throw TenantIntegrityException("Cost reservation settlement lost ownership")
        }

        return UsageEventRecord(
            id = newId,
            workspaceId = workspaceId,
            jobId = null,
            provider = provider,
            model = model,
            stage = stage,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            cachedInputTokens = cachedInputTokens,
            estimatedCostUsd = estimatedCostUsd
        )
    }

    /**
     * Owner-only call site support for an uncertain external attempt. The decision, optional usage
     * event, reservation release, and immutable audit row commit together under tenant predicates.
     */
    suspend fun reconcileExternalSpend(
        workspaceId: UUID,
        reservationId: UUID,
        actorUserId: UUID,
        decision: ExternalSpendDecision,
        chargeUsd: Double?,
        reason: String,
        at: Instant = Instant.now()
    ): ExternalSpendReconciliationRecord = txc.tx {
        val safeReason = reason.trim()
        require(safeReason.length in MIN_REASON_CHARS..MAX_REASON_CHARS) {
            "reconciliation reason must contain $MIN_REASON_CHARS-$MAX_REASON_CHARS characters"
        }
        require(
            safeReason.none(Char::isISOControl) &&
                !BIDI_CONTROL_PATTERN.containsMatchIn(safeReason)
        ) {
            "reconciliation reason contains unsafe controls"
        }
        val persistedReason =
            Redactor.redact(
                safeReason,
                setOf(RedactionFinding.Category.SECRET)
            ).redactedText
        val member =
            WorkspaceMembersTable
                .select {
                    (WorkspaceMembersTable.workspaceId eq workspaceId) and
                        (WorkspaceMembersTable.userId eq actorUserId) and
                        (WorkspaceMembersTable.role eq "owner")
                }
                .forUpdate()
                .limit(1)
                .singleOrNull()
                ?: throw TenantIntegrityException("Workspace owner not found")
        check(member[WorkspaceMembersTable.userId] == actorUserId)

        val reservation =
            CostReservationsTable
                .select {
                    (CostReservationsTable.workspaceId eq workspaceId) and
                        (CostReservationsTable.id eq reservationId)
                }
                .forUpdate()
                .limit(1)
                .singleOrNull()
                ?: throw TenantIntegrityException("Pending external spend not found")
        if (
            reservation[CostReservationsTable.releasedAt] != null ||
            reservation[CostReservationsTable.externalSpendStartedAt] == null ||
            reservation[CostReservationsTable.externalCheckpoint] != null ||
            reservation[CostReservationsTable.jobId] != null ||
            reservation[CostReservationsTable.processingLeaseExpiresAt] == null
        ) {
            throw TenantIntegrityException("Pending external spend not found")
        }
        if (requireNotNull(reservation[CostReservationsTable.processingLeaseExpiresAt]) > at) {
            throw CostReservationStateConflictException(
                "External spend is still inside its active processing lease"
            )
        }

        val chargedUsd =
            when (decision) {
                ExternalSpendDecision.CHARGE ->
                    requireNotNull(chargeUsd) {
                        "charge decisions require an explicit provider-receipt amount"
                    }
                ExternalSpendDecision.RELEASE -> {
                    require(chargeUsd == null) { "release decisions cannot include chargeUsd" }
                    0.0
                }
            }
        require(chargedUsd.isFinite() && chargedUsd in 0.0..MAX_RECONCILIATION_CHARGE_USD) {
            "chargeUsd must be finite and between 0 and $MAX_RECONCILIATION_CHARGE_USD"
        }

        if (decision == ExternalSpendDecision.CHARGE) {
            UsageEventsTable.insert {
                it[UsageEventsTable.id] = eventIdFactory()
                it[UsageEventsTable.workspaceId] = workspaceId
                it[UsageEventsTable.jobId] = null
                it[UsageEventsTable.provider] =
                    reservation[CostReservationsTable.externalProvider] ?: "unknown"
                it[UsageEventsTable.model] =
                    reservation[CostReservationsTable.externalModel] ?: "unknown"
                it[UsageEventsTable.stage] =
                    reservation[CostReservationsTable.externalStage] ?: "external"
                it[UsageEventsTable.inputTokens] = 0
                it[UsageEventsTable.outputTokens] = 0
                it[UsageEventsTable.cachedInputTokens] = 0
                it[UsageEventsTable.estimatedCostUsd] = chargedUsd
                it[UsageEventsTable.createdAt] = at
            }
        }

        val released =
            CostReservationsTable.update({
                (CostReservationsTable.workspaceId eq workspaceId) and
                    (CostReservationsTable.id eq reservationId) and
                    CostReservationsTable.externalCheckpoint.isNull() and
                    CostReservationsTable.releasedAt.isNull()
            }) {
                it[releasedAt] = at
                it[reconciliationDecision] = decision.name.lowercase()
                it[reconciledAt] = at
                it[reconciledBy] = actorUserId
                it[externalCheckpoint] = null
                it[externalCheckpointedAt] = null
                it[processingToken] = null
                it[processingLeaseExpiresAt] = null
            }
        if (released != 1) {
            throw TenantIntegrityException("External spend reconciliation lost ownership")
        }

        AuditEventsTable.insert {
            it[AuditEventsTable.id] = UUID.randomUUID()
            it[AuditEventsTable.workspaceId] = workspaceId
            it[AuditEventsTable.actorUserId] = actorUserId
            it[AuditEventsTable.eventType] = "external_spend_reconciled"
            it[AuditEventsTable.resourceType] = "cost_reservation"
            it[AuditEventsTable.resourceId] = reservationId
            it[AuditEventsTable.ip] = null
            it[AuditEventsTable.userAgent] = null
            it[AuditEventsTable.payload] =
                buildJsonObject {
                    put("decision", decision.name.lowercase())
                    put("chargedUsd", chargedUsd)
                    put("reason", persistedReason)
                    put(
                        "operation",
                        reservation[CostReservationsTable.externalOperation] ?: "external"
                    )
                    put(
                        "provider",
                        reservation[CostReservationsTable.externalProvider] ?: "unknown"
                    )
                }.toString()
            it[AuditEventsTable.createdAt] = at
        }

        ExternalSpendReconciliationRecord(
            reservationId = reservationId,
            workspaceId = workspaceId,
            decision = decision,
            chargedUsd = chargedUsd,
            reconciledAt = at,
            reconciledBy = actorUserId
        )
    }

    /**
     * Sum of [UsageEventsTable.estimatedCostUsd] for [workspaceId] since the first instant of the
     * UTC calendar month containing [now]. Tenant-scoped: only this workspace's rows are summed.
     */
    suspend fun sumCostThisMonth(workspaceId: UUID, now: Instant = Instant.now()): Double = txc.tx {
        val monthStart = startOfUtcMonth(now)
        UsageEventsTable
            .slice(UsageEventsTable.estimatedCostUsd)
            .select {
                (UsageEventsTable.workspaceId eq workspaceId) and
                    (UsageEventsTable.createdAt greaterEq monthStart)
            }
            .sumOf { it[UsageEventsTable.estimatedCostUsd] }
    }

    private fun startOfUtcMonth(now: Instant): Instant =
        now.atZone(ZoneOffset.UTC)
            .toLocalDate()
            .withDayOfMonth(1)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .truncatedTo(ChronoUnit.SECONDS)

    private fun validateUsage(
        inputTokens: Int,
        outputTokens: Int,
        cachedInputTokens: Int,
        estimatedCostUsd: Double
    ) {
        require(inputTokens >= 0) { "inputTokens must not be negative" }
        require(outputTokens >= 0) { "outputTokens must not be negative" }
        require(cachedInputTokens >= 0) { "cachedInputTokens must not be negative" }
        require(estimatedCostUsd.isFinite() && estimatedCostUsd >= 0.0) {
            "estimatedCostUsd must be finite and non-negative"
        }
    }

    private fun validateExternalResult(value: String) {
        require(value.length <= MAX_EXTERNAL_RESULT_CHARS) { "external result is too large" }
        require(runCatching { Json.parseToJsonElement(value) }.isSuccess) {
            "external result must be valid JSON"
        }
    }

    private companion object {
        const val MAX_EXTERNAL_RESULT_CHARS = 16_384
        const val MIN_REASON_CHARS = 10
        const val MAX_REASON_CHARS = 500
        val BIDI_CONTROL_PATTERN = Regex("[\\u202A-\\u202E\\u2066-\\u2069]")
    }
}
