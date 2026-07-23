/*
 * Copyright (c) 2026, Potaty
 *
 * Exposed table for cost/usage accounting (WS6; plan 3.4 cost tracking + 21.1 "cost tracking").
 * One row per LLM call (or deterministic stage) so a workspace's month-to-date spend can be
 * summed for quota enforcement and a cost dashboard. Tenant-owned: every row carries
 * workspaceId and every query filters by it. H2-compatible column types only (cost stored as a
 * double; no jsonb/pgvector). Mirrors the authoritative Postgres DDL under db/migration.
 */

package com.potaty.backend.persistence

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

object UsageEventsTable : Table("usage_events") {
    val id = uuid("id")
    val workspaceId = uuid("workspace_id")

    /** The job that incurred the usage, when applicable (e.g. ad-hoc estimates have none). */
    val jobId = uuid("job_id").nullable()
    val provider = text("provider")
    val model = text("model")

    /** Pipeline stage that incurred the cost (e.g. "extract", "plan", "critic", "estimate"). */
    val stage = text("stage")
    val inputTokens = integer("input_tokens")
    val outputTokens = integer("output_tokens")
    val cachedInputTokens = integer("cached_input_tokens").default(0)

    /** Estimated USD cost for this event. Stored as a double for H2/Postgres portability. */
    val estimatedCostUsd = double("estimated_cost_usd")
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}
