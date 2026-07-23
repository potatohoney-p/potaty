/*
 * Copyright (c) 2026, Potaty
 *
 * Tenant/project integrity checks and explicit development identity bootstrap.
 */

package com.potaty.backend.persistence

import com.potaty.backend.auth.WorkspaceRole
import com.potaty.backend.config.AuthConfig
import java.time.Instant
import java.util.UUID
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select

data class ProjectIdentity(
    val id: UUID,
    val workspaceId: UUID,
    val name: String,
    val slug: String
)

class IdentityRepository(private val txc: TransactionContext) {

    suspend fun findProject(workspaceId: UUID, projectId: UUID): ProjectIdentity? = txc.tx {
        ProjectsTable.select {
            (ProjectsTable.id eq projectId) and
                (ProjectsTable.workspaceId eq workspaceId) and
                ProjectsTable.deletedAt.isNull()
        }
            .limit(1)
            .map { row ->
                ProjectIdentity(
                    id = row[ProjectsTable.id],
                    workspaceId = row[ProjectsTable.workspaceId],
                    name = row[ProjectsTable.name],
                    slug = row[ProjectsTable.slug]
                )
            }
            .singleOrNull()
    }

    suspend fun isActiveMember(workspaceId: UUID, userId: UUID): Boolean = txc.tx {
        val workspaceActive =
            WorkspacesTable.select {
                (WorkspacesTable.id eq workspaceId) and WorkspacesTable.deletedAt.isNull()
            }
                .limit(1)
                .any()
        val userActive =
            UsersTable.select { (UsersTable.id eq userId) and UsersTable.deletedAt.isNull() }
                .limit(1)
                .any()
        workspaceActive &&
            userActive &&
            WorkspaceMembersTable.select {
                (WorkspaceMembersTable.workspaceId eq workspaceId) and
                    (WorkspaceMembersTable.userId eq userId)
            }
                .limit(1)
                .any()
    }

    suspend fun activeMemberRole(workspaceId: UUID, userId: UUID): WorkspaceRole? = txc.tx {
        val workspaceActive =
            WorkspacesTable.select {
                (WorkspacesTable.id eq workspaceId) and WorkspacesTable.deletedAt.isNull()
            }
                .limit(1)
                .any()
        val userActive =
            UsersTable.select { (UsersTable.id eq userId) and UsersTable.deletedAt.isNull() }
                .limit(1)
                .any()
        if (!workspaceActive || !userActive) {
            null
        } else {
            WorkspaceMembersTable.slice(WorkspaceMembersTable.role)
                .select {
                    (WorkspaceMembersTable.workspaceId eq workspaceId) and
                        (WorkspaceMembersTable.userId eq userId)
                }
                .limit(1)
                .mapNotNull { row ->
                    runCatching { WorkspaceRole.fromWire(row[WorkspaceMembersTable.role]) }
                        .getOrNull()
                }
                .singleOrNull()
        }
    }

    suspend fun requireProject(workspaceId: UUID, projectId: UUID) {
        if (findProject(workspaceId, projectId) == null) {
            throw TenantIntegrityException(
                "Project is not available in the authenticated workspace"
            )
        }
    }

    suspend fun requireMember(workspaceId: UUID, userId: UUID?) {
        if (userId != null && !isActiveMember(workspaceId, userId)) {
            throw TenantIntegrityException(
                "User is not an active member of the authenticated workspace"
            )
        }
    }

    /**
     * Proves every requested source version belongs to the project in the same workspace. This is
     * the enqueue boundary guard that prevents a caller from mixing source IDs across projects.
     */
    suspend fun requireSourceVersionsInProject(
        workspaceId: UUID,
        projectId: UUID,
        sourceVersionIds: Collection<UUID>
    ) {
        val requested = sourceVersionIds.toSet()
        if (requested.isEmpty()) {
            throw TenantIntegrityException("At least one source version is required")
        }
        val matched = txc.tx {
            val projectSourceIds =
                SourcesTable.slice(SourcesTable.id)
                    .select {
                        (SourcesTable.workspaceId eq workspaceId) and
                            (SourcesTable.projectId eq projectId) and
                            SourcesTable.deletedAt.isNull()
                    }
                    .map { it[SourcesTable.id] }
            if (projectSourceIds.isEmpty()) {
                emptySet()
            } else {
                SourceVersionsTable.slice(SourceVersionsTable.id)
                    .select {
                        (SourceVersionsTable.workspaceId eq workspaceId) and
                            (SourceVersionsTable.id inList requested) and
                            (SourceVersionsTable.sourceId inList projectSourceIds)
                    }
                    .map { it[SourceVersionsTable.id] }
                    .toSet()
            }
        }
        if (matched != requested) {
            throw TenantIntegrityException(
                "One or more source versions are not available in the requested project"
            )
        }
    }

    suspend fun requireSource(workspaceId: UUID, sourceId: UUID) =
        requireOwnedRow(
            SourcesTable.select {
                (SourcesTable.id eq sourceId) and
                    (SourcesTable.workspaceId eq workspaceId) and
                    SourcesTable.deletedAt.isNull()
            },
            "Source is not available in the authenticated workspace"
        )

    suspend fun requireSourceVersion(workspaceId: UUID, sourceVersionId: UUID) =
        requireOwnedRow(
            SourceVersionsTable.select {
                (SourceVersionsTable.id eq sourceVersionId) and
                    (SourceVersionsTable.workspaceId eq workspaceId)
            },
            "Source version is not available in the authenticated workspace"
        )

    suspend fun requireDiagram(workspaceId: UUID, diagramId: UUID) =
        requireOwnedRow(
            DiagramsTable.select {
                (DiagramsTable.id eq diagramId) and
                    (DiagramsTable.workspaceId eq workspaceId) and
                    DiagramsTable.deletedAt.isNull()
            },
            "Diagram is not available in the authenticated workspace"
        )

    suspend fun requireJob(workspaceId: UUID, jobId: UUID) =
        requireOwnedRow(
            JobsTable.select {
                (JobsTable.id eq jobId) and (JobsTable.workspaceId eq workspaceId)
            },
            "Job is not available in the authenticated workspace"
        )

    private suspend fun requireOwnedRow(query: org.jetbrains.exposed.sql.Query, message: String) {
        val present = txc.tx { query.limit(1).any() }
        if (!present) throw TenantIntegrityException(message)
    }
}

/** Intentionally maps to a tenant-safe 404/403 at the HTTP boundary; no foreign IDs are exposed. */
class TenantIntegrityException(message: String) : RuntimeException(message)

internal suspend fun bootstrapDevelopmentIdentity(
    txc: TransactionContext,
    auth: AuthConfig
) {
    check(auth.devAuthEnabled) { "Development identity bootstrap is only allowed in dev auth mode" }
    val workspaceId = UUID.fromString(auth.devWorkspaceId)
    val userId = UUID.fromString(auth.devUserId)
    val projectId = UUID.fromString(auth.devProjectId)
    val now = Instant.now()

    txc.tx {
        if (!WorkspacesTable.select { WorkspacesTable.id eq workspaceId }.limit(1).any()) {
            WorkspacesTable.insert {
                it[id] = workspaceId
                it[name] = "Potaty local workspace"
                it[slug] = "potaty-local"
                it[plan] = "development"
                it[createdAt] = now
                it[updatedAt] = now
            }
        }
        if (!UsersTable.select { UsersTable.id eq userId }.limit(1).any()) {
            UsersTable.insert {
                it[id] = userId
                it[email] = "local-dev@potaty.invalid"
                it[displayName] = "Local developer"
                it[createdAt] = now
                it[updatedAt] = now
            }
        }
        if (
            !WorkspaceMembersTable.select {
                (WorkspaceMembersTable.workspaceId eq workspaceId) and
                    (WorkspaceMembersTable.userId eq userId)
            }
                .limit(1)
                .any()
        ) {
            WorkspaceMembersTable.insert {
                it[WorkspaceMembersTable.workspaceId] = workspaceId
                it[WorkspaceMembersTable.userId] = userId
                it[role] = WorkspaceRole.OWNER.name.lowercase()
                it[createdAt] = now
            }
        }

        val projectWorkspace =
            ProjectsTable.select { ProjectsTable.id eq projectId }
                .limit(1)
                .map { it[ProjectsTable.workspaceId] }
                .singleOrNull()
        check(projectWorkspace == null || projectWorkspace == workspaceId) {
            "Configured development project ID already belongs to a different workspace"
        }
        if (projectWorkspace == null) {
            ProjectsTable.insert {
                it[id] = projectId
                it[ProjectsTable.workspaceId] = workspaceId
                it[name] = "Local playground"
                it[slug] = "local-playground"
                it[description] = "Deterministic project seeded only for explicit development auth"
                it[createdAt] = now
                it[updatedAt] = now
            }
        }
    }
}
