/*
 * Copyright (c) 2026, Potaty
 *
 * SessionAuth: resolves the per-call [TenantContext] from an `Authorization: Bearer <token>`
 * header via a [SessionStore] and attaches it under [TenantContextKey]. This is the middleware
 * the routes depend on — without it every call to ApplicationCall.tenant() throws and the API
 * returns 401 (plan section 5, 20.5).
 *
 * The plugin is deliberately permissive: it ATTACHES a context when a valid token is present and
 * does nothing otherwise. Enforcement lives in the routes (call.tenant() + Rbac.require), so a
 * future public/unauthenticated endpoint needs no special-casing here.
 */

package com.potaty.backend.auth

import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.request.ApplicationRequest

class SessionAuthConfig {
    lateinit var store: SessionStore
}

private const val BEARER_PREFIX = "Bearer "

internal fun ApplicationRequest.bearerToken(): String? {
    val header = headers["Authorization"] ?: return null
    if (!header.startsWith(BEARER_PREFIX)) return null
    return header.substring(BEARER_PREFIX.length)
        .trim()
        .takeIf { it.isNotEmpty() && it.length <= 8_192 }
}

val SessionAuth =
    createApplicationPlugin(name = "SessionAuth", createConfiguration = ::SessionAuthConfig) {
        val store = pluginConfig.store
        onCall { call ->
            val token = call.request.bearerToken()
            if (token != null) {
                store.resolve(token)?.let { context ->
                    call.attributes.put(TenantContextKey, context)
                }
            }
        }
    }

/** Installs [SessionAuth] backed by [store]. Call in Application.module() before routing. */
fun Application.installSessionAuth(store: SessionStore) {
    install(SessionAuth) { this.store = store }
}
