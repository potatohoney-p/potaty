/*
 * Copyright (c) 2026, Potaty
 *
 * Exposed table objects for typed queries. The authoritative schema is the Flyway SQL
 * under db/migration (plan section 8); these objects mirror it for query building only.
 * Every tenant-owned table exposes workspaceId so repositories can always filter by it.
 */

package com.potaty.backend.persistence

import org.jetbrains.exposed.sql.BooleanColumnType
import org.jetbrains.exposed.sql.CustomFunction
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNotNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.stringLiteral

object SourcesTable : Table("sources") {
    val id = uuid("id")
    val workspaceId = uuid("workspace_id")
    val projectId = uuid("project_id")
    val sourceType = text("source_type")
    val displayName = text("display_name")
    val externalRef = jsonbString("external_ref")

    /** Stable server-generated key for crash-safe idempotent source ingestion. */
    val ingestionKey = text("ingestion_key").nullable()

    /** Hash of the complete logical request bound to [ingestionKey]. */
    val ingestionRequestHash = text("ingestion_request_hash").nullable()
    val createdBy = uuid("created_by").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val deletedAt = timestamp("deleted_at").nullable()
    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("uq_sources_workspace_ingestion_key", workspaceId, ingestionKey)
        uniqueIndex("uq_sources_workspace_id", workspaceId, id)
        check("ck_sources_ingestion_request_hash") {
            val hasValidRequestHash =
                CustomFunction<Boolean>(
                    "REGEXP_LIKE",
                    BooleanColumnType(),
                    ingestionRequestHash,
                    stringLiteral("^sha256:[0-9a-f]{64}$")
                ) eq true

            (ingestionKey.isNull() and ingestionRequestHash.isNull()) or
                (
                    ingestionKey.isNotNull() and
                        ingestionRequestHash.isNotNull() and
                        hasValidRequestHash
                    )
        }
    }
}

object SourceVersionsTable : Table("source_versions") {
    val id = uuid("id")
    val workspaceId = uuid("workspace_id")
    val sourceId = uuid("source_id")
    val versionLabel = text("version_label").nullable()
    val contentHash = text("content_hash")
    val normalizedTextObjectKey = text("normalized_text_object_key").nullable()
    val rawObjectKey = text("raw_object_key").nullable()
    val metadata = jsonbString("metadata")
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)

    init {
        // Mirrors V1 so H2 exercises the same idempotent-version invariant as Postgres.
        uniqueIndex("uq_source_versions_source_content", sourceId, contentHash)
        uniqueIndex("uq_source_versions_workspace_id", workspaceId, id)
    }
}

object SourceChunksTable : Table("source_chunks") {
    val id = uuid("id")
    val workspaceId = uuid("workspace_id")
    val sourceVersionId = uuid("source_version_id")
    val chunkIndex = integer("chunk_index")
    val path = text("path").nullable()
    val startLine = integer("start_line").nullable()
    val endLine = integer("end_line").nullable()
    val startMs = integer("start_ms").nullable()
    val endMs = integer("end_ms").nullable()
    val speaker = text("speaker").nullable()
    val content = text("text")
    val textHash = text("text_hash")
    val tokenCount = integer("token_count")
    val metadata = jsonbString("metadata")
    val createdAt = timestamp("created_at")

    // NOTE: the pgvector `embedding` column exists only in the Postgres DDL (Flyway); Exposed
    // does not model it (pgvector is Postgres-only). H2/dev runs without embeddings.
    override val primaryKey = PrimaryKey(id)

    init {
        // Mirrors V1 so concurrent/retried ingestion cannot duplicate an ordered chunk.
        uniqueIndex("uq_source_chunks_version_index", sourceVersionId, chunkIndex)
    }
}

/**
 * Short-lived ownership fence for source ingestion that performs billable or rate-limited
 * outbound work. A complete source remains the replay authority; this row prevents concurrent
 * callers from doing that outbound work before the atomic source transaction commits.
 */
object SourceIngestionClaimsTable : Table("source_ingestion_claims") {
    val id = uuid("id")
    val workspaceId = uuid("workspace_id")
    val projectId = uuid("project_id")
    val sourceType = text("source_type")
    val ingestionKey = text("ingestion_key")
    val requestHash = text("request_hash")
    val status = text("status")
    val processingToken = uuid("processing_token").nullable()
    val leaseExpiresAt = timestamp("lease_expires_at").nullable()
    val sourceId = uuid("source_id").nullable()
    val sourceVersionId = uuid("source_version_id").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("uq_source_ingestion_claims_workspace_key", workspaceId, ingestionKey)
        index("idx_source_ingestion_claims_recovery", false, status, leaseExpiresAt)
        foreignKey(
            workspaceId to ProjectsTable.workspaceId,
            projectId to ProjectsTable.id,
            name = "fk_source_ingestion_claims_workspace_project"
        )
        foreignKey(
            workspaceId to SourcesTable.workspaceId,
            sourceId to SourcesTable.id,
            name = "fk_source_ingestion_claims_workspace_source"
        )
        foreignKey(
            workspaceId to SourceVersionsTable.workspaceId,
            sourceVersionId to SourceVersionsTable.id,
            name = "fk_source_ingestion_claims_workspace_version"
        )
        check("ck_source_ingestion_claims_key_hash") {
            val validKey =
                CustomFunction<Boolean>(
                    "REGEXP_LIKE",
                    BooleanColumnType(),
                    ingestionKey,
                    stringLiteral("^[A-Za-z0-9][A-Za-z0-9._:-]{0,255}$")
                ) eq true
            val validHash =
                CustomFunction<Boolean>(
                    "REGEXP_LIKE",
                    BooleanColumnType(),
                    requestHash,
                    stringLiteral("^sha256:[0-9a-f]{64}$")
                ) eq true
            validKey and validHash
        }
        check("ck_source_ingestion_claims_state") {
            (
                (status eq "processing") and
                    processingToken.isNotNull() and
                    leaseExpiresAt.isNotNull() and
                    sourceId.isNull() and
                    sourceVersionId.isNull()
                ) or
                (
                    (status eq "complete") and
                        processingToken.isNull() and
                        leaseExpiresAt.isNull() and
                        sourceId.isNotNull() and
                        sourceVersionId.isNotNull()
                    )
        }
    }
}

object DiagramsTable : Table("diagrams") {
    val id = uuid("id")
    val workspaceId = uuid("workspace_id")
    val projectId = uuid("project_id")
    val title = text("title")
    val diagramType = text("diagram_type")
    val status = text("status")

    /** The generation job that owns this artifact; null for synchronous/manual diagrams. */
    val generationJobId = uuid("generation_job_id").nullable()
    val createdBy = uuid("created_by").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val deletedAt = timestamp("deleted_at").nullable()
    override val primaryKey = PrimaryKey(id)

    init {
        // A retried/reclaimed job can commit at most one diagram artifact.
        uniqueIndex("uq_diagrams_workspace_generation_job", workspaceId, generationJobId)
    }
}

object DiagramVersionsTable : Table("diagram_versions") {
    val id = uuid("id")
    val workspaceId = uuid("workspace_id")
    val diagramId = uuid("diagram_id")
    val versionNumber = integer("version_number")
    val cause = text("cause")
    val ir = jsonbString("ir")
    val validationReport = jsonbString("validation_report")
    val evidenceCoverage = jsonbString("evidence_coverage")
    val sourceSnapshot = jsonbString("source_snapshot")
    val modelTrace = jsonbString("model_trace")
    val rendererVersion = text("renderer_version")
    val layoutEngineVersion = text("layout_engine_version")
    val createdBy = uuid("created_by").nullable()
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

object JobsTable : Table("jobs") {
    val id = uuid("id")
    val workspaceId = uuid("workspace_id")
    val projectId = uuid("project_id").nullable()
    val jobType = text("job_type")
    val status = text("status")
    val idempotencyKey = text("idempotency_key")
    val priority = integer("priority")
    val attempts = integer("attempts")
    val maxAttempts = integer("max_attempts")
    val input = jsonbString("input")
    val output = jsonbString("output").nullable()
    val error = jsonbString("error").nullable()
    val lockedBy = text("locked_by").nullable()
    val lockedUntil = timestamp("locked_until").nullable()
    val runAfter = timestamp("run_after")
    val createdBy = uuid("created_by").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val completedAt = timestamp("completed_at").nullable()
    override val primaryKey = PrimaryKey(id)

    init {
        // Mirrors the Postgres DDL: identical logical jobs collapse to one row (plan 11.3).
        uniqueIndex("uq_jobs_workspace_idempotency", workspaceId, idempotencyKey)
    }
}

object JobEventsTable : Table("job_events") {
    val id = uuid("id")
    val workspaceId = uuid("workspace_id")
    val jobId = uuid("job_id")
    val eventType = text("event_type")
    val stage = text("stage").nullable()
    val message = text("message").nullable()
    val payload = jsonbString("payload")
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}
