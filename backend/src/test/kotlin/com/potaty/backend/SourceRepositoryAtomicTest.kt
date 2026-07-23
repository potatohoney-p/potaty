/*
 * Copyright (c) 2026, Potaty
 *
 * Transaction-boundary tests for source, version, and evidence-chunk ingestion.
 */

package com.potaty.backend

import com.potaty.backend.jobs.Idempotency
import com.potaty.backend.persistence.Database
import com.potaty.backend.persistence.IdentityRepository
import com.potaty.backend.persistence.SourceIngestionClaimsTable
import com.potaty.backend.persistence.SourcesTable
import com.potaty.backend.persistence.TransactionContext
import com.potaty.backend.persistence.bootstrapDevelopmentIdentity
import com.potaty.backend.persistence.repositories.AtomicIngestionClaim
import com.potaty.backend.persistence.repositories.IdempotencyConflictException
import com.potaty.backend.persistence.repositories.IngestionClaimLostException
import com.potaty.backend.persistence.repositories.SourceRepository
import com.potaty.backend.source.TextChunk
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.update

class SourceRepositoryAtomicTest {
    @Test
    fun h2SchemaRejectsInvalidIngestionClaimStateAndKey() = runBlocking {
        val config = testConfig()
        val database = Database.connect(config.database)
        val txc = TransactionContext(database.exposed)
        bootstrapDevelopmentIdentity(txc, config.auth)
        val workspaceId = UUID.fromString(config.auth.devWorkspaceId)
        val projectId = UUID.fromString(config.auth.devProjectId)
        val now = Instant.now()

        suspend fun assertRejected(key: String, token: UUID?) {
            assertFails {
                txc.tx {
                    SourceIngestionClaimsTable.insert {
                        it[id] = UUID.randomUUID()
                        it[SourceIngestionClaimsTable.workspaceId] = workspaceId
                        it[SourceIngestionClaimsTable.projectId] = projectId
                        it[sourceType] = "GITHUB_REPO"
                        it[ingestionKey] = key
                        it[requestHash] = "sha256:${"a".repeat(64)}"
                        it[status] = "processing"
                        it[processingToken] = token
                        it[leaseExpiresAt] = now.plusSeconds(60)
                        it[sourceId] = null
                        it[sourceVersionId] = null
                        it[createdAt] = now
                        it[updatedAt] = now
                    }
                }
            }
        }

        try {
            assertRejected("github:missing-token", null)
            assertRejected("github key with spaces", UUID.randomUUID())
        } finally {
            database.close()
        }
    }

    @Test
    fun claimLifecycleRejectsConflictsAndFencesExpiredOwner() = runBlocking {
        val config = testConfig()
        val database = Database.connect(config.database)
        val txc = TransactionContext(database.exposed)
        bootstrapDevelopmentIdentity(txc, config.auth)
        val workspaceId = UUID.fromString(config.auth.devWorkspaceId)
        val projectId = UUID.fromString(config.auth.devProjectId)
        val repository = SourceRepository(txc, IdentityRepository(txc))
        val requestHash = "sha256:" + Idempotency.sha256("claim-request")

        try {
            val first =
                assertIs<AtomicIngestionClaim.Acquired>(
                    repository.acquireAtomicIngestionClaim(
                        workspaceId,
                        projectId,
                        "GITHUB_REPO",
                        "github:claim-lifecycle",
                        requestHash,
                        Duration.ofSeconds(30)
                    )
                )
            assertIs<AtomicIngestionClaim.Busy>(
                repository.acquireAtomicIngestionClaim(
                    workspaceId,
                    projectId,
                    "GITHUB_REPO",
                    "github:claim-lifecycle",
                    requestHash,
                    Duration.ofSeconds(30)
                )
            )
            assertFailsWith<IdempotencyConflictException> {
                repository.acquireAtomicIngestionClaim(
                    workspaceId,
                    projectId,
                    "GITHUB_REPO",
                    "github:claim-lifecycle",
                    "sha256:${"b".repeat(64)}"
                )
            }

            txc.tx {
                SourceIngestionClaimsTable.update({
                    (SourceIngestionClaimsTable.workspaceId eq workspaceId) and
                        (SourceIngestionClaimsTable.ingestionKey eq "github:claim-lifecycle")
                }) {
                    it[leaseExpiresAt] = Instant.now().minusSeconds(1)
                }
            }

            val takeover =
                assertIs<AtomicIngestionClaim.Acquired>(
                    repository.acquireAtomicIngestionClaim(
                        workspaceId,
                        projectId,
                        "GITHUB_REPO",
                        "github:claim-lifecycle",
                        requestHash,
                        Duration.ofMinutes(1)
                    )
                )
            assertTrue(first.token != takeover.token)
            repository.releaseAtomicIngestionClaim(
                workspaceId,
                "github:claim-lifecycle",
                first.token
            )
            assertEquals(1, repository.countAtomicIngestionClaims(workspaceId))
            repository.releaseAtomicIngestionClaim(
                workspaceId,
                "github:claim-lifecycle",
                takeover.token
            )
            assertEquals(0, repository.countAtomicIngestionClaims(workspaceId))
        } finally {
            database.close()
        }
    }

    @Test
    fun expiredOwnerCannotRenewOrFinalizeGitHubSource() = runBlocking {
        val config = testConfig()
        val database = Database.connect(config.database)
        val txc = TransactionContext(database.exposed)
        bootstrapDevelopmentIdentity(txc, config.auth)
        val workspaceId = UUID.fromString(config.auth.devWorkspaceId)
        val projectId = UUID.fromString(config.auth.devProjectId)
        val userId = UUID.fromString(config.auth.devUserId)
        val repository = SourceRepository(txc, IdentityRepository(txc))
        val requestHash = "sha256:" + Idempotency.sha256("expired-claim")

        try {
            val expired =
                assertIs<AtomicIngestionClaim.Acquired>(
                    repository.acquireAtomicIngestionClaim(
                        workspaceId,
                        projectId,
                        "GITHUB_REPO",
                        "github:expired-owner",
                        requestHash,
                        Duration.ofSeconds(30)
                    )
                )
            txc.tx {
                SourceIngestionClaimsTable.update({
                    (SourceIngestionClaimsTable.workspaceId eq workspaceId) and
                        (SourceIngestionClaimsTable.ingestionKey eq "github:expired-owner")
                }) {
                    it[leaseExpiresAt] = Instant.now().minusSeconds(1)
                }
            }
            assertFailsWith<IngestionClaimLostException> {
                repository.renewAtomicIngestionClaim(
                    workspaceId,
                    "github:expired-owner",
                    expired.token,
                    Duration.ofMinutes(1)
                )
            }
            assertFailsWith<IngestionClaimLostException> {
                repository.createTextSourceAtomic(
                    workspaceId = workspaceId,
                    projectId = projectId,
                    sourceType = "GITHUB_REPO",
                    displayName = "Expired owner",
                    externalRefJson = "{}",
                    createdBy = userId,
                    contentHash = "sha256:" + Idempotency.sha256("A -> B"),
                    metadataJson = """{"kind":"github","chunkCount":1}""",
                    chunks = listOf(TextChunk(0, "src/App.kt", 1, 1, "A -> B", 2)),
                    ingestionKey = "github:expired-owner",
                    requestHash = requestHash,
                    claimToken = expired.token
                )
            }
            assertEquals(0, repository.listSources(workspaceId, projectId).size)
        } finally {
            database.close()
        }
    }

    @Test
    fun h2SchemaRejectsUnboundOrMalformedIngestionHashes() = runBlocking {
        val config = testConfig()
        val database = Database.connect(config.database)
        val txc = TransactionContext(database.exposed)
        bootstrapDevelopmentIdentity(txc, config.auth)
        val workspaceId = UUID.fromString(config.auth.devWorkspaceId)
        val projectId = UUID.fromString(config.auth.devProjectId)
        val now = Instant.parse("2026-01-01T00:00:00Z")

        suspend fun assertRejected(
            ingestionKey: String?,
            requestHash: String?
        ) {
            assertFails {
                txc.tx {
                    SourcesTable.insert {
                        it[id] = UUID.randomUUID()
                        it[SourcesTable.workspaceId] = workspaceId
                        it[SourcesTable.projectId] = projectId
                        it[sourceType] = "TEXT_PASTE"
                        it[displayName] = "Invalid ingestion binding"
                        it[externalRef] = "{}"
                        it[SourcesTable.ingestionKey] = ingestionKey
                        it[ingestionRequestHash] = requestHash
                        it[createdAt] = now
                        it[updatedAt] = now
                    }
                }
            }
        }

        try {
            assertRejected("source:missing-hash", null)
            assertRejected(null, "sha256:${"a".repeat(64)}")
            assertRejected("source:malformed-hash", "not-a-sha256-hash")
        } finally {
            database.close()
        }
    }

    @Test
    fun concurrentEquivalentRequestsReturnOneCompleteArtifact() = runBlocking {
        val config = testConfig()
        val database = Database.connect(config.database)
        val txc = TransactionContext(database.exposed)
        bootstrapDevelopmentIdentity(txc, config.auth)
        val workspaceId = UUID.fromString(config.auth.devWorkspaceId)
        val projectId = UUID.fromString(config.auth.devProjectId)
        val userId = UUID.fromString(config.auth.devUserId)
        val repository = SourceRepository(txc, IdentityRepository(txc))
        val requestHash = "sha256:" + Idempotency.sha256("concurrent-source-request")
        val chunks = listOf(TextChunk(0, null, 1, 1, "A -> B", 2))

        try {
            val results = coroutineScope {
                (1..8).map {
                    async(Dispatchers.IO) {
                        repository.createTextSourceAtomic(
                            workspaceId = workspaceId,
                            projectId = projectId,
                            sourceType = "TEXT_PASTE",
                            displayName = "Concurrent test",
                            externalRefJson = "{}",
                            createdBy = userId,
                            contentHash = "sha256:" + Idempotency.sha256("A -> B"),
                            metadataJson = """{"kind":"text","chunkCount":1}""",
                            chunks = chunks,
                            ingestionKey = "source:concurrent-1",
                            requestHash = requestHash
                        )
                    }
                }.awaitAll()
            }

            assertEquals(1, results.map { it.sourceId }.toSet().size)
            assertEquals(1, results.map { it.sourceVersionId }.toSet().size)
            assertEquals(1, repository.listSources(workspaceId, projectId).size)
            assertEquals(
                1,
                repository.listChunks(workspaceId, results.first().sourceVersionId).size
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun chunkInsertFailureRollsBackSourceAndVersion() = runBlocking {
        val config = testConfig()
        val database = Database.connect(config.database)
        val txc = TransactionContext(database.exposed)
        bootstrapDevelopmentIdentity(txc, config.auth)
        val workspaceId = UUID.fromString(config.auth.devWorkspaceId)
        val projectId = UUID.fromString(config.auth.devProjectId)
        val userId = UUID.fromString(config.auth.devUserId)
        val requestHash = "sha256:" + Idempotency.sha256("atomic-source-request")
        val duplicateChunkId = UUID.randomUUID()
        val generatedIds =
            listOf(
                UUID.randomUUID(),
                UUID.randomUUID(),
                duplicateChunkId,
                duplicateChunkId
            )
        var nextId = 0
        val failing =
            SourceRepository(txc, IdentityRepository(txc)) {
                generatedIds[nextId++ % generatedIds.size]
            }
        val chunks =
            listOf(
                TextChunk(0, null, 1, 1, "A -> B", 2),
                TextChunk(1, null, 2, 2, "B -> C", 2)
            )

        try {
            assertFails {
                failing.createTextSourceAtomic(
                    workspaceId = workspaceId,
                    projectId = projectId,
                    sourceType = "TEXT_PASTE",
                    displayName = "Atomic test",
                    externalRefJson = "{}",
                    createdBy = userId,
                    contentHash = "sha256:" + Idempotency.sha256("A -> B\nB -> C"),
                    metadataJson = """{"kind":"text","chunkCount":2}""",
                    chunks = chunks,
                    ingestionKey = "source:atomic-rollback-1",
                    requestHash = requestHash
                )
            }

            val repository = SourceRepository(txc, IdentityRepository(txc))
            assertEquals(0, repository.listSources(workspaceId, projectId).size)
            assertNull(
                repository.findAtomicIngestion(
                    workspaceId,
                    projectId,
                    "TEXT_PASTE",
                    "source:atomic-rollback-1",
                    requestHash
                )
            )
        } finally {
            database.close()
        }
    }
}
