/*
 * Copyright (c) 2026, Potaty
 *
 * Exposed mirrors for operational tables currently accessed only by retention/deletion. The
 * authoritative PostgreSQL definitions remain in Flyway; these mirrors keep H2 parity and let
 * tenant purges use parameterized, workspace-scoped Exposed deletes.
 */

package com.potaty.backend.persistence

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

object RenderingsTable : Table("renderings") {
    val id = uuid("id")
    val workspaceId = uuid("workspace_id")
    val diagramVersionId = uuid("diagram_version_id")
    val format = text("format")
    val objectKey = text("object_key").nullable()
    val contentText = text("content_text").nullable()
    val contentHash = text("content_hash")
    val renderStatus = text("render_status")
    val renderWarnings = jsonbString("render_warnings")
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(diagramVersionId, format, contentHash)
    }
}

object LlmCredentialsTable : Table("llm_credentials") {
    val id = uuid("id")
    val workspaceId = uuid("workspace_id")
    val provider = text("provider")
    val credentialType = text("credential_type")
    val encryptedSecretRef = text("encrypted_secret_ref")
    val label = text("label")
    val status = text("status")
    val metadata = jsonbString("metadata")
    val createdBy = uuid("created_by").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val lastUsedAt = timestamp("last_used_at").nullable()
    val revokedAt = timestamp("revoked_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object AuditEventsTable : Table("audit_events") {
    val id = uuid("id")
    val workspaceId = uuid("workspace_id")
    val actorUserId = uuid("actor_user_id").nullable()
    val eventType = text("event_type")
    val resourceType = text("resource_type")
    val resourceId = uuid("resource_id").nullable()

    // PostgreSQL uses inet; H2 stores the same value as text. Retention only filters workspaceId.
    val ip = text("ip").nullable()
    val userAgent = text("user_agent").nullable()
    val payload = jsonbString("payload")
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}
