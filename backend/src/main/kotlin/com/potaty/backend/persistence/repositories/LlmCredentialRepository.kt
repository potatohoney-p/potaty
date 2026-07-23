/*
 * Copyright (c) 2026, Potaty
 *
 * Server-side lookup for tenant-owned LLM credentials. Callers submit only a credential id; the
 * encrypted secret reference never crosses the HTTP trust boundary. Revoked, expired, errored,
 * cross-workspace, wrong-provider, and wrong-type rows all fail closed as an indistinguishable
 * not-found result.
 */

package com.potaty.backend.persistence.repositories

import com.potaty.backend.llm.auth.ApiKeyCredential
import com.potaty.backend.llm.auth.CredentialStatus
import com.potaty.backend.llm.provider.ProviderId
import com.potaty.backend.persistence.LlmCredentialsTable
import com.potaty.backend.persistence.TransactionContext
import java.time.Instant
import java.util.UUID
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update

class LlmCredentialRepository(private val txc: TransactionContext) {

    /**
     * Resolves one active API-key credential inside [workspaceId]. The returned opaque reference is
     * still envelope-encrypted and can only be opened by CredentialStore with the same workspace
     * AAD. A successful lookup updates operational last-used telemetry in the same transaction.
     */
    suspend fun findActiveApiKey(
        workspaceId: UUID,
        credentialId: UUID,
        provider: ProviderId
    ): ApiKeyCredential? = txc.tx {
        val row =
            LlmCredentialsTable.select {
                (LlmCredentialsTable.id eq credentialId) and
                    (LlmCredentialsTable.workspaceId eq workspaceId) and
                    (LlmCredentialsTable.provider eq provider.name.lowercase()) and
                    (LlmCredentialsTable.credentialType eq "api_key") and
                    (LlmCredentialsTable.status eq "active") and
                    LlmCredentialsTable.revokedAt.isNull()
            }
                .limit(1)
                .singleOrNull()
                ?: return@tx null

        LlmCredentialsTable.update({
            (LlmCredentialsTable.id eq credentialId) and
                (LlmCredentialsTable.workspaceId eq workspaceId)
        }) {
            it[lastUsedAt] = Instant.now()
        }

        ApiKeyCredential(
            id = row[LlmCredentialsTable.id].toString(),
            workspaceId = row[LlmCredentialsTable.workspaceId].toString(),
            provider = provider,
            encryptedApiKeyRef = row[LlmCredentialsTable.encryptedSecretRef],
            label = row[LlmCredentialsTable.label],
            createdByUserId = row[LlmCredentialsTable.createdBy]?.toString()
                ?: "deployment-operator",
            status = CredentialStatus.ACTIVE
        )
    }
}
