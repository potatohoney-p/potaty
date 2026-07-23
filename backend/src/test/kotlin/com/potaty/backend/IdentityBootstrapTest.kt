/*
 * Copyright (c) 2026, Potaty
 */

package com.potaty.backend

import com.potaty.backend.auth.JwtSessionStore
import com.potaty.backend.auth.MembershipBoundSessionStore
import com.potaty.backend.auth.TenantContext
import com.potaty.backend.auth.WorkspaceRole
import com.potaty.backend.config.AppConfig
import com.potaty.backend.config.AuthMode
import com.potaty.backend.config.EnvConfig
import com.potaty.backend.persistence.TenantIntegrityException
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class IdentityBootstrapTest {

    @Test
    fun explicitDevAuthBootstrapsDeterministicWorkspaceMemberAndProject() = runBlocking {
        val config = testConfig()
        val graph = AppGraph.create(config)
        try {
            val workspaceId = UUID.fromString(config.auth.devWorkspaceId)
            val userId = UUID.fromString(config.auth.devUserId)
            val projectId = UUID.fromString(config.auth.devProjectId)

            val project = graph.identities.findProject(workspaceId, projectId)
            assertNotNull(project)
            assertEquals("local-playground", project.slug)
            assertTrue(graph.identities.isActiveMember(workspaceId, userId))
            assertNull(graph.identities.findProject(UUID.randomUUID(), projectId))

            val jwtConfig =
                config.auth.copy(
                    mode = AuthMode.JWT,
                    jwtIssuer = "https://issuer.potaty.test",
                    jwtAudience = "potaty-api",
                    jwtSecret = "a-production-grade-test-secret-32-bytes-long"
                )
            val membershipBound =
                MembershipBoundSessionStore(JwtSessionStore(jwtConfig)) { context ->
                    graph.identities.activeMemberRole(
                        UUID.fromString(context.workspaceId),
                        UUID.fromString(context.userId)
                    ) == context.role
                }
            val owner =
                TenantContext(workspaceId.toString(), userId.toString(), WorkspaceRole.OWNER)
            assertEquals(owner, membershipBound.resolve(membershipBound.issue(owner)))
            val staleRole = owner.copy(role = WorkspaceRole.ADMIN)
            assertNull(membershipBound.resolve(membershipBound.issue(staleRole)))

            assertFailsWith<TenantIntegrityException> {
                graph.sources.createSource(
                    workspaceId = workspaceId,
                    projectId = UUID.randomUUID(),
                    sourceType = "TEXT_PASTE",
                    displayName = "wrong project",
                    externalRefJson = "{}",
                    createdBy = userId
                )
            }
        } finally {
            graph.stop()
        }
    }

    @Test
    fun jwtModeNeverBootstrapsDevelopmentIdentities() = runBlocking {
        val config =
            AppConfig.fromEnv(
                EnvConfig.of(
                    mapOf(
                        "POTATY_ENV" to "test",
                        "POTATY_DB_MODE" to "h2",
                        "POTATY_DB_URL" to
                            "jdbc:h2:mem:jwt_no_bootstrap_${UUID.randomUUID()};" +
                            "DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
                        "POTATY_AUTH_MODE" to "jwt",
                        "POTATY_JWT_ISSUER" to "https://issuer.potaty.test",
                        "POTATY_JWT_AUDIENCE" to "potaty-api",
                        "POTATY_JWT_SECRET" to "a-production-grade-test-secret-32-bytes-long"
                    )
                )
            )
        val graph = AppGraph.create(config)
        try {
            assertNull(
                graph.identities.findProject(
                    UUID.fromString(config.auth.devWorkspaceId),
                    UUID.fromString(config.auth.devProjectId)
                )
            )
        } finally {
            graph.stop()
        }
    }
}
