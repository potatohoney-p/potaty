/*
 * Copyright (c) 2026, Potaty
 *
 * Real PostgreSQL/Flyway contract test for source-ingestion fencing. CI supplies a disposable
 * pgvector database; local runs skip only when POTATY_TEST_POSTGRES_URL is intentionally absent.
 */

package com.potaty.backend

import com.potaty.backend.config.DatabaseConfig
import com.potaty.backend.jobs.Idempotency
import com.potaty.backend.ops.RetentionService
import com.potaty.backend.persistence.Database
import com.potaty.backend.persistence.IdentityRepository
import com.potaty.backend.persistence.ProjectsTable
import com.potaty.backend.persistence.SourceIngestionClaimsTable
import com.potaty.backend.persistence.TenantIntegrityException
import com.potaty.backend.persistence.TransactionContext
import com.potaty.backend.persistence.UsersTable
import com.potaty.backend.persistence.WorkspaceMembersTable
import com.potaty.backend.persistence.WorkspacesTable
import com.potaty.backend.persistence.repositories.AtomicIngestionClaim
import com.potaty.backend.persistence.repositories.IngestionClaimLostException
import com.potaty.backend.persistence.repositories.SourceRepository
import com.potaty.backend.source.TextChunk
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.Assumptions.assumeTrue

class PostgresSourceIngestionClaimIntegrationTest {
    @Test
    fun flywayClaimContractFencesRacesAndPurgesTenantData() = runBlocking {
        val jdbcUrl = System.getenv("POTATY_TEST_POSTGRES_URL")
        assumeTrue(
            !jdbcUrl.isNullOrBlank(),
            "Set POTATY_TEST_POSTGRES_URL to a disposable pgvector PostgreSQL database"
        )
        val database =
            Database.connect(
                DatabaseConfig(
                    mode = "postgres",
                    jdbcUrl = checkNotNull(jdbcUrl),
                    username = System.getenv("POTATY_TEST_POSTGRES_USER") ?: "potaty",
                    password = System.getenv("POTATY_TEST_POSTGRES_PASSWORD") ?: "potaty",
                    maxPoolSize = 10,
                    runFlywayMigrations = true
                )
            )
        val txc = TransactionContext(database.exposed)
        val workspaceA = UUID.randomUUID()
        val workspaceB = UUID.randomUUID()
        val userA = UUID.randomUUID()
        val userB = UUID.randomUUID()
        val projectA = UUID.randomUUID()
        val projectB = UUID.randomUUID()
        val repository = SourceRepository(txc, IdentityRepository(txc))
        val retention = RetentionService(txc)

        try {
            seedIdentity(txc, workspaceA, userA, projectA, "a")
            seedIdentity(txc, workspaceB, userB, projectB, "b")
            val retainedA =
                createFixtureSource(repository, workspaceA, projectA, userA, "retained-a")
            val retainedB =
                createFixtureSource(repository, workspaceB, projectB, userB, "retained-b")

            assertFails("composite project FK must reject a cross-tenant claim") {
                txc.tx {
                    insertProcessingClaim(
                        workspaceId = workspaceA,
                        projectId = projectB,
                        key = "github:postgres-cross-tenant",
                        token = UUID.randomUUID()
                    )
                }
            }
            assertFails("processing state must require a token") {
                txc.tx {
                    insertProcessingClaim(
                        workspaceId = workspaceA,
                        projectId = projectA,
                        key = "github:postgres-invalid-state",
                        token = null
                    )
                }
            }
            assertFails("claim key constraint must reject whitespace") {
                txc.tx {
                    insertProcessingClaim(
                        workspaceId = workspaceA,
                        projectId = projectA,
                        key = "github:invalid key",
                        token = UUID.randomUUID()
                    )
                }
            }
            assertFails("claim hash constraint must require canonical sha256") {
                txc.tx {
                    insertProcessingClaim(
                        workspaceId = workspaceA,
                        projectId = projectA,
                        key = "github:invalid-hash",
                        token = UUID.randomUUID(),
                        requestHash = "sha256:not-a-hash"
                    )
                }
            }
            assertFails("composite source FK must reject a cross-tenant source") {
                txc.tx {
                    insertCompleteClaim(
                        workspaceId = workspaceA,
                        projectId = projectA,
                        key = "github:cross-tenant-source",
                        sourceId = retainedB.sourceId,
                        sourceVersionId = retainedA.sourceVersionId
                    )
                }
            }
            assertFails("composite version FK must reject a cross-tenant version") {
                txc.tx {
                    insertCompleteClaim(
                        workspaceId = workspaceA,
                        projectId = projectA,
                        key = "github:cross-tenant-version",
                        sourceId = retainedA.sourceId,
                        sourceVersionId = retainedB.sourceVersionId
                    )
                }
            }

            val takeoverHash = "sha256:" + Idempotency.sha256("postgres-takeover")
            val expired =
                assertIs<AtomicIngestionClaim.Acquired>(
                    repository.acquireAtomicIngestionClaim(
                        workspaceA,
                        projectA,
                        "GITHUB_REPO",
                        "github:postgres-takeover",
                        takeoverHash,
                        Duration.ofSeconds(30)
                    )
                )
            txc.tx {
                SourceIngestionClaimsTable.update({
                    (SourceIngestionClaimsTable.workspaceId eq workspaceA) and
                        (SourceIngestionClaimsTable.ingestionKey eq "github:postgres-takeover")
                }) {
                    it[leaseExpiresAt] = Instant.now().minusSeconds(1)
                }
            }
            val contenders = coroutineScope {
                val start = CompletableDeferred<Unit>()
                val ready = AtomicInteger()
                val attempts = (1..8).map {
                    async(Dispatchers.IO) {
                        ready.incrementAndGet()
                        start.await()
                        repository.acquireAtomicIngestionClaim(
                            workspaceA,
                            projectA,
                            "GITHUB_REPO",
                            "github:postgres-takeover",
                            takeoverHash
                        )
                    }
                }
                withTimeout(5_000) {
                    while (ready.get() < attempts.size) yield()
                }
                start.complete(Unit)
                attempts.awaitAll()
            }
            val winner = contenders.filterIsInstance<AtomicIngestionClaim.Acquired>().single()
            assertTrue(
                contenders.all {
                    it is AtomicIngestionClaim.Acquired || it is AtomicIngestionClaim.Busy
                }
            )
            assertFailsWith<IngestionClaimLostException> {
                repository.renewAtomicIngestionClaim(
                    workspaceA,
                    "github:postgres-takeover",
                    expired.token,
                    Duration.ofMinutes(1)
                )
            }
            repository.releaseAtomicIngestionClaim(
                workspaceA,
                "github:postgres-takeover",
                winner.token
            )

            assertFailsWith<TenantIntegrityException> {
                repository.acquireAtomicIngestionClaim(
                    workspaceA,
                    projectB,
                    "GITHUB_REPO",
                    "github:postgres-repository-tenant",
                    "sha256:${"b".repeat(64)}"
                )
            }

            val completionHash = "sha256:" + Idempotency.sha256("postgres-completion")
            val owned =
                assertIs<AtomicIngestionClaim.Acquired>(
                    repository.acquireAtomicIngestionClaim(
                        workspaceA,
                        projectA,
                        "GITHUB_REPO",
                        "github:postgres-completion",
                        completionHash
                    )
                )
            val stored =
                repository.createTextSourceAtomic(
                    workspaceId = workspaceA,
                    projectId = projectA,
                    sourceType = "GITHUB_REPO",
                    displayName = "postgres/fixture@main",
                    externalRefJson = "{}",
                    createdBy = userA,
                    contentHash = "sha256:" + Idempotency.sha256("A -> B"),
                    metadataJson = """{"kind":"github","chunkCount":1}""",
                    chunks = listOf(TextChunk(0, "src/App.kt", 1, 1, "A -> B", 2)),
                    ingestionKey = "github:postgres-completion",
                    requestHash = completionHash,
                    claimToken = owned.token
                )
            val replay =
                assertIs<AtomicIngestionClaim.Complete>(
                    repository.acquireAtomicIngestionClaim(
                        workspaceA,
                        projectA,
                        "GITHUB_REPO",
                        "github:postgres-completion",
                        completionHash
                    )
                )
            assertEquals(stored.sourceId, replay.source.sourceId)

            assertIs<AtomicIngestionClaim.Acquired>(
                repository.acquireAtomicIngestionClaim(
                    workspaceB,
                    projectB,
                    "GITHUB_REPO",
                    "github:postgres-retained-b",
                    "sha256:" + Idempotency.sha256("postgres-retained-b")
                )
            )

            val report = retention.deleteWorkspaceData(workspaceA)
            assertEquals(1, report.sourceIngestionClaims)
            assertEquals(2, report.sources)
            assertEquals(2, report.sourceVersions)
            assertEquals(2, report.sourceChunks)
            assertEquals(0, repository.countAtomicIngestionClaims(workspaceA))
            assertEquals(1, repository.countAtomicIngestionClaims(workspaceB))
            assertEquals(1, repository.listSources(workspaceB, projectB).size)
        } finally {
            runCatching { retention.deleteWorkspaceData(workspaceA) }
            runCatching { retention.deleteWorkspaceData(workspaceB) }
            runCatching {
                txc.tx {
                    WorkspaceMembersTable.deleteWhere {
                        (WorkspaceMembersTable.workspaceId eq workspaceA) or
                            (WorkspaceMembersTable.workspaceId eq workspaceB)
                    }
                    ProjectsTable.deleteWhere {
                        (ProjectsTable.id eq projectA) or (ProjectsTable.id eq projectB)
                    }
                    UsersTable.deleteWhere {
                        (UsersTable.id eq userA) or (UsersTable.id eq userB)
                    }
                    WorkspacesTable.deleteWhere {
                        (WorkspacesTable.id eq workspaceA) or (WorkspacesTable.id eq workspaceB)
                    }
                }
            }
            database.close()
        }
    }

    private suspend fun seedIdentity(
        txc: TransactionContext,
        workspaceId: UUID,
        userId: UUID,
        projectId: UUID,
        suffix: String
    ) {
        val now = Instant.now()
        txc.tx {
            WorkspacesTable.insert {
                it[id] = workspaceId
                it[name] = "Postgres fixture $suffix"
                it[slug] = "postgres-fixture-$workspaceId"
                it[plan] = "test"
                it[createdAt] = now
                it[updatedAt] = now
            }
            UsersTable.insert {
                it[id] = userId
                it[email] = "$userId@potaty.invalid"
                it[displayName] = "Fixture $suffix"
                it[createdAt] = now
                it[updatedAt] = now
            }
            WorkspaceMembersTable.insert {
                it[WorkspaceMembersTable.workspaceId] = workspaceId
                it[WorkspaceMembersTable.userId] = userId
                it[role] = "owner"
                it[createdAt] = now
            }
            ProjectsTable.insert {
                it[id] = projectId
                it[ProjectsTable.workspaceId] = workspaceId
                it[name] = "Postgres fixture $suffix"
                it[slug] = "postgres-fixture-$projectId"
                it[description] = null
                it[createdAt] = now
                it[updatedAt] = now
            }
        }
    }

    private fun insertProcessingClaim(
        workspaceId: UUID,
        projectId: UUID,
        key: String,
        token: UUID?,
        requestHash: String = "sha256:${"a".repeat(64)}"
    ) {
        val now = Instant.now()
        SourceIngestionClaimsTable.insert {
            it[id] = UUID.randomUUID()
            it[SourceIngestionClaimsTable.workspaceId] = workspaceId
            it[SourceIngestionClaimsTable.projectId] = projectId
            it[sourceType] = "GITHUB_REPO"
            it[ingestionKey] = key
            it[SourceIngestionClaimsTable.requestHash] = requestHash
            it[status] = "processing"
            it[processingToken] = token
            it[leaseExpiresAt] = now.plusSeconds(60)
            it[sourceId] = null
            it[sourceVersionId] = null
            it[createdAt] = now
            it[updatedAt] = now
        }
    }

    private fun insertCompleteClaim(
        workspaceId: UUID,
        projectId: UUID,
        key: String,
        sourceId: UUID,
        sourceVersionId: UUID
    ) {
        val now = Instant.now()
        SourceIngestionClaimsTable.insert {
            it[id] = UUID.randomUUID()
            it[SourceIngestionClaimsTable.workspaceId] = workspaceId
            it[SourceIngestionClaimsTable.projectId] = projectId
            it[sourceType] = "GITHUB_REPO"
            it[ingestionKey] = key
            it[requestHash] = "sha256:${"c".repeat(64)}"
            it[status] = "complete"
            it[processingToken] = null
            it[leaseExpiresAt] = null
            it[SourceIngestionClaimsTable.sourceId] = sourceId
            it[SourceIngestionClaimsTable.sourceVersionId] = sourceVersionId
            it[createdAt] = now
            it[updatedAt] = now
        }
    }

    private suspend fun createFixtureSource(
        repository: SourceRepository,
        workspaceId: UUID,
        projectId: UUID,
        userId: UUID,
        suffix: String
    ) = repository.createTextSourceAtomic(
        workspaceId = workspaceId,
        projectId = projectId,
        sourceType = "TEXT_PASTE",
        displayName = suffix,
        externalRefJson = "{}",
        createdBy = userId,
        contentHash = "sha256:" + Idempotency.sha256("content-$suffix"),
        metadataJson = """{"kind":"text","chunkCount":1}""",
        chunks = listOf(TextChunk(0, null, 1, 1, "content-$suffix", 1)),
        ingestionKey = "text:postgres-$suffix",
        requestHash = "sha256:" + Idempotency.sha256("request-$suffix")
    )
}
