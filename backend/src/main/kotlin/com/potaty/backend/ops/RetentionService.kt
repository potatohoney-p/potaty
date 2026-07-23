/*
 * Copyright (c) 2026, Potaty
 *
 * Tenant-scoped data deletion (plan 20.6 "data deletion / right to erasure"). Deletes all
 * data-bearing rows a single workspace owns, inside one Exposed transaction so the purge is
 * atomic. Workspace, membership, user, and project control-plane rows remain for a separate,
 * audited account-closure workflow. EVERY delete filters by workspaceId — there is no path that
 * can touch another tenant's data (plan 20.5). Deletion order is child-before-parent so it is safe
 * where PostgreSQL foreign keys exist; H2 keeps the same order for parity.
 *
 * Returns a per-table count of deleted rows so the caller can log/audit the erasure.
 */

package com.potaty.backend.ops

import com.potaty.backend.persistence.AuditEventsTable
import com.potaty.backend.persistence.CostReservationsTable
import com.potaty.backend.persistence.DiagramVersionsTable
import com.potaty.backend.persistence.DiagramsTable
import com.potaty.backend.persistence.ExtractedEntitiesTable
import com.potaty.backend.persistence.ExtractedRelationsTable
import com.potaty.backend.persistence.GitHubConnectStatesTable
import com.potaty.backend.persistence.GitHubInstallationsTable
import com.potaty.backend.persistence.JobEventsTable
import com.potaty.backend.persistence.JobsTable
import com.potaty.backend.persistence.LlmCredentialsTable
import com.potaty.backend.persistence.RenderingsTable
import com.potaty.backend.persistence.SourceChunksTable
import com.potaty.backend.persistence.SourceIngestionClaimsTable
import com.potaty.backend.persistence.SourceVersionsTable
import com.potaty.backend.persistence.SourcesTable
import com.potaty.backend.persistence.TransactionContext
import com.potaty.backend.persistence.UsageEventsTable
import java.util.UUID
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere

/** Per-table deletion counts for one workspace purge (plan 20.6 audit). */
data class RetentionReport(
    val jobEvents: Int,
    val costReservations: Int,
    val jobs: Int,
    val diagramVersions: Int,
    val diagrams: Int,
    val sourceChunks: Int,
    val sourceIngestionClaims: Int,
    val sourceVersions: Int,
    val sources: Int,
    val extractedRelations: Int,
    val extractedEntities: Int,
    val usageEvents: Int,
    val githubConnectStates: Int,
    val githubInstallations: Int,
    val renderings: Int,
    val llmCredentials: Int,
    val auditEvents: Int
) {
    val total: Int
        get() =
            jobEvents +
                costReservations +
                jobs +
                diagramVersions +
                diagrams +
                sourceChunks +
                sourceIngestionClaims +
                sourceVersions +
                sources +
                extractedRelations +
                extractedEntities +
                usageEvents +
                githubConnectStates +
                githubInstallations +
                renderings +
                llmCredentials +
                auditEvents
}

class RetentionService(private val txc: TransactionContext) {

    /**
     * Deletes every data-bearing row owned by [workspaceId] in a single transaction. Identity and
     * project rows intentionally remain for the separate workspace-closure workflow.
     */
    suspend fun deleteWorkspaceData(workspaceId: UUID): RetentionReport = txc.tx {
        // Children first.
        val githubConnectStates = GitHubConnectStatesTable.deleteWhere {
            GitHubConnectStatesTable.workspaceId eq workspaceId
        }
        val githubInstallations = GitHubInstallationsTable.deleteWhere {
            GitHubInstallationsTable.workspaceId eq workspaceId
        }
        val jobEvents = JobEventsTable.deleteWhere { JobEventsTable.workspaceId eq workspaceId }
        // usage_events and cost_reservations have optional job FKs, so both precede jobs.
        val costReservations = CostReservationsTable.deleteWhere {
            CostReservationsTable.workspaceId eq workspaceId
        }
        val usageEvents = UsageEventsTable.deleteWhere {
            UsageEventsTable.workspaceId eq workspaceId
        }
        val jobs = JobsTable.deleteWhere { JobsTable.workspaceId eq workspaceId }
        val renderings = RenderingsTable.deleteWhere {
            RenderingsTable.workspaceId eq workspaceId
        }
        val diagramVersions = DiagramVersionsTable.deleteWhere {
            DiagramVersionsTable.workspaceId eq workspaceId
        }
        val diagrams = DiagramsTable.deleteWhere { DiagramsTable.workspaceId eq workspaceId }
        // Extraction rows reference source versions in PostgreSQL.
        val extractedRelations = ExtractedRelationsTable.deleteWhere {
            ExtractedRelationsTable.workspaceId eq workspaceId
        }
        val extractedEntities = ExtractedEntitiesTable.deleteWhere {
            ExtractedEntitiesTable.workspaceId eq workspaceId
        }
        val sourceChunks = SourceChunksTable.deleteWhere {
            SourceChunksTable.workspaceId eq workspaceId
        }
        val sourceIngestionClaims = SourceIngestionClaimsTable.deleteWhere {
            SourceIngestionClaimsTable.workspaceId eq workspaceId
        }
        val sourceVersions = SourceVersionsTable.deleteWhere {
            SourceVersionsTable.workspaceId eq workspaceId
        }
        val sources = SourcesTable.deleteWhere { SourcesTable.workspaceId eq workspaceId }
        val llmCredentials = LlmCredentialsTable.deleteWhere {
            LlmCredentialsTable.workspaceId eq workspaceId
        }
        val auditEvents = AuditEventsTable.deleteWhere {
            AuditEventsTable.workspaceId eq workspaceId
        }

        RetentionReport(
            jobEvents = jobEvents,
            costReservations = costReservations,
            jobs = jobs,
            diagramVersions = diagramVersions,
            diagrams = diagrams,
            sourceChunks = sourceChunks,
            sourceIngestionClaims = sourceIngestionClaims,
            sourceVersions = sourceVersions,
            sources = sources,
            extractedRelations = extractedRelations,
            extractedEntities = extractedEntities,
            usageEvents = usageEvents,
            githubConnectStates = githubConnectStates,
            githubInstallations = githubInstallations,
            renderings = renderings,
            llmCredentials = llmCredentials,
            auditEvents = auditEvents
        )
    }
}
