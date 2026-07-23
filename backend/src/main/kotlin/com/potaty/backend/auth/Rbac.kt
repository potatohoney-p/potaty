/*
 * Copyright (c) 2026, Potaty
 *
 * Role-based access control over workspace-scoped resources. Permission checks operate on
 * the per-call TenantContext; failures surface as 403 via StatusPages.
 */

package com.potaty.backend.auth

/**
 * Coarse-grained permissions. Kept intentionally small for the scaffold; extend per the plan's
 * resource matrix as routes are implemented.
 */
enum class Permission {
    VIEW_DIAGRAM,
    EDIT_DIAGRAM,
    CREATE_SOURCE,
    RUN_JOB,
    MANAGE_CREDENTIALS,
    PUBLISH_PR,
    MANAGE_WORKSPACE
}

object Rbac {

    private val grants: Map<WorkspaceRole, Set<Permission>> =
        mapOf(
            // values() (not entries): entries needs Kotlin 1.9 language version; project is on 1.8.
            WorkspaceRole.OWNER to Permission.values().toSet(),
            WorkspaceRole.ADMIN to
                setOf(
                    Permission.VIEW_DIAGRAM,
                    Permission.EDIT_DIAGRAM,
                    Permission.CREATE_SOURCE,
                    Permission.RUN_JOB,
                    Permission.MANAGE_CREDENTIALS,
                    Permission.PUBLISH_PR
                ),
            WorkspaceRole.EDITOR to
                setOf(
                    Permission.VIEW_DIAGRAM,
                    Permission.EDIT_DIAGRAM,
                    Permission.CREATE_SOURCE,
                    Permission.RUN_JOB
                ),
            WorkspaceRole.VIEWER to setOf(Permission.VIEW_DIAGRAM)
        )

    fun has(role: WorkspaceRole, permission: Permission): Boolean =
        grants[role]?.contains(permission) ?: false

    /** Throws [ForbiddenException] if the tenant lacks [permission]. */
    fun require(tenant: TenantContext, permission: Permission) {
        if (!has(tenant.role, permission)) {
            throw ForbiddenException(
                "Role ${tenant.role} lacks permission $permission " +
                    "in workspace ${tenant.workspaceId}"
            )
        }
    }
}

class ForbiddenException(message: String) : RuntimeException(message)
