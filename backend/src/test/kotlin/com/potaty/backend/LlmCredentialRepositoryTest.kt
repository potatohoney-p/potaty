/*
 * Copyright (c) 2026, Potaty
 *
 * Credential lookup is a security boundary: only active, workspace-owned, provider/type-matched
 * records may reach a billable provider.
 */

package com.potaty.backend

import com.potaty.backend.llm.auth.EnvelopeCredentialStore
import com.potaty.backend.llm.provider.ProviderId
import com.potaty.backend.persistence.Database
import com.potaty.backend.persistence.LlmCredentialsTable
import com.potaty.backend.persistence.TransactionContext
import com.potaty.backend.persistence.bootstrapDevelopmentIdentity
import com.potaty.backend.persistence.repositories.LlmCredentialRepository
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update

class LlmCredentialRepositoryTest {

    @Test
    fun resolvesOnlyActiveWorkspaceOwnedOpenAiApiKeyRows() = runBlocking {
        val config = testConfig()
        val database = Database.connect(config.database)
        try {
            val txc = TransactionContext(database.exposed)
            bootstrapDevelopmentIdentity(txc, config.auth)
            val workspaceId = UUID.fromString(config.auth.devWorkspaceId)
            val userId = UUID.fromString(config.auth.devUserId)
            val credentialId = UUID.randomUUID()
            val secret = "sk-" + "K".repeat(40)
            val encrypted =
                EnvelopeCredentialStore(config.security.credentialMasterKeyRef)
                    .seal(workspaceId.toString(), secret)
                    .value
            val now = Instant.now()

            txc.tx {
                LlmCredentialsTable.insert {
                    it[id] = credentialId
                    it[LlmCredentialsTable.workspaceId] = workspaceId
                    it[provider] = "openai"
                    it[credentialType] = "api_key"
                    it[encryptedSecretRef] = encrypted
                    it[label] = "transcription fixture"
                    it[status] = "active"
                    it[metadata] = "{}"
                    it[createdBy] = userId
                    it[createdAt] = now
                    it[updatedAt] = now
                    it[lastUsedAt] = null
                    it[revokedAt] = null
                }
            }

            val repository = LlmCredentialRepository(txc)
            val resolved =
                repository.findActiveApiKey(workspaceId, credentialId, ProviderId.OPENAI)
            assertNotNull(resolved)
            assertEquals(credentialId.toString(), resolved.id)
            assertEquals(workspaceId.toString(), resolved.workspaceId)
            assertEquals(encrypted, resolved.encryptedApiKeyRef)
            assertTrue(secret !in resolved.encryptedApiKeyRef)
            assertNull(
                repository.findActiveApiKey(
                    UUID.randomUUID(),
                    credentialId,
                    ProviderId.OPENAI
                )
            )
            assertNull(
                repository.findActiveApiKey(workspaceId, credentialId, ProviderId.ANTHROPIC)
            )

            val lastUsed = txc.tx {
                LlmCredentialsTable.select { LlmCredentialsTable.id eq credentialId }
                    .single()[LlmCredentialsTable.lastUsedAt]
            }
            assertNotNull(lastUsed)

            txc.tx {
                LlmCredentialsTable.update({ LlmCredentialsTable.id eq credentialId }) {
                    it[status] = "revoked"
                    it[revokedAt] = Instant.now()
                }
            }
            assertNull(
                repository.findActiveApiKey(workspaceId, credentialId, ProviderId.OPENAI)
            )
        } finally {
            database.close()
        }
    }
}
