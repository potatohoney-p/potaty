/*
 * Copyright (c) 2026, Potaty
 *
 * Atomic per-workspace cost quota admission. A reservation is created while holding a row lock on
 * the owning workspace, making the read + decision + write one serializable critical section even
 * when several backend instances receive requests concurrently.
 */

package com.potaty.backend.cost

import com.potaty.backend.jobs.JobStatus
import com.potaty.backend.jobs.isTerminal
import com.potaty.backend.persistence.CostReservationsTable
import com.potaty.backend.persistence.JobsTable
import com.potaty.backend.persistence.TenantIntegrityException
import com.potaty.backend.persistence.TransactionContext
import com.potaty.backend.persistence.UsageEventsTable
import com.potaty.backend.persistence.WorkspacesTable
import com.potaty.backend.persistence.jsonDocumentsEqual
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNotNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update

data class QuotaReservation(
    val id: UUID,
    val workspaceId: UUID,
    val reservationKey: String,
    val amountUsd: Double,
    val check: QuotaCheck,
    /** True only when this call created/reactivated the row and therefore owns rollback cleanup. */
    val acquired: Boolean,
    val requestHash: String? = null,
    val externalSpendStartedAt: Instant? = null,
    val externalCheckpointJson: String? = null,
    val processingLeaseExpiresAt: Instant? = null,
    val externalResultJson: String? = null,
    val releasedAt: Instant? = null,
    val reconciliationDecision: String? = null
)

data class PendingExternalSpendRecord(
    val id: UUID,
    val workspaceId: UUID,
    val amountUsd: Double,
    val operation: String,
    val provider: String,
    val model: String,
    val stage: String,
    val metadataJson: String?,
    val startedAt: Instant,
    val reconcilableAt: Instant
)

object ExternalSpendPolicy {
    /** Longer than the 180-second provider timeout, with room for persistence and clock skew. */
    val PROCESSING_LEASE: Duration = Duration.ofMinutes(10)
}

class QuotaGuard(
    private val txc: TransactionContext,
    private val config: CostConfig
) {

    /**
     * Atomically admits and reserves [estimate.highUsd]. Reusing [reservationKey] is idempotent,
     * which mirrors the job API's workspace-scoped idempotency key.
     */
    suspend fun reserve(
        workspaceId: UUID,
        reservationKey: String,
        estimate: CostEstimate,
        now: Instant = Instant.now(),
        requestHash: String? = null,
        externalOperation: String? = null,
        externalProvider: String? = null,
        externalModel: String? = null,
        externalStage: String? = null,
        externalMetadataJson: String? = null
    ): QuotaReservation = txc.tx {
        require(reservationKey.isNotBlank()) { "reservationKey must not be blank" }
        require(estimate.highUsd >= 0.0) { "reservation amount must not be negative" }
        validateExternalReservation(
            requestHash,
            externalOperation,
            externalProvider,
            externalModel,
            externalStage,
            externalMetadataJson
        )

        // This stable parent-row lock serializes different reservation keys for one tenant.
        val workspace =
            WorkspacesTable
                .slice(WorkspacesTable.id)
                .select { WorkspacesTable.id eq workspaceId }
                .forUpdate()
                .limit(1)
                .singleOrNull()
                ?: throw TenantIntegrityException("Workspace not found")

        // Read the value to ensure the SELECT ... FOR UPDATE is materialized before sums/inserts.
        check(workspace[WorkspacesTable.id] == workspaceId)

        val existing =
            CostReservationsTable
                .select {
                    (CostReservationsTable.workspaceId eq workspaceId) and
                        (CostReservationsTable.reservationKey eq reservationKey)
                }
                .limit(1)
                .singleOrNull()
        if (existing != null) {
            val descriptorMismatch =
                existing[CostReservationsTable.externalOperation] != externalOperation ||
                    existing[CostReservationsTable.externalProvider] != externalProvider ||
                    existing[CostReservationsTable.externalModel] != externalModel ||
                    existing[CostReservationsTable.externalStage] != externalStage ||
                    !jsonDocumentsEqual(
                        existing[CostReservationsTable.externalMetadata],
                        externalMetadataJson
                    )
            if (
                existing[CostReservationsTable.requestHash] != requestHash ||
                descriptorMismatch
            ) {
                throw CostReservationConflictException(
                    "Idempotency-Key was already used for a different external request"
                )
            }
            val canReactivate =
                existing[CostReservationsTable.jobId] == null &&
                    existing[CostReservationsTable.externalSpendStartedAt] == null &&
                    if (requestHash == null) {
                        existing[CostReservationsTable.releasedAt] != null ||
                            existing[CostReservationsTable.expiresAt] <= now
                    } else {
                        existing[CostReservationsTable.releasedAt] == null &&
                            existing[CostReservationsTable.expiresAt] <= now
                    }
            if (canReactivate) {
                val before = quotaSnapshot(workspaceId, now)
                val admitted = admit(workspaceId, before, estimate.highUsd)
                CostReservationsTable.update({
                    (CostReservationsTable.id eq existing[CostReservationsTable.id]) and
                        (CostReservationsTable.workspaceId eq workspaceId)
                }) {
                    it[amountUsd] = estimate.highUsd
                    it[createdAt] = now
                    it[expiresAt] = now.plus(RESERVATION_TTL)
                    it[CostReservationsTable.requestHash] = requestHash
                    it[CostReservationsTable.externalOperation] = externalOperation
                    it[CostReservationsTable.externalProvider] = externalProvider
                    it[CostReservationsTable.externalModel] = externalModel
                    it[CostReservationsTable.externalStage] = externalStage
                    it[CostReservationsTable.externalMetadata] = externalMetadataJson
                    it[externalSpendStartedAt] = null
                    it[externalCheckpoint] = null
                    it[externalCheckpointedAt] = null
                    it[processingToken] = null
                    it[processingLeaseExpiresAt] = null
                    it[externalResult] = null
                    it[reconciliationDecision] = null
                    it[reconciledAt] = null
                    it[reconciledBy] = null
                    it[releasedAt] = null
                }
                return@tx QuotaReservation(
                    id = existing[CostReservationsTable.id],
                    workspaceId = workspaceId,
                    reservationKey = reservationKey,
                    amountUsd = estimate.highUsd,
                    check = admitted,
                    acquired = true,
                    requestHash = requestHash
                )
            }
            val snapshot = quotaSnapshot(workspaceId, now)
            return@tx QuotaReservation(
                id = existing[CostReservationsTable.id],
                workspaceId = workspaceId,
                reservationKey = reservationKey,
                amountUsd = existing[CostReservationsTable.amountUsd],
                check = snapshot,
                acquired = false,
                requestHash = existing[CostReservationsTable.requestHash],
                externalSpendStartedAt =
                existing[CostReservationsTable.externalSpendStartedAt],
                externalCheckpointJson = existing[CostReservationsTable.externalCheckpoint],
                processingLeaseExpiresAt =
                existing[CostReservationsTable.processingLeaseExpiresAt],
                externalResultJson = existing[CostReservationsTable.externalResult],
                releasedAt = existing[CostReservationsTable.releasedAt],
                reconciliationDecision =
                existing[CostReservationsTable.reconciliationDecision]
            )
        }

        val before = quotaSnapshot(workspaceId, now)
        val admitted = admit(workspaceId, before, estimate.highUsd)

        val id = UUID.randomUUID()
        CostReservationsTable.insert {
            it[CostReservationsTable.id] = id
            it[CostReservationsTable.workspaceId] = workspaceId
            it[CostReservationsTable.reservationKey] = reservationKey
            it[CostReservationsTable.jobId] = null
            it[amountUsd] = estimate.highUsd
            it[createdAt] = now
            it[expiresAt] = now.plus(RESERVATION_TTL)
            it[CostReservationsTable.requestHash] = requestHash
            it[CostReservationsTable.externalOperation] = externalOperation
            it[CostReservationsTable.externalProvider] = externalProvider
            it[CostReservationsTable.externalModel] = externalModel
            it[CostReservationsTable.externalStage] = externalStage
            it[CostReservationsTable.externalMetadata] = externalMetadataJson
            it[externalSpendStartedAt] = null
            it[externalCheckpoint] = null
            it[externalCheckpointedAt] = null
            it[processingToken] = null
            it[processingLeaseExpiresAt] = null
            it[externalResult] = null
            it[reconciliationDecision] = null
            it[reconciledAt] = null
            it[reconciledBy] = null
            it[releasedAt] = null
        }
        QuotaReservation(
            id = id,
            workspaceId = workspaceId,
            reservationKey = reservationKey,
            amountUsd = estimate.highUsd,
            check = admitted,
            acquired = true,
            requestHash = requestHash
        )
    }

    /**
     * Fences an unattached reservation immediately before a potentially billable external call.
     * A fenced reservation ignores ordinary expiry and therefore cannot disappear from quota
     * accounting if the provider succeeds but usage persistence later fails. Settlement must write
     * the usage event and release this row in one database transaction.
     */
    suspend fun markExternalSpendStarted(
        workspaceId: UUID,
        reservationId: UUID,
        now: Instant = Instant.now()
    ): UUID = txc.tx {
        val token = UUID.randomUUID()
        val updated =
            CostReservationsTable.update({
                (CostReservationsTable.workspaceId eq workspaceId) and
                    (CostReservationsTable.id eq reservationId) and
                    CostReservationsTable.jobId.isNull() and
                    CostReservationsTable.requestHash.isNotNull() and
                    CostReservationsTable.externalSpendStartedAt.isNull() and
                    CostReservationsTable.releasedAt.isNull()
            }) {
                it[externalSpendStartedAt] = now
                it[processingToken] = token
                it[processingLeaseExpiresAt] = now.plus(ExternalSpendPolicy.PROCESSING_LEASE)
            }
        if (updated != 1) {
            throw CostReservationStateConflictException(
                "External spend attempt is already running, settled, or reconciled"
            )
        }
        token
    }

    /** Stores a bounded, already-redacted completion checkpoint under the active fence token. */
    suspend fun saveExternalCheckpoint(
        workspaceId: UUID,
        reservationId: UUID,
        token: UUID,
        checkpointJson: String,
        now: Instant = Instant.now()
    ) = txc.tx {
        require(checkpointJson.length <= MAX_EXTERNAL_CHECKPOINT_CHARS) {
            "external checkpoint is too large"
        }
        require(runCatching { Json.parseToJsonElement(checkpointJson) }.isSuccess) {
            "external checkpoint must be valid JSON"
        }
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
        val existing = reservation[CostReservationsTable.externalCheckpoint]
        if (existing != null) {
            if (
                jsonDocumentsEqual(existing, checkpointJson) &&
                reservation[CostReservationsTable.processingToken] == token &&
                reservation[CostReservationsTable.releasedAt] == null
            ) {
                return@tx
            }
            throw CostReservationStateConflictException(
                "External checkpoint is already owned by another attempt"
            )
        }
        val updated =
            CostReservationsTable.update({
                (CostReservationsTable.workspaceId eq workspaceId) and
                    (CostReservationsTable.id eq reservationId) and
                    (CostReservationsTable.processingToken eq token) and
                    CostReservationsTable.externalSpendStartedAt.isNotNull() and
                    CostReservationsTable.releasedAt.isNull() and
                    CostReservationsTable.externalCheckpoint.isNull()
            }) {
                it[externalCheckpoint] = checkpointJson
                it[externalCheckpointedAt] = now
            }
        if (updated != 1) {
            throw CostReservationStateConflictException(
                "External checkpoint fence is no longer active"
            )
        }
    }

    /** Claims an expired checkpoint lease so a retry can finish without calling the provider. */
    suspend fun claimExternalCheckpoint(
        workspaceId: UUID,
        reservationId: UUID,
        now: Instant = Instant.now()
    ): UUID = txc.tx {
        val token = UUID.randomUUID()
        val updated =
            CostReservationsTable.update({
                (CostReservationsTable.workspaceId eq workspaceId) and
                    (CostReservationsTable.id eq reservationId) and
                    CostReservationsTable.externalCheckpoint.isNotNull() and
                    CostReservationsTable.releasedAt.isNull() and
                    (CostReservationsTable.processingLeaseExpiresAt lessEq now)
            }) {
                it[processingToken] = token
                it[processingLeaseExpiresAt] = now.plus(ExternalSpendPolicy.PROCESSING_LEASE)
            }
        if (updated != 1) {
            throw CostReservationStateConflictException(
                "External checkpoint is still processing or was already settled"
            )
        }
        token
    }

    /** Oldest-first tenant-scoped queue containing only expired processing leases. */
    suspend fun listPendingExternalSpend(
        workspaceId: UUID,
        limit: Int = 100,
        now: Instant = Instant.now()
    ): List<PendingExternalSpendRecord> = txc.tx {
        require(limit in 1..500) { "limit must be between 1 and 500" }
        CostReservationsTable
            .select {
                (CostReservationsTable.workspaceId eq workspaceId) and
                    CostReservationsTable.externalSpendStartedAt.isNotNull() and
                    CostReservationsTable.externalCheckpoint.isNull() and
                    CostReservationsTable.releasedAt.isNull() and
                    (CostReservationsTable.processingLeaseExpiresAt lessEq now)
            }
            .orderBy(CostReservationsTable.externalSpendStartedAt to SortOrder.ASC)
            .limit(limit)
            .map { row ->
                PendingExternalSpendRecord(
                    id = row[CostReservationsTable.id],
                    workspaceId = row[CostReservationsTable.workspaceId],
                    amountUsd = row[CostReservationsTable.amountUsd],
                    operation = row[CostReservationsTable.externalOperation] ?: "external",
                    provider = row[CostReservationsTable.externalProvider] ?: "unknown",
                    model = row[CostReservationsTable.externalModel] ?: "unknown",
                    stage = row[CostReservationsTable.externalStage] ?: "external",
                    metadataJson = row[CostReservationsTable.externalMetadata],
                    startedAt = requireNotNull(row[CostReservationsTable.externalSpendStartedAt]),
                    reconcilableAt =
                    requireNotNull(row[CostReservationsTable.processingLeaseExpiresAt])
                )
            }
    }

    /** Links a reservation to its queued job; terminal races release it in the same transaction. */
    suspend fun attachToJob(
        workspaceId: UUID,
        reservationId: UUID,
        jobId: UUID,
        now: Instant = Instant.now()
    ) = txc.tx {
        val job =
            JobsTable
                .select { (JobsTable.workspaceId eq workspaceId) and (JobsTable.id eq jobId) }
                .limit(1)
                .singleOrNull()
                ?: throw TenantIntegrityException("Job not found")
        val reservation =
            CostReservationsTable
                .select {
                    (CostReservationsTable.workspaceId eq workspaceId) and
                        (CostReservationsTable.id eq reservationId)
                }
                .limit(1)
                .singleOrNull()
                ?: throw TenantIntegrityException("Cost reservation not found")
        val attachedJobId = reservation[CostReservationsTable.jobId]
        require(reservation[CostReservationsTable.requestHash] == null) {
            "external reservations cannot be attached to queued jobs"
        }
        require(attachedJobId == null || attachedJobId == jobId) {
            "cost reservation is already attached to another job"
        }
        val terminal = JobStatus.fromWire(job[JobsTable.status]).isTerminal
        CostReservationsTable.update({
            (CostReservationsTable.workspaceId eq workspaceId) and
                (CostReservationsTable.id eq reservationId)
        }) {
            it[CostReservationsTable.jobId] = jobId
            if (terminal) it[releasedAt] = now
        }
    }

    suspend fun release(
        workspaceId: UUID,
        reservationId: UUID,
        now: Instant = Instant.now()
    ) = txc.tx {
        CostReservationsTable.update({
            (CostReservationsTable.workspaceId eq workspaceId) and
                (CostReservationsTable.id eq reservationId) and
                CostReservationsTable.externalSpendStartedAt.isNull() and
                CostReservationsTable.releasedAt.isNull()
        }) {
            it[releasedAt] = now
        }
    }

    suspend fun releaseForJob(
        workspaceId: UUID,
        jobId: UUID,
        now: Instant = Instant.now()
    ) = txc.tx {
        CostReservationsTable.update({
            (CostReservationsTable.workspaceId eq workspaceId) and
                (CostReservationsTable.jobId eq jobId) and
                CostReservationsTable.externalSpendStartedAt.isNull() and
                CostReservationsTable.releasedAt.isNull()
        }) {
            it[releasedAt] = now
        }
    }

    suspend fun snapshot(workspaceId: UUID, now: Instant = Instant.now()): QuotaCheck =
        txc.tx { quotaSnapshot(workspaceId, now) }

    private fun quotaSnapshot(workspaceId: UUID, now: Instant): QuotaCheck {
        val monthStart =
            now.atZone(ZoneOffset.UTC)
                .toLocalDate()
                .withDayOfMonth(1)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .truncatedTo(ChronoUnit.SECONDS)
        val spent =
            UsageEventsTable
                .slice(UsageEventsTable.estimatedCostUsd)
                .select {
                    (UsageEventsTable.workspaceId eq workspaceId) and
                        (UsageEventsTable.createdAt greaterEq monthStart)
                }
                .sumOf { it[UsageEventsTable.estimatedCostUsd] }
        val reservationRows =
            CostReservationsTable
                .slice(
                    CostReservationsTable.amountUsd,
                    CostReservationsTable.expiresAt,
                    CostReservationsTable.jobId,
                    CostReservationsTable.externalSpendStartedAt
                )
                .select {
                    (CostReservationsTable.workspaceId eq workspaceId) and
                        CostReservationsTable.releasedAt.isNull()
                }
                .toList()
        val attachedJobIds = reservationRows.mapNotNull { it[CostReservationsTable.jobId] }.toSet()
        val terminalJobIds =
            if (attachedJobIds.isEmpty()) {
                emptySet()
            } else {
                JobsTable
                    .slice(JobsTable.id, JobsTable.status)
                    .select {
                        (JobsTable.workspaceId eq workspaceId) and
                            (JobsTable.id inList attachedJobIds)
                    }
                    .filter { JobStatus.fromWire(it[JobsTable.status]).isTerminal }
                    .mapTo(mutableSetOf()) { it[JobsTable.id] }
            }
        val reserved =
            reservationRows.sumOf { row ->
                val jobId = row[CostReservationsTable.jobId]
                val active =
                    if (jobId == null) {
                        row[CostReservationsTable.externalSpendStartedAt] != null ||
                            row[CostReservationsTable.expiresAt] > now
                    } else {
                        // Attached reservations follow authoritative job state, not a wall-clock
                        // timeout. Missing jobs are counted fail-closed.
                        jobId !in terminalJobIds
                    }
                if (active) row[CostReservationsTable.amountUsd] else 0.0
            }
        return QuotaCheck(
            monthToDateUsd = spent,
            reservedUsd = reserved,
            projectedUsd = spent + reserved,
            capUsd = if (config.capEnabled) config.monthlyCapUsd else null
        )
    }

    private fun admit(
        workspaceId: UUID,
        before: QuotaCheck,
        amountUsd: Double
    ): QuotaCheck {
        val projected = before.monthToDateUsd + before.reservedUsd + amountUsd
        if (config.capEnabled && projected > config.monthlyCapUsd) {
            throw QuotaExceededException(
                workspaceId = workspaceId,
                monthToDateUsd = before.monthToDateUsd,
                reservedUsd = before.reservedUsd,
                estimateHighUsd = amountUsd,
                capUsd = config.monthlyCapUsd
            )
        }
        return QuotaCheck(
            monthToDateUsd = before.monthToDateUsd,
            reservedUsd = before.reservedUsd + amountUsd,
            projectedUsd = projected,
            capUsd = if (config.capEnabled) config.monthlyCapUsd else null
        )
    }

    @Suppress("LongParameterList")
    private fun validateExternalReservation(
        requestHash: String?,
        operation: String?,
        provider: String?,
        model: String?,
        stage: String?,
        metadataJson: String?
    ) {
        val descriptors = listOf(operation, provider, model, stage)
        if (requestHash == null) {
            require(descriptors.all { it == null } && metadataJson == null) {
                "external reservation metadata requires a request hash"
            }
            return
        }
        require(requestHash.matches(REQUEST_HASH_PATTERN)) { "requestHash must be sha256" }
        require(descriptors.all { !it.isNullOrBlank() && it.length <= 200 }) {
            "external reservation descriptors must contain 1-200 characters"
        }
        require((metadataJson?.length ?: 0) <= MAX_EXTERNAL_METADATA_CHARS) {
            "external reservation metadata is too large"
        }
        require(
            metadataJson == null ||
                runCatching { Json.parseToJsonElement(metadataJson) }.isSuccess
        ) {
            "external reservation metadata must be valid JSON"
        }
    }

    private companion object {
        val RESERVATION_TTL: Duration = Duration.ofHours(24)
        val REQUEST_HASH_PATTERN = Regex("^sha256:[0-9a-f]{64}$")
        const val MAX_EXTERNAL_METADATA_CHARS = 4_096
        const val MAX_EXTERNAL_CHECKPOINT_CHARS = 4 * 1024 * 1024
    }
}

data class QuotaCheck(
    val monthToDateUsd: Double,
    val reservedUsd: Double,
    val projectedUsd: Double,
    /** The active cap, or null when no cap is enforced. */
    val capUsd: Double?
)

class QuotaExceededException(
    val workspaceId: UUID,
    val monthToDateUsd: Double,
    val reservedUsd: Double,
    val estimateHighUsd: Double,
    val capUsd: Double
) : RuntimeException(
    "Workspace monthly cost cap exceeded: " +
        "month-to-date \$${"%.4f".format(monthToDateUsd)} + " +
        "reserved \$${"%.4f".format(reservedUsd)} + " +
        "estimate \$${"%.4f".format(estimateHighUsd)} would exceed " +
        "cap \$${"%.4f".format(capUsd)}"
)

class CostReservationConflictException(message: String) : RuntimeException(message)

class CostReservationStateConflictException(message: String) : RuntimeException(message)
