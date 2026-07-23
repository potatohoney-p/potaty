/*
 * Copyright (c) 2026, Potaty
 *
 * Workspace-scoped principal and tenant context. Plan section 20.5: every repository
 * method requires an explicit TenantContext; there is no un-scoped data access path.
 */

package com.potaty.backend.auth

import io.ktor.server.application.ApplicationCall
import io.ktor.util.AttributeKey

/**
 * The authenticated caller, always bound to exactly one workspace. Repositories take this
 * (or its workspaceId) so cross-tenant access is structurally impossible.
 */
data class TenantContext(
    val workspaceId: String,
    val userId: String,
    val role: WorkspaceRole
)

enum class WorkspaceRole {
    OWNER,
    ADMIN,
    EDITOR,
    VIEWER;

    companion object {
        fun fromWire(value: String): WorkspaceRole =
            values().firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: error("Unknown workspace role: $value")
    }
}

/** Ktor attribute key under which the resolved TenantContext is stored per-call. */
val TenantContextKey: AttributeKey<TenantContext> = AttributeKey("TenantContext")

/**
 * Returns the TenantContext for the current call or throws. Authentication middleware is
 * responsible for resolving and attaching it (see SessionAuth).
 */
fun ApplicationCall.tenant(): TenantContext =
    attributes.getOrNull(TenantContextKey)
        ?: throw NotAuthenticatedException("Request is missing an authenticated tenant context")

fun ApplicationCall.tenantOrNull(): TenantContext? = attributes.getOrNull(TenantContextKey)

class NotAuthenticatedException(message: String) : RuntimeException(message)
