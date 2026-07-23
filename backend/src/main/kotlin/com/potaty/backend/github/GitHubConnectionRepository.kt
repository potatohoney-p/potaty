/*
 * Copyright (c) 2026, Potaty
 */

package com.potaty.backend.github

import com.potaty.backend.persistence.GitHubConnectStatesTable
import com.potaty.backend.persistence.GitHubInstallationsTable
import com.potaty.backend.persistence.TransactionContext
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update

data class GitHubConnectionRecord(
    val id: UUID,
    val workspaceId: UUID,
    val connectedByUserId: UUID,
    val installationId: Long,
    val appId: Long,
    val accountId: Long,
    val accountLogin: String,
    val accountType: String,
    val installationHtmlUrl: String,
    val githubUserId: Long,
    val githubLogin: String,
    val connectedAt: Instant
)

data class VerifiedGitHubInstallation(
    val installationId: Long,
    val appId: Long,
    val accountId: Long,
    val accountLogin: String,
    val accountType: String,
    val installationHtmlUrl: String,
    val githubUserId: Long,
    val githubLogin: String
)

class GitHubConnectionConflictException(message: String) : RuntimeException(message)

class GitHubConnectionRepository(private val txc: TransactionContext) {

    suspend fun saveVerified(
        workspaceId: UUID,
        connectedByUserId: UUID,
        verified: VerifiedGitHubInstallation
    ): GitHubConnectionRecord = txc.tx {
        val activeKey = activeKey(verified.appId, verified.installationId)
        val existing =
            GitHubInstallationsTable.select {
                (GitHubInstallationsTable.activeKey eq activeKey) and
                    GitHubInstallationsTable.disconnectedAt.isNull()
            }
                .limit(1)
                .map(::toRecord)
                .singleOrNull()
        if (existing != null) {
            if (existing.workspaceId != workspaceId) {
                throw GitHubConnectionConflictException(
                    "This GitHub installation is already connected to another workspace"
                )
            }
            val now = Instant.now()
            GitHubInstallationsTable.update({ GitHubInstallationsTable.id eq existing.id }) {
                it[GitHubInstallationsTable.connectedByUserId] = connectedByUserId
                it[accountId] = verified.accountId
                it[accountLogin] = verified.accountLogin
                it[accountType] = verified.accountType
                it[installationHtmlUrl] = verified.installationHtmlUrl
                it[githubUserId] = verified.githubUserId
                it[githubLogin] = verified.githubLogin
                it[updatedAt] = now
            }
            return@tx existing.copy(
                connectedByUserId = connectedByUserId,
                accountId = verified.accountId,
                accountLogin = verified.accountLogin,
                accountType = verified.accountType,
                installationHtmlUrl = verified.installationHtmlUrl,
                githubUserId = verified.githubUserId,
                githubLogin = verified.githubLogin
            )
        }

        val id = UUID.randomUUID()
        val now = Instant.now()
        try {
            GitHubInstallationsTable.insert {
                it[GitHubInstallationsTable.id] = id
                it[GitHubInstallationsTable.workspaceId] = workspaceId
                it[GitHubInstallationsTable.connectedByUserId] = connectedByUserId
                it[installationId] = verified.installationId
                it[appId] = verified.appId
                it[accountId] = verified.accountId
                it[accountLogin] = verified.accountLogin
                it[accountType] = verified.accountType
                it[installationHtmlUrl] = verified.installationHtmlUrl
                it[githubUserId] = verified.githubUserId
                it[githubLogin] = verified.githubLogin
                it[GitHubInstallationsTable.activeKey] = activeKey
                it[createdAt] = now
                it[updatedAt] = now
            }
        } catch (cause: org.jetbrains.exposed.exceptions.ExposedSQLException) {
            throw GitHubConnectionConflictException(
                "This GitHub installation became connected while the request was completing"
            )
        }
        GitHubConnectionRecord(
            id = id,
            workspaceId = workspaceId,
            connectedByUserId = connectedByUserId,
            installationId = verified.installationId,
            appId = verified.appId,
            accountId = verified.accountId,
            accountLogin = verified.accountLogin,
            accountType = verified.accountType,
            installationHtmlUrl = verified.installationHtmlUrl,
            githubUserId = verified.githubUserId,
            githubLogin = verified.githubLogin,
            connectedAt = now
        )
    }

    suspend fun listActive(workspaceId: UUID): List<GitHubConnectionRecord> = txc.tx {
        GitHubInstallationsTable.select {
            (GitHubInstallationsTable.workspaceId eq workspaceId) and
                GitHubInstallationsTable.disconnectedAt.isNull()
        }
            .map(::toRecord)
            .sortedBy { it.accountLogin.lowercase() }
    }

    suspend fun findActive(workspaceId: UUID, connectionId: UUID): GitHubConnectionRecord? =
        txc.tx {
            GitHubInstallationsTable.select {
                (GitHubInstallationsTable.id eq connectionId) and
                    (GitHubInstallationsTable.workspaceId eq workspaceId) and
                    GitHubInstallationsTable.disconnectedAt.isNull()
            }
                .limit(1)
                .map(::toRecord)
                .singleOrNull()
        }

    suspend fun disconnect(workspaceId: UUID, connectionId: UUID): GitHubConnectionRecord? =
        txc.tx {
            val existing =
                GitHubInstallationsTable.select {
                    (GitHubInstallationsTable.id eq connectionId) and
                        (GitHubInstallationsTable.workspaceId eq workspaceId) and
                        GitHubInstallationsTable.disconnectedAt.isNull()
                }
                    .limit(1)
                    .map(::toRecord)
                    .singleOrNull() ?: return@tx null
            val now = Instant.now()
            val updated =
                GitHubInstallationsTable.update({
                    (GitHubInstallationsTable.id eq connectionId) and
                        (GitHubInstallationsTable.workspaceId eq workspaceId) and
                        GitHubInstallationsTable.disconnectedAt.isNull()
                }) {
                    it[activeKey] = null
                    it[disconnectedAt] = now
                    it[updatedAt] = now
                }
            if (updated == 1) existing else null
        }

    private fun toRecord(row: org.jetbrains.exposed.sql.ResultRow): GitHubConnectionRecord =
        GitHubConnectionRecord(
            id = row[GitHubInstallationsTable.id],
            workspaceId = row[GitHubInstallationsTable.workspaceId],
            connectedByUserId = row[GitHubInstallationsTable.connectedByUserId],
            installationId = row[GitHubInstallationsTable.installationId],
            appId = row[GitHubInstallationsTable.appId],
            accountId = row[GitHubInstallationsTable.accountId],
            accountLogin = row[GitHubInstallationsTable.accountLogin],
            accountType = row[GitHubInstallationsTable.accountType],
            installationHtmlUrl = row[GitHubInstallationsTable.installationHtmlUrl],
            githubUserId = row[GitHubInstallationsTable.githubUserId],
            githubLogin = row[GitHubInstallationsTable.githubLogin],
            connectedAt = row[GitHubInstallationsTable.createdAt]
        )

    private fun activeKey(appId: Long, installationId: Long): String = "$appId:$installationId"
}

data class GitHubConnectAttempt(
    val workspaceId: UUID,
    val userId: UUID,
    val phase: GitHubConnectPhase,
    val candidateInstallationId: Long?,
    val pkceVerifier: String?,
    val expiresAt: Instant
)

class GitHubConnectStateRepository(private val txc: TransactionContext) {

    suspend fun create(state: GitHubConnectState, pkceVerifier: String? = null) = txc.tx {
        val now = Instant.now()
        GitHubConnectStatesTable.deleteWhere {
            GitHubConnectStatesTable.expiresAt less now.minus(1, ChronoUnit.DAYS)
        }
        GitHubConnectStatesTable.insert {
            it[nonceHash] = hashNonce(state.nonce)
            it[workspaceId] = UUID.fromString(state.workspaceId)
            it[userId] = UUID.fromString(state.userId)
            it[phase] = state.phase.name
            it[candidateInstallationId] = state.candidateInstallationId
            it[GitHubConnectStatesTable.pkceVerifier] = pkceVerifier
            it[expiresAt] = Instant.ofEpochSecond(state.expiresAtEpochSeconds)
            it[consumedAt] = null
            it[createdAt] = Instant.ofEpochSecond(state.issuedAtEpochSeconds)
        }
    }

    /**
     * Atomically marks a state nonce consumed. A null result means expired, replayed, or
     * mismatched.
     */
    suspend fun consume(
        state: GitHubConnectState,
        now: Instant = Instant.now()
    ): GitHubConnectAttempt? = txc.tx {
        val nonceHash = hashNonce(state.nonce)
        val workspaceId = UUID.fromString(state.workspaceId)
        val userId = UUID.fromString(state.userId)
        val candidateConstraint =
            state.candidateInstallationId?.let {
                GitHubConnectStatesTable.candidateInstallationId eq it
            } ?: GitHubConnectStatesTable.candidateInstallationId.isNull()
        val updated =
            GitHubConnectStatesTable.update({
                (GitHubConnectStatesTable.nonceHash eq nonceHash) and
                    (GitHubConnectStatesTable.workspaceId eq workspaceId) and
                    (GitHubConnectStatesTable.userId eq userId) and
                    (GitHubConnectStatesTable.phase eq state.phase.name) and
                    candidateConstraint and
                    GitHubConnectStatesTable.consumedAt.isNull() and
                    (GitHubConnectStatesTable.expiresAt greater now)
            }) {
                it[consumedAt] = now
            }
        if (updated != 1) return@tx null
        GitHubConnectStatesTable.select { GitHubConnectStatesTable.nonceHash eq nonceHash }
            .limit(1)
            .map { row ->
                GitHubConnectAttempt(
                    workspaceId = row[GitHubConnectStatesTable.workspaceId],
                    userId = row[GitHubConnectStatesTable.userId],
                    phase = GitHubConnectPhase.valueOf(row[GitHubConnectStatesTable.phase]),
                    candidateInstallationId = row[GitHubConnectStatesTable.candidateInstallationId],
                    pkceVerifier = row[GitHubConnectStatesTable.pkceVerifier],
                    expiresAt = row[GitHubConnectStatesTable.expiresAt]
                )
            }
            .singleOrNull()
    }

    private fun hashNonce(nonce: String): String =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                MessageDigest.getInstance("SHA-256").digest(nonce.toByteArray(Charsets.US_ASCII))
            )
}
