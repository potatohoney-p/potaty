/*
 * Copyright (c) 2026, Potaty
 *
 * H2 round-trip tests for RetentionService (plan 20.6 data deletion). Seeds rows across the
 * tenant tables (sources / versions / chunks / diagrams / versions / jobs / usage) for two
 * workspaces, deletes one workspace's data, and asserts: (1) the target workspace's rows are
 * gone across every table, and (2) the OTHER workspace's rows are untouched (tenant-scoped
 * deletion, plan 20.5). Exercises the service directly over a Database built from testConfig().
 */

package com.potaty.backend

import com.potaty.backend.ops.RetentionService
import com.potaty.backend.persistence.AuditEventsTable
import com.potaty.backend.persistence.CostReservationsTable
import com.potaty.backend.persistence.Database
import com.potaty.backend.persistence.ExtractedEntitiesTable
import com.potaty.backend.persistence.ExtractedRelationsTable
import com.potaty.backend.persistence.GitHubConnectStatesTable
import com.potaty.backend.persistence.GitHubInstallationsTable
import com.potaty.backend.persistence.LlmCredentialsTable
import com.potaty.backend.persistence.ProjectsTable
import com.potaty.backend.persistence.RenderingsTable
import com.potaty.backend.persistence.SourceIngestionClaimsTable
import com.potaty.backend.persistence.TransactionContext
import com.potaty.backend.persistence.WorkspacesTable
import com.potaty.backend.persistence.repositories.DiagramRepository
import com.potaty.backend.persistence.repositories.JobRepository
import com.potaty.backend.persistence.repositories.SourceRepository
import com.potaty.backend.source.TextChunk
import com.potaty.backend.usage.UsageRecorder
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.insert

class RetentionServiceTest {

    private class Fixture {
        val database = Database.connect(testConfig().database)
        val txc = TransactionContext(database.exposed)
        val sources = SourceRepository(txc)
        val diagrams = DiagramRepository(txc)
        val jobs = JobRepository(txc)
        val usage = UsageRecorder(txc)
        val retention = RetentionService(txc)
    }

    /** Seeds one source(+version+chunk), one diagram(+version), one job(+event), one usage event. */
    private suspend fun seedWorkspace(f: Fixture, workspaceId: UUID) {
        val projectId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val identityNow = Instant.now()

        // Retention intentionally preserves control-plane identity/project rows. Seed the project
        // so the source-ingestion claim exercises the same composite tenant FK as production.
        f.txc.tx {
            WorkspacesTable.insert {
                it[id] = workspaceId
                it[name] = "Fixture workspace"
                it[slug] = "fixture-$workspaceId"
                it[plan] = "test"
                it[createdAt] = identityNow
                it[updatedAt] = identityNow
            }
            ProjectsTable.insert {
                it[id] = projectId
                it[ProjectsTable.workspaceId] = workspaceId
                it[name] = "Fixture project"
                it[slug] = "fixture-project-$projectId"
                it[description] = null
                it[createdAt] = identityNow
                it[updatedAt] = identityNow
            }
        }

        val source = f.sources.createSource(
            workspaceId,
            projectId,
            "TEXT_PASTE",
            "demo",
            "{}",
            userId
        )
        val version = f.sources.createVersion(
            workspaceId,
            source.id,
            "sha256:abc",
            null,
            null,
            "{}"
        )
        f.sources.saveChunks(
            workspaceId,
            version.id,
            listOf(
                TextChunk(
                    chunkIndex = 0,
                    path = null,
                    startLine = 1,
                    endLine = 1,
                    text = "A -> B",
                    tokenCount = 3
                )
            )
        )

        val diagram = f.diagrams.createDiagram(
            workspaceId,
            projectId,
            "title",
            "architecture",
            userId
        )
        val diagramVersion = f.diagrams.appendVersion(
            workspaceId, diagram.id, "generation",
            irJson = "{}", validationReportJson = "{}", evidenceCoverageJson = "{}",
            sourceSnapshotJson = "[]", modelTraceJson = "[]",
            rendererVersion = "r1", layoutEngineVersion = "l1", createdBy = userId
        )

        val job = f.jobs.enqueue(
            workspaceId = workspaceId,
            projectId = projectId,
            jobType = "DIAGRAM_GENERATION",
            idempotencyKey = "k-${UUID.randomUUID()}",
            inputJson = "{}",
            createdBy = userId
        )
        f.jobs.recordEvent(workspaceId, job.id, "STAGE_PROGRESS", "extract", "started")

        f.usage.record(
            workspaceId = workspaceId,
            jobId = job.id,
            provider = "openai",
            model = "gpt-4o-mini",
            stage = "extract",
            inputTokens = 100,
            outputTokens = 50,
            estimatedCostUsd = 0.01
        )

        f.txc.tx {
            val installationId =
                kotlin.math.abs(UUID.randomUUID().mostSignificantBits).coerceAtLeast(1)
            val now = Instant.now()
            SourceIngestionClaimsTable.insert {
                it[id] = UUID.randomUUID()
                it[SourceIngestionClaimsTable.workspaceId] = workspaceId
                it[SourceIngestionClaimsTable.projectId] = projectId
                it[sourceType] = "TEXT_PASTE"
                it[ingestionKey] = "retention:${UUID.randomUUID()}"
                it[requestHash] = "sha256:${"a".repeat(64)}"
                it[status] = "processing"
                it[processingToken] = UUID.randomUUID()
                it[leaseExpiresAt] = now.plusSeconds(300)
                it[sourceId] = null
                it[sourceVersionId] = null
                it[createdAt] = now
                it[updatedAt] = now
            }
            CostReservationsTable.insert {
                it[CostReservationsTable.id] = UUID.randomUUID()
                it[CostReservationsTable.workspaceId] = workspaceId
                it[CostReservationsTable.reservationKey] = "fixture-${UUID.randomUUID()}"
                it[CostReservationsTable.jobId] = job.id
                it[CostReservationsTable.amountUsd] = 0.05
                it[CostReservationsTable.createdAt] = now
                it[CostReservationsTable.expiresAt] = now.plusSeconds(3_600)
                it[CostReservationsTable.releasedAt] = null
            }
            GitHubInstallationsTable.insert {
                it[GitHubInstallationsTable.id] = UUID.randomUUID()
                it[GitHubInstallationsTable.workspaceId] = workspaceId
                it[GitHubInstallationsTable.connectedByUserId] = userId
                it[GitHubInstallationsTable.installationId] = installationId
                it[GitHubInstallationsTable.appId] = 123L
                it[GitHubInstallationsTable.accountId] = installationId
                it[GitHubInstallationsTable.accountLogin] = "fixture-$installationId"
                it[GitHubInstallationsTable.accountType] = "Organization"
                it[GitHubInstallationsTable.installationHtmlUrl] =
                    "https://github.com/settings/installations/$installationId"
                it[GitHubInstallationsTable.githubUserId] = installationId
                it[GitHubInstallationsTable.githubLogin] = "fixture-user"
                it[GitHubInstallationsTable.activeKey] = "123:$installationId"
                it[GitHubInstallationsTable.createdAt] = now
                it[GitHubInstallationsTable.updatedAt] = now
                it[GitHubInstallationsTable.disconnectedAt] = null
            }
            GitHubConnectStatesTable.insert {
                it[GitHubConnectStatesTable.nonceHash] = "nonce-${UUID.randomUUID()}"
                it[GitHubConnectStatesTable.workspaceId] = workspaceId
                it[GitHubConnectStatesTable.userId] = userId
                it[GitHubConnectStatesTable.phase] = "OAUTH"
                it[GitHubConnectStatesTable.candidateInstallationId] = installationId
                it[GitHubConnectStatesTable.pkceVerifier] = "fixture-verifier"
                it[GitHubConnectStatesTable.expiresAt] = now.plusSeconds(900)
                it[GitHubConnectStatesTable.consumedAt] = null
                it[GitHubConnectStatesTable.createdAt] = now
            }
            RenderingsTable.insert {
                it[RenderingsTable.id] = UUID.randomUUID()
                it[RenderingsTable.workspaceId] = workspaceId
                it[RenderingsTable.diagramVersionId] = diagramVersion.id
                it[RenderingsTable.format] = "mermaid"
                it[RenderingsTable.objectKey] = null
                it[RenderingsTable.contentText] = "flowchart LR"
                it[RenderingsTable.contentHash] = "sha256:rendering"
                it[RenderingsTable.renderStatus] = "ready"
                it[RenderingsTable.renderWarnings] = "[]"
                it[RenderingsTable.createdAt] = now
            }
            LlmCredentialsTable.insert {
                it[LlmCredentialsTable.id] = UUID.randomUUID()
                it[LlmCredentialsTable.workspaceId] = workspaceId
                it[LlmCredentialsTable.provider] = "mock"
                it[LlmCredentialsTable.credentialType] = "api_key"
                it[LlmCredentialsTable.encryptedSecretRef] = "fixture-encrypted-ref"
                it[LlmCredentialsTable.label] = "fixture"
                it[LlmCredentialsTable.status] = "active"
                it[LlmCredentialsTable.metadata] = "{}"
                it[LlmCredentialsTable.createdBy] = userId
                it[LlmCredentialsTable.createdAt] = now
                it[LlmCredentialsTable.updatedAt] = now
                it[LlmCredentialsTable.lastUsedAt] = null
                it[LlmCredentialsTable.revokedAt] = null
            }
            AuditEventsTable.insert {
                it[AuditEventsTable.id] = UUID.randomUUID()
                it[AuditEventsTable.workspaceId] = workspaceId
                it[AuditEventsTable.actorUserId] = userId
                it[AuditEventsTable.eventType] = "fixture"
                it[AuditEventsTable.resourceType] = "workspace"
                it[AuditEventsTable.resourceId] = workspaceId
                it[AuditEventsTable.ip] = "127.0.0.1"
                it[AuditEventsTable.userAgent] = "test"
                it[AuditEventsTable.payload] = "{}"
                it[AuditEventsTable.createdAt] = now
            }
            ExtractedEntitiesTable.insert {
                it[ExtractedEntitiesTable.id] = UUID.randomUUID()
                it[ExtractedEntitiesTable.workspaceId] = workspaceId
                it[ExtractedEntitiesTable.projectId] = projectId
                it[ExtractedEntitiesTable.sourceVersionId] = version.id
                it[ExtractedEntitiesTable.entityKey] = "a"
                it[ExtractedEntitiesTable.type] = "service"
                it[ExtractedEntitiesTable.name] = "A"
                it[ExtractedEntitiesTable.canonicalName] = "a"
                it[ExtractedEntitiesTable.summary] = null
                it[ExtractedEntitiesTable.confidence] = 0.9.toBigDecimal()
                it[ExtractedEntitiesTable.evidenceChunkIds] = "[]"
                it[ExtractedEntitiesTable.evidence] = "[]"
                it[ExtractedEntitiesTable.metadata] = "{}"
                it[ExtractedEntitiesTable.createdAt] = now
            }
            ExtractedRelationsTable.insert {
                it[ExtractedRelationsTable.id] = UUID.randomUUID()
                it[ExtractedRelationsTable.workspaceId] = workspaceId
                it[ExtractedRelationsTable.projectId] = projectId
                it[ExtractedRelationsTable.sourceVersionId] = version.id
                it[ExtractedRelationsTable.fromEntityKey] = "a"
                it[ExtractedRelationsTable.toEntityKey] = "b"
                it[ExtractedRelationsTable.type] = "calls"
                it[ExtractedRelationsTable.label] = "calls"
                it[ExtractedRelationsTable.confidence] = 0.9.toBigDecimal()
                it[ExtractedRelationsTable.evidenceChunkIds] = "[]"
                it[ExtractedRelationsTable.evidence] = "[]"
                it[ExtractedRelationsTable.metadata] = "{}"
                it[ExtractedRelationsTable.createdAt] = now
            }
        }
    }

    @Test
    fun deletesAllOfAWorkspacesDataAcrossTables() = runBlocking {
        val f = Fixture()
        val ws = UUID.randomUUID()
        seedWorkspace(f, ws)

        // Sanity: usage exists before deletion.
        assertTrue(f.usage.sumCostThisMonth(ws) > 0.0)

        val report = f.retention.deleteWorkspaceData(ws)

        // Each table contributed at least one row to the purge.
        assertEquals(1, report.sources)
        assertEquals(1, report.sourceVersions)
        assertEquals(1, report.sourceChunks)
        assertEquals(1, report.sourceIngestionClaims)
        assertEquals(1, report.diagrams)
        assertEquals(1, report.diagramVersions)
        assertEquals(1, report.jobs)
        assertEquals(1, report.jobEvents)
        assertEquals(1, report.costReservations)
        assertEquals(1, report.usageEvents)
        assertEquals(1, report.githubConnectStates)
        assertEquals(1, report.githubInstallations)
        assertEquals(1, report.renderings)
        assertEquals(1, report.llmCredentials)
        assertEquals(1, report.auditEvents)
        assertEquals(1, report.extractedEntities)
        assertEquals(1, report.extractedRelations)
        assertTrue(report.total >= 17)

        // Re-running yields nothing left.
        val second = f.retention.deleteWorkspaceData(ws)
        assertEquals(0, second.total)
        assertEquals(0.0, f.usage.sumCostThisMonth(ws))
    }

    @Test
    fun deletionIsTenantScoped() = runBlocking {
        val f = Fixture()
        val target = UUID.randomUUID()
        val keep = UUID.randomUUID()
        seedWorkspace(f, target)
        seedWorkspace(f, keep)

        f.retention.deleteWorkspaceData(target)

        // The target is purged; the other workspace is untouched.
        assertEquals(0.0, f.usage.sumCostThisMonth(target))
        assertTrue(f.usage.sumCostThisMonth(keep) > 0.0, "kept workspace usage survives")

        // Purging the kept workspace still finds its full row set (proves nothing leaked across).
        val keepReport = f.retention.deleteWorkspaceData(keep)
        assertEquals(1, keepReport.sources)
        assertEquals(1, keepReport.sourceVersions)
        assertEquals(1, keepReport.sourceChunks)
        assertEquals(1, keepReport.sourceIngestionClaims)
        assertEquals(1, keepReport.diagrams)
        assertEquals(1, keepReport.diagramVersions)
        assertEquals(1, keepReport.jobs)
        assertEquals(1, keepReport.jobEvents)
        assertEquals(1, keepReport.costReservations)
        assertEquals(1, keepReport.usageEvents)
        assertEquals(1, keepReport.githubConnectStates)
        assertEquals(1, keepReport.githubInstallations)
        assertEquals(1, keepReport.renderings)
        assertEquals(1, keepReport.llmCredentials)
        assertEquals(1, keepReport.auditEvents)
        assertEquals(1, keepReport.extractedEntities)
        assertEquals(1, keepReport.extractedRelations)
    }
}
