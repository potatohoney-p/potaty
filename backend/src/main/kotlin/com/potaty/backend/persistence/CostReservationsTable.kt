/*
 * Copyright (c) 2026, Potaty
 *
 * Durable per-workspace cost reservations. Admission locks the owning workspace row and writes a
 * reservation in the same transaction, so parallel API instances cannot all pass a stale quota
 * read. Reservations linked to jobs follow authoritative job state until a terminal transition;
 * expiry is only a crash-recovery backstop for reservations that were never attached and never
 * crossed an external billable boundary. Once a provider attempt starts, the reservation remains
 * counted until usage and release commit atomically or an operator explicitly reconciles it.
 */

package com.potaty.backend.persistence

import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNotNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.or

object CostReservationsTable : Table("cost_reservations") {
    val id = uuid("id")
    val workspaceId = uuid("workspace_id")
    val reservationKey = text("reservation_key")
    val jobId = uuid("job_id").nullable()
    val amountUsd = double("amount_usd")
    val createdAt = timestamp("created_at")
    val expiresAt = timestamp("expires_at")

    /** Hash of the canonical external request; null for ordinary queued-job reservations. */
    val requestHash = text("request_hash").nullable()
    val externalOperation = text("external_operation").nullable()
    val externalProvider = text("external_provider").nullable()
    val externalModel = text("external_model").nullable()
    val externalStage = text("external_stage").nullable()
    val externalMetadata = jsonbString("external_metadata").nullable()
    val externalSpendStartedAt = timestamp("external_spend_started_at").nullable()

    /** Redacted, bounded transcript checkpoint used to resume without a second provider call. */
    val externalCheckpoint = jsonbString("external_checkpoint").nullable()
    val externalCheckpointedAt = timestamp("external_checkpointed_at").nullable()

    /** Rotating server-only token and lease fence the active provider/completion owner. */
    val processingToken = uuid("processing_token").nullable()
    val processingLeaseExpiresAt = timestamp("processing_lease_expires_at").nullable()

    /** Safe serialized HTTP outcome used to replay a completed idempotent external request. */
    val externalResult = jsonbString("external_result").nullable()
    val reconciliationDecision = text("reconciliation_decision").nullable()
    val reconciledAt = timestamp("reconciled_at").nullable()
    val reconciledBy = uuid("reconciled_by").nullable()
    val releasedAt = timestamp("released_at").nullable()
    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("uq_cost_reservations_workspace_key", workspaceId, reservationKey)
        check("ck_cost_reservations_external_terminal") {
            externalSpendStartedAt.isNull() or
                releasedAt.isNull() or
                externalResult.isNotNull() or
                reconciliationDecision.isNotNull()
        }
        check("ck_cost_reservations_checkpoint_pending") {
            externalCheckpoint.isNull() or
                (externalSpendStartedAt.isNotNull() and releasedAt.isNull())
        }
        check("ck_cost_reservations_processing_fence") {
            externalSpendStartedAt.isNull() or
                releasedAt.isNotNull() or
                (
                    requestHash.isNotNull() and
                        processingToken.isNotNull() and
                        processingLeaseExpiresAt.isNotNull()
                    )
        }
    }
}
