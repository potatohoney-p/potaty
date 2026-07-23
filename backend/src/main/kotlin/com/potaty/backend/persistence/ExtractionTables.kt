/*
 * Copyright (c) 2026, Potaty
 *
 * Exposed tables for the deterministic extraction layer (plan section 8.3: extracted_entities /
 * extracted_relations). These persist the grounded entity/relation graph that the pipeline derives
 * from source chunks BEFORE any LLM interpretation (plan 7.1 / 2.3), so a run is reproducible and
 * auditable. As with the rest of the schema (Tables.kt) the authoritative Postgres DDL lives under
 * db/migration; these objects mirror it for H2/dev + typed query building only.
 *
 * Every row is tenant-owned (workspaceId) AND project-scoped (projectId) so repositories can always
 * filter by both. The pgvector/jsonb columns from the prod DDL are stored as plain text here
 * (evidence_chunk_ids / metadata as serialized JSON) to stay H2-compatible.
 */

package com.potaty.backend.persistence

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

object ExtractedEntitiesTable : Table("extracted_entities") {
    val id = uuid("id")
    val workspaceId = uuid("workspace_id")
    val projectId = uuid("project_id")
    val sourceVersionId = uuid("source_version_id").nullable()
    val entityKey = text("entity_key") // canonical/dedup key within a run
    val type = text("type")
    val name = text("name")
    val canonicalName = text("canonical_name")
    val summary = text("summary").nullable()
    val confidence = decimal("confidence", precision = 4, scale = 3)
    val evidenceChunkIds = jsonbString("evidence_chunk_ids")
    val evidence = jsonbString("evidence")
    val metadata = jsonbString("metadata")
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

object ExtractedRelationsTable : Table("extracted_relations") {
    val id = uuid("id")
    val workspaceId = uuid("workspace_id")
    val projectId = uuid("project_id")
    val sourceVersionId = uuid("source_version_id").nullable()
    val fromEntityKey = text("from_entity_key")
    val toEntityKey = text("to_entity_key")
    val type = text("type")
    val label = text("label").nullable()
    val confidence = decimal("confidence", precision = 4, scale = 3)
    val evidenceChunkIds = jsonbString("evidence_chunk_ids")
    val evidence = jsonbString("evidence")
    val metadata = jsonbString("metadata")
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}
