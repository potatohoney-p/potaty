/*
 * Copyright (c) 2026, Potaty
 *
 * Durable, tenant-scoped GitHub App installation bindings plus short-lived one-time connect state.
 */

package com.potaty.backend.persistence

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

object GitHubInstallationsTable : Table("github_installations") {
    val id = uuid("id")
    val workspaceId = uuid("workspace_id")
    val connectedByUserId = uuid("connected_by_user_id")
    val installationId = long("installation_id")
    val appId = long("app_id")
    val accountId = long("account_id")
    val accountLogin = text("account_login")
    val accountType = text("account_type")
    val installationHtmlUrl = text("installation_html_url")
    val githubUserId = long("github_user_id")
    val githubLogin = text("github_login")

    /** Non-null only while active; a unique nullable key permits audited reconnect after disconnect. */
    val activeKey = text("active_key").nullable().uniqueIndex("uq_github_installations_active_key")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val disconnectedAt = timestamp("disconnected_at").nullable()
    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("uq_github_installations_workspace_id", workspaceId, id)
    }
}

object GitHubConnectStatesTable : Table("github_connect_states") {
    /** SHA-256 of the random state nonce; the browser-visible nonce itself is never persisted. */
    val nonceHash = text("nonce_hash")
    val workspaceId = uuid("workspace_id")
    val userId = uuid("user_id")
    val phase = text("phase")
    val candidateInstallationId = long("candidate_installation_id").nullable()
    val pkceVerifier = text("pkce_verifier").nullable()
    val expiresAt = timestamp("expires_at")
    val consumedAt = timestamp("consumed_at").nullable()
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(nonceHash)
}
