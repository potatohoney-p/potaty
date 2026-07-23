/*
 * Copyright (c) 2026, Potaty
 *
 * Tenant-scoped persistence for sources / source versions. EVERY method takes workspaceId
 * and filters by it (plan 20.5). There is no method that can read across workspaces.
 */

package com.potaty.backend.persistence.repositories

import com.potaty.backend.persistence.IdentityRepository
import com.potaty.backend.persistence.ProjectsTable
import com.potaty.backend.persistence.SourceChunksTable
import com.potaty.backend.persistence.SourceIngestionClaimsTable
import com.potaty.backend.persistence.SourceVersionsTable
import com.potaty.backend.persistence.SourcesTable
import com.potaty.backend.persistence.TenantIntegrityException
import com.potaty.backend.persistence.TransactionContext
import com.potaty.backend.persistence.WorkspaceMembersTable
import com.potaty.backend.source.TextChunk
import com.potaty.backend.source.TranscriptChunk
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.Expression
import org.jetbrains.exposed.sql.QueryBuilder
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.vendors.H2Dialect
import org.jetbrains.exposed.sql.vendors.PostgreSQLDialect
import org.jetbrains.exposed.sql.vendors.currentDialect

data class SourceRecord(
    val id: UUID,
    val workspaceId: UUID,
    val projectId: UUID,
    val sourceType: String,
    val displayName: String
)

data class SourceVersionRecord(
    val id: UUID,
    val workspaceId: UUID,
    val sourceId: UUID,
    val contentHash: String
)

data class StoredChunk(
    val id: UUID,
    val sourceVersionId: UUID,
    val chunkIndex: Int,
    val path: String?,
    val startLine: Int?,
    val endLine: Int?,
    val text: String,
    val startMs: Int? = null,
    val endMs: Int? = null,
    val speaker: String? = null
)

data class AtomicSourceRecord(
    val sourceId: UUID,
    val sourceVersionId: UUID,
    val projectId: UUID,
    val sourceType: String,
    val displayName: String,
    val externalRefJson: String,
    val contentHash: String,
    val metadataJson: String,
    val chunkCount: Int
)

sealed interface AtomicIngestionClaim {
    data class Acquired(
        val token: UUID,
        val leaseExpiresAt: Instant
    ) : AtomicIngestionClaim

    data class Busy(val leaseExpiresAt: Instant) : AtomicIngestionClaim

    data class Complete(val source: AtomicSourceRecord) : AtomicIngestionClaim
}

class IngestionClaimLostException(message: String) : RuntimeException(message)

class SourceRepository(
    private val txc: TransactionContext,
    private val identities: IdentityRepository? = null,
    private val idFactory: () -> UUID = UUID::randomUUID
) {

    /**
     * Claims ownership before an ingestion performs outbound work. The unique workspace/key row is
     * the authority under races; an expired owner can be fenced out with a new token.
     */
    @Suppress("LongParameterList")
    suspend fun acquireAtomicIngestionClaim(
        workspaceId: UUID,
        projectId: UUID,
        sourceType: String,
        ingestionKey: String,
        requestHash: String,
        leaseDuration: Duration = DEFAULT_INGESTION_CLAIM_LEASE
    ): AtomicIngestionClaim {
        validateClaimInput(sourceType, ingestionKey, requestHash, leaseDuration)
        val token = idFactory()
        try {
            return checkNotNull(
                txc.tx {
                    acquireAtomicIngestionClaimInCurrentTransaction(
                        workspaceId,
                        projectId,
                        sourceType,
                        ingestionKey,
                        requestHash,
                        token,
                        leaseDuration,
                        insertWhenMissing = true
                    )
                }
            )
        } catch (cause: IdempotencyConflictException) {
            throw cause
        } catch (cause: Throwable) {
            if (cause is CancellationException) throw cause
            // Two first callers can race on the unique key. Re-open a fresh transaction after the
            // losing insert rolls back and observe, wait for, or take over the authoritative row.
            val winner =
                txc.tx {
                    acquireAtomicIngestionClaimInCurrentTransaction(
                        workspaceId,
                        projectId,
                        sourceType,
                        ingestionKey,
                        requestHash,
                        token,
                        leaseDuration,
                        insertWhenMissing = false
                    )
                }
            if (winner != null) return winner
            throw cause
        }
    }

    suspend fun renewAtomicIngestionClaim(
        workspaceId: UUID,
        ingestionKey: String,
        claimToken: UUID,
        leaseDuration: Duration = DEFAULT_INGESTION_CLAIM_LEASE
    ) {
        validateClaimLeaseDuration(leaseDuration)
        val updated =
            txc.tx {
                SourceIngestionClaimsTable.update({
                    (SourceIngestionClaimsTable.workspaceId eq workspaceId) and
                        (SourceIngestionClaimsTable.ingestionKey eq ingestionKey) and
                        (SourceIngestionClaimsTable.status eq CLAIM_PROCESSING) and
                        (SourceIngestionClaimsTable.processingToken eq claimToken) and
                        (
                            SourceIngestionClaimsTable.leaseExpiresAt greater
                                databaseNow()
                            )
                }) {
                    it[SourceIngestionClaimsTable.leaseExpiresAt] =
                        databaseLeaseExpiry(leaseDuration)
                    it[updatedAt] = databaseNow()
                }
            }
        if (updated != 1) {
            throw IngestionClaimLostException("Source ingestion claim is no longer owned")
        }
    }

    /** Releases only the caller's still-processing claim; completed replay rows are immutable. */
    suspend fun releaseAtomicIngestionClaim(
        workspaceId: UUID,
        ingestionKey: String,
        claimToken: UUID
    ) {
        txc.tx {
            SourceIngestionClaimsTable.deleteWhere {
                (SourceIngestionClaimsTable.workspaceId eq workspaceId) and
                    (SourceIngestionClaimsTable.ingestionKey eq ingestionKey) and
                    (SourceIngestionClaimsTable.status eq CLAIM_PROCESSING) and
                    (SourceIngestionClaimsTable.processingToken eq claimToken)
            }
        }
    }

    internal suspend fun countAtomicIngestionClaims(workspaceId: UUID): Int = txc.tx {
        SourceIngestionClaimsTable.select {
            SourceIngestionClaimsTable.workspaceId eq workspaceId
        }.count().toInt()
    }

    /** Returns a completed ingestion only when the key is bound to this exact logical request. */
    suspend fun findAtomicIngestion(
        workspaceId: UUID,
        projectId: UUID,
        sourceType: String,
        ingestionKey: String,
        requestHash: String
    ): AtomicSourceRecord? = txc.tx {
        findAtomicIngestionInCurrentTransaction(
            workspaceId = workspaceId,
            projectId = projectId,
            sourceType = sourceType,
            ingestionKey = ingestionKey,
            requestHash = requestHash
        )
    }

    /** Creates a text/GitHub source, version, and chunks in one replay-safe transaction. */
    @Suppress("LongParameterList")
    suspend fun createTextSourceAtomic(
        workspaceId: UUID,
        projectId: UUID,
        sourceType: String,
        displayName: String,
        externalRefJson: String,
        createdBy: UUID?,
        contentHash: String,
        metadataJson: String,
        chunks: List<TextChunk>,
        ingestionKey: String,
        requestHash: String,
        claimToken: UUID? = null
    ): AtomicSourceRecord = createAtomic(
        workspaceId = workspaceId,
        projectId = projectId,
        sourceType = sourceType,
        displayName = displayName,
        externalRefJson = externalRefJson,
        createdBy = createdBy,
        contentHash = contentHash,
        metadataJson = metadataJson,
        chunks = chunks.map(ChunkDraft::fromText),
        ingestionKey = ingestionKey,
        requestHash = requestHash,
        claimToken = claimToken
    )

    /** Creates source, version, and transcript chunks in one transaction, replaying by key. */
    @Suppress("LongParameterList")
    suspend fun createTranscriptAtomic(
        workspaceId: UUID,
        projectId: UUID,
        displayName: String,
        externalRefJson: String,
        createdBy: UUID?,
        contentHash: String,
        metadataJson: String,
        chunks: List<TranscriptChunk>,
        ingestionKey: String? = null,
        requestHash: String? = ingestionKey
    ): AtomicSourceRecord = createAtomic(
        workspaceId = workspaceId,
        projectId = projectId,
        sourceType = "TRANSCRIPT",
        displayName = displayName.ifBlank { "Untitled transcript" },
        externalRefJson = externalRefJson,
        createdBy = createdBy,
        contentHash = contentHash,
        metadataJson = metadataJson,
        chunks = chunks.map(ChunkDraft::fromTranscript),
        ingestionKey = ingestionKey,
        requestHash = requestHash,
        claimToken = null
    )

    /** Transactional primitive used by the transcription completion coordinator. */
    @Suppress("LongParameterList")
    internal fun createTranscriptInCurrentTransaction(
        workspaceId: UUID,
        projectId: UUID,
        displayName: String,
        externalRefJson: String,
        createdBy: UUID?,
        contentHash: String,
        metadataJson: String,
        chunks: List<TranscriptChunk>,
        ingestionKey: String?,
        requestHash: String? = ingestionKey
    ): AtomicSourceRecord = createAtomicInCurrentTransaction(
        workspaceId = workspaceId,
        projectId = projectId,
        sourceType = "TRANSCRIPT",
        displayName = displayName.ifBlank { "Untitled transcript" },
        externalRefJson = externalRefJson,
        createdBy = createdBy,
        contentHash = contentHash,
        metadataJson = metadataJson,
        chunks = chunks.map(ChunkDraft::fromTranscript),
        ingestionKey = ingestionKey,
        requestHash = requestHash,
        claimToken = null
    )

    @Suppress("LongParameterList")
    private suspend fun createAtomic(
        workspaceId: UUID,
        projectId: UUID,
        sourceType: String,
        displayName: String,
        externalRefJson: String,
        createdBy: UUID?,
        contentHash: String,
        metadataJson: String,
        chunks: List<ChunkDraft>,
        ingestionKey: String?,
        requestHash: String?,
        claimToken: UUID?
    ): AtomicSourceRecord {
        try {
            return txc.tx {
                createAtomicInCurrentTransaction(
                    workspaceId = workspaceId,
                    projectId = projectId,
                    sourceType = sourceType,
                    displayName = displayName,
                    externalRefJson = externalRefJson,
                    createdBy = createdBy,
                    contentHash = contentHash,
                    metadataJson = metadataJson,
                    chunks = chunks,
                    ingestionKey = ingestionKey,
                    requestHash = requestHash,
                    claimToken = claimToken
                )
            }
        } catch (cause: IdempotencyConflictException) {
            throw cause
        } catch (cause: Throwable) {
            if (cause is CancellationException) throw cause
            if (ingestionKey != null && requestHash != null) {
                // Concurrent callers can both miss the initial lookup. The unique index decides
                // the winner; the loser re-opens a fresh transaction and replays only an exact
                // request whose complete artifact is present.
                val winner = txc.tx {
                    findAtomicIngestionInCurrentTransaction(
                        workspaceId = workspaceId,
                        projectId = projectId,
                        sourceType = sourceType,
                        ingestionKey = ingestionKey,
                        requestHash = requestHash
                    )?.also { existing ->
                        if (claimToken != null) {
                            completeAtomicIngestionClaimInCurrentTransaction(
                                workspaceId,
                                projectId,
                                sourceType,
                                ingestionKey,
                                requestHash,
                                claimToken,
                                existing
                            )
                        }
                    }
                }
                if (winner != null) return winner
            }
            throw cause
        }
    }

    @Suppress("LongParameterList", "LongMethod")
    private fun createAtomicInCurrentTransaction(
        workspaceId: UUID,
        projectId: UUID,
        sourceType: String,
        displayName: String,
        externalRefJson: String,
        createdBy: UUID?,
        contentHash: String,
        metadataJson: String,
        chunks: List<ChunkDraft>,
        ingestionKey: String?,
        requestHash: String?,
        claimToken: UUID?
    ): AtomicSourceRecord {
        validateAtomicInput(
            sourceType,
            displayName,
            externalRefJson,
            contentHash,
            metadataJson,
            chunks,
            ingestionKey,
            requestHash
        )
        require(claimToken == null || (ingestionKey != null && requestHash != null)) {
            "A source-ingestion claim requires an ingestion key and request hash"
        }
        require(sourceType != GITHUB_SOURCE_TYPE || claimToken != null) {
            "GitHub source persistence requires an owned ingestion claim"
        }
        val project =
            ProjectsTable
                .slice(ProjectsTable.id)
                .select {
                    (ProjectsTable.workspaceId eq workspaceId) and
                        (ProjectsTable.id eq projectId) and
                        ProjectsTable.deletedAt.isNull()
                }
                .limit(1)
                .singleOrNull()
                ?: throw TenantIntegrityException("Project not found")
        check(project[ProjectsTable.id] == projectId)
        if (createdBy != null) {
            val member =
                WorkspaceMembersTable
                    .slice(WorkspaceMembersTable.userId)
                    .select {
                        (WorkspaceMembersTable.workspaceId eq workspaceId) and
                            (WorkspaceMembersTable.userId eq createdBy)
                    }
                    .limit(1)
                    .singleOrNull()
                    ?: throw TenantIntegrityException("Workspace member not found")
            check(member[WorkspaceMembersTable.userId] == createdBy)
        }

        if (ingestionKey != null && requestHash != null) {
            val existing =
                findAtomicIngestionInCurrentTransaction(
                    workspaceId = workspaceId,
                    projectId = projectId,
                    sourceType = sourceType,
                    ingestionKey = ingestionKey,
                    requestHash = requestHash
                )
            if (existing != null) {
                if (claimToken != null) {
                    completeAtomicIngestionClaimInCurrentTransaction(
                        workspaceId,
                        projectId,
                        sourceType,
                        ingestionKey,
                        requestHash,
                        claimToken,
                        existing
                    )
                }
                return existing
            }
        }

        if (claimToken != null && ingestionKey != null && requestHash != null) {
            fenceAtomicIngestionClaimInCurrentTransaction(
                workspaceId,
                projectId,
                sourceType,
                ingestionKey,
                requestHash,
                claimToken
            )
        }

        val sourceId = idFactory()
        val sourceVersionId = idFactory()
        val now = Instant.now()
        SourcesTable.insert {
            it[SourcesTable.id] = sourceId
            it[SourcesTable.workspaceId] = workspaceId
            it[SourcesTable.projectId] = projectId
            it[SourcesTable.sourceType] = sourceType
            it[SourcesTable.displayName] = displayName
            it[externalRef] = externalRefJson
            it[SourcesTable.ingestionKey] = ingestionKey
            it[SourcesTable.ingestionRequestHash] = requestHash
            it[SourcesTable.createdBy] = createdBy
            it[createdAt] = now
            it[updatedAt] = now
        }
        SourceVersionsTable.insert {
            it[SourceVersionsTable.id] = sourceVersionId
            it[SourceVersionsTable.workspaceId] = workspaceId
            it[SourceVersionsTable.sourceId] = sourceId
            it[SourceVersionsTable.contentHash] = contentHash
            it[normalizedTextObjectKey] = null
            it[rawObjectKey] = null
            it[metadata] = metadataJson
            it[createdAt] = now
        }
        chunks.forEach { chunk ->
            SourceChunksTable.insert {
                it[SourceChunksTable.id] = idFactory()
                it[SourceChunksTable.workspaceId] = workspaceId
                it[SourceChunksTable.sourceVersionId] = sourceVersionId
                it[chunkIndex] = chunk.chunkIndex
                it[path] = chunk.path
                it[startLine] = chunk.startLine
                it[endLine] = chunk.endLine
                it[startMs] = chunk.startMs
                it[endMs] = chunk.endMs
                it[speaker] = chunk.speaker
                it[content] = chunk.text
                it[textHash] = chunk.textHash
                it[tokenCount] = chunk.tokenCount
                it[metadata] = "{}"
                it[createdAt] = now
            }
        }
        val stored = AtomicSourceRecord(
            sourceId = sourceId,
            sourceVersionId = sourceVersionId,
            projectId = projectId,
            sourceType = sourceType,
            displayName = displayName,
            externalRefJson = externalRefJson,
            contentHash = contentHash,
            metadataJson = metadataJson,
            chunkCount = chunks.size
        )
        if (claimToken != null && ingestionKey != null && requestHash != null) {
            completeAtomicIngestionClaimInCurrentTransaction(
                workspaceId,
                projectId,
                sourceType,
                ingestionKey,
                requestHash,
                claimToken,
                stored
            )
        }
        return stored
    }

    @Suppress("LongParameterList")
    private fun findAtomicIngestionInCurrentTransaction(
        workspaceId: UUID,
        projectId: UUID,
        sourceType: String,
        ingestionKey: String,
        requestHash: String
    ): AtomicSourceRecord? {
        require(REQUEST_HASH_PATTERN.matches(requestHash)) { "requestHash must be sha256" }
        val source =
            SourcesTable
                .select {
                    (SourcesTable.workspaceId eq workspaceId) and
                        (SourcesTable.ingestionKey eq ingestionKey)
                }
                .limit(1)
                .singleOrNull()
                ?: return null
        if (
            source[SourcesTable.deletedAt] != null ||
            source[SourcesTable.projectId] != projectId ||
            source[SourcesTable.sourceType] != sourceType ||
            source[SourcesTable.ingestionRequestHash] != requestHash
        ) {
            throw IdempotencyConflictException(
                "Idempotency-Key was already used for a different source request"
            )
        }
        val versions =
            SourceVersionsTable
                .select {
                    (SourceVersionsTable.workspaceId eq workspaceId) and
                        (SourceVersionsTable.sourceId eq source[SourcesTable.id])
                }
                .toList()
        if (versions.size != 1) {
            throw TenantIntegrityException("Idempotent source artifact is incomplete")
        }
        val version = versions.single()
        val versionId = version[SourceVersionsTable.id]
        val storedChunkCount =
            SourceChunksTable.select {
                (SourceChunksTable.workspaceId eq workspaceId) and
                    (SourceChunksTable.sourceVersionId eq versionId)
            }.count().toInt()
        val declaredChunkCount = metadataChunkCount(version[SourceVersionsTable.metadata])
        if (
            declaredChunkCount != null && storedChunkCount != declaredChunkCount
        ) {
            throw TenantIntegrityException("Idempotent source chunks are incomplete")
        }
        return AtomicSourceRecord(
            sourceId = source[SourcesTable.id],
            sourceVersionId = versionId,
            projectId = projectId,
            sourceType = sourceType,
            displayName = source[SourcesTable.displayName],
            externalRefJson = source[SourcesTable.externalRef],
            contentHash = version[SourceVersionsTable.contentHash],
            metadataJson = version[SourceVersionsTable.metadata],
            chunkCount = storedChunkCount
        )
    }

    @Suppress("LongParameterList")
    private fun acquireAtomicIngestionClaimInCurrentTransaction(
        workspaceId: UUID,
        projectId: UUID,
        sourceType: String,
        ingestionKey: String,
        requestHash: String,
        token: UUID,
        leaseDuration: Duration,
        insertWhenMissing: Boolean
    ): AtomicIngestionClaim? {
        requireProjectInCurrentTransaction(workspaceId, projectId)
        findAtomicIngestionInCurrentTransaction(
            workspaceId,
            projectId,
            sourceType,
            ingestionKey,
            requestHash
        )?.let { return AtomicIngestionClaim.Complete(it) }

        val existing =
            SourceIngestionClaimsTable.select {
                (SourceIngestionClaimsTable.workspaceId eq workspaceId) and
                    (SourceIngestionClaimsTable.ingestionKey eq ingestionKey)
            }
                .limit(1)
                .singleOrNull()
        if (existing == null) {
            if (!insertWhenMissing) return null
            SourceIngestionClaimsTable.insert {
                it[id] = idFactory()
                it[SourceIngestionClaimsTable.workspaceId] = workspaceId
                it[SourceIngestionClaimsTable.projectId] = projectId
                it[SourceIngestionClaimsTable.sourceType] = sourceType
                it[SourceIngestionClaimsTable.ingestionKey] = ingestionKey
                it[SourceIngestionClaimsTable.requestHash] = requestHash
                it[status] = CLAIM_PROCESSING
                it[processingToken] = token
                it[SourceIngestionClaimsTable.leaseExpiresAt] =
                    databaseLeaseExpiry(leaseDuration)
                it[sourceId] = null
                it[sourceVersionId] = null
                it[createdAt] = databaseNow()
                it[updatedAt] = databaseNow()
            }
            return AtomicIngestionClaim.Acquired(
                token,
                claimLeaseInCurrentTransaction(workspaceId, ingestionKey, token)
            )
        }

        validateClaimBinding(existing, projectId, sourceType, requestHash)
        return when (existing[SourceIngestionClaimsTable.status]) {
            CLAIM_COMPLETE -> {
                val source =
                    findAtomicIngestionInCurrentTransaction(
                        workspaceId,
                        projectId,
                        sourceType,
                        ingestionKey,
                        requestHash
                    ) ?: throw TenantIntegrityException(
                        "Completed source ingestion claim has no complete artifact"
                    )
                if (
                    existing[SourceIngestionClaimsTable.sourceId] != source.sourceId ||
                    existing[SourceIngestionClaimsTable.sourceVersionId] != source.sourceVersionId
                ) {
                    throw TenantIntegrityException("Source ingestion claim result is inconsistent")
                }
                AtomicIngestionClaim.Complete(source)
            }

            CLAIM_PROCESSING -> {
                val activeToken = existing[SourceIngestionClaimsTable.processingToken]
                    ?: throw TenantIntegrityException("Processing source claim has no token")
                val activeLease = existing[SourceIngestionClaimsTable.leaseExpiresAt]
                    ?: throw TenantIntegrityException("Processing source claim has no lease")
                val updated =
                    SourceIngestionClaimsTable.update({
                        (SourceIngestionClaimsTable.workspaceId eq workspaceId) and
                            (SourceIngestionClaimsTable.ingestionKey eq ingestionKey) and
                            (SourceIngestionClaimsTable.status eq CLAIM_PROCESSING) and
                            (SourceIngestionClaimsTable.processingToken eq activeToken) and
                            (SourceIngestionClaimsTable.leaseExpiresAt eq activeLease) and
                            (
                                SourceIngestionClaimsTable.leaseExpiresAt lessEq
                                    databaseNow()
                                )
                    }) {
                        it[processingToken] = token
                        it[SourceIngestionClaimsTable.leaseExpiresAt] =
                            databaseLeaseExpiry(leaseDuration)
                        it[updatedAt] = databaseNow()
                    }
                if (updated == 1) {
                    AtomicIngestionClaim.Acquired(
                        token,
                        claimLeaseInCurrentTransaction(workspaceId, ingestionKey, token)
                    )
                } else {
                    AtomicIngestionClaim.Busy(activeLease)
                }
            }

            else -> throw TenantIntegrityException("Source ingestion claim has an invalid status")
        }
    }

    private fun validateClaimInput(
        sourceType: String,
        ingestionKey: String,
        requestHash: String,
        leaseDuration: Duration
    ) {
        require(sourceType.isNotBlank()) { "sourceType is required" }
        require(INGESTION_KEY_PATTERN.matches(ingestionKey)) { "ingestionKey is invalid" }
        require(REQUEST_HASH_PATTERN.matches(requestHash)) { "requestHash must be sha256" }
        validateClaimLeaseDuration(leaseDuration)
    }

    private fun validateClaimLeaseDuration(leaseDuration: Duration) {
        require(
            !leaseDuration.isNegative &&
                !leaseDuration.isZero &&
                leaseDuration.toMillis() >= 1L &&
                leaseDuration <= MAX_INGESTION_CLAIM_LEASE
        ) {
            "Source ingestion claim lease is outside the allowed range"
        }
    }

    private fun validateClaimBinding(
        claim: ResultRow,
        projectId: UUID,
        sourceType: String,
        requestHash: String
    ) {
        if (
            claim[SourceIngestionClaimsTable.projectId] != projectId ||
            claim[SourceIngestionClaimsTable.sourceType] != sourceType ||
            claim[SourceIngestionClaimsTable.requestHash] != requestHash
        ) {
            throw IdempotencyConflictException(
                "Idempotency-Key was already used for a different source request"
            )
        }
    }

    private fun requireProjectInCurrentTransaction(
        workspaceId: UUID,
        projectId: UUID
    ) {
        val exists =
            ProjectsTable.select {
                (ProjectsTable.workspaceId eq workspaceId) and
                    (ProjectsTable.id eq projectId) and
                    ProjectsTable.deletedAt.isNull()
            }
                .limit(1)
                .any()
        if (!exists) throw TenantIntegrityException("Project not found")
    }

    @Suppress("LongParameterList")
    private fun fenceAtomicIngestionClaimInCurrentTransaction(
        workspaceId: UUID,
        projectId: UUID,
        sourceType: String,
        ingestionKey: String,
        requestHash: String,
        claimToken: UUID
    ) {
        val claim =
            SourceIngestionClaimsTable.select {
                (SourceIngestionClaimsTable.workspaceId eq workspaceId) and
                    (SourceIngestionClaimsTable.ingestionKey eq ingestionKey)
            }
                .limit(1)
                .singleOrNull()
                ?: throw IngestionClaimLostException("Source ingestion claim no longer exists")
        validateClaimBinding(claim, projectId, sourceType, requestHash)
        if (
            claim[SourceIngestionClaimsTable.status] != CLAIM_PROCESSING ||
            claim[SourceIngestionClaimsTable.processingToken] != claimToken
        ) {
            throw IngestionClaimLostException("Source ingestion claim is no longer owned")
        }
        // A write both extends the finalization window and locks the claim row until the source,
        // version, chunks, and completed claim commit together.
        val updated =
            SourceIngestionClaimsTable.update({
                (SourceIngestionClaimsTable.workspaceId eq workspaceId) and
                    (SourceIngestionClaimsTable.ingestionKey eq ingestionKey) and
                    (SourceIngestionClaimsTable.status eq CLAIM_PROCESSING) and
                    (SourceIngestionClaimsTable.processingToken eq claimToken) and
                    (
                        SourceIngestionClaimsTable.leaseExpiresAt greater
                            databaseNow()
                        )
            }) {
                it[leaseExpiresAt] = databaseLeaseExpiry(FINALIZATION_CLAIM_LEASE)
                it[updatedAt] = databaseNow()
            }
        if (updated != 1) {
            throw IngestionClaimLostException("Source ingestion claim is no longer owned")
        }
    }

    @Suppress("LongParameterList")
    private fun completeAtomicIngestionClaimInCurrentTransaction(
        workspaceId: UUID,
        projectId: UUID,
        sourceType: String,
        ingestionKey: String,
        requestHash: String,
        claimToken: UUID,
        source: AtomicSourceRecord
    ) {
        val claim =
            SourceIngestionClaimsTable.select {
                (SourceIngestionClaimsTable.workspaceId eq workspaceId) and
                    (SourceIngestionClaimsTable.ingestionKey eq ingestionKey)
            }
                .limit(1)
                .singleOrNull()
                ?: throw IngestionClaimLostException("Source ingestion claim no longer exists")
        validateClaimBinding(claim, projectId, sourceType, requestHash)
        if (claim[SourceIngestionClaimsTable.status] == CLAIM_COMPLETE) {
            if (
                claim[SourceIngestionClaimsTable.sourceId] != source.sourceId ||
                claim[SourceIngestionClaimsTable.sourceVersionId] != source.sourceVersionId
            ) {
                throw TenantIntegrityException("Source ingestion claim result is inconsistent")
            }
            return
        }
        if (claim[SourceIngestionClaimsTable.processingToken] != claimToken) {
            throw IngestionClaimLostException("Source ingestion claim is no longer owned")
        }
        val updated =
            SourceIngestionClaimsTable.update({
                (SourceIngestionClaimsTable.workspaceId eq workspaceId) and
                    (SourceIngestionClaimsTable.ingestionKey eq ingestionKey) and
                    (SourceIngestionClaimsTable.status eq CLAIM_PROCESSING) and
                    (SourceIngestionClaimsTable.processingToken eq claimToken) and
                    (
                        SourceIngestionClaimsTable.leaseExpiresAt greater
                            databaseNow()
                        )
            }) {
                it[status] = CLAIM_COMPLETE
                it[processingToken] = null
                it[leaseExpiresAt] = null
                it[sourceId] = source.sourceId
                it[sourceVersionId] = source.sourceVersionId
                it[updatedAt] = databaseNow()
            }
        if (updated != 1) {
            throw IngestionClaimLostException("Source ingestion claim is no longer owned")
        }
    }

    private fun claimLeaseInCurrentTransaction(
        workspaceId: UUID,
        ingestionKey: String,
        claimToken: UUID
    ): Instant = checkNotNull(
        SourceIngestionClaimsTable
            .slice(SourceIngestionClaimsTable.leaseExpiresAt)
            .select {
                (SourceIngestionClaimsTable.workspaceId eq workspaceId) and
                    (SourceIngestionClaimsTable.ingestionKey eq ingestionKey) and
                    (SourceIngestionClaimsTable.status eq CLAIM_PROCESSING) and
                    (SourceIngestionClaimsTable.processingToken eq claimToken)
            }
            .limit(1)
            .singleOrNull()
            ?.get(SourceIngestionClaimsTable.leaseExpiresAt)
    ) { "Source ingestion claim has no active lease" }

    private fun validateAtomicInput(
        sourceType: String,
        displayName: String,
        externalRefJson: String,
        contentHash: String,
        metadataJson: String,
        chunks: List<ChunkDraft>,
        ingestionKey: String?,
        requestHash: String?
    ) {
        require(sourceType.isNotBlank()) { "sourceType is required" }
        require(displayName.isNotBlank()) { "displayName is required" }
        require(CONTENT_HASH_PATTERN.matches(contentHash)) { "contentHash must be sha256" }
        require(runCatching { Json.parseToJsonElement(externalRefJson) }.isSuccess) {
            "externalRefJson must be valid JSON"
        }
        require(runCatching { Json.parseToJsonElement(metadataJson) }.isSuccess) {
            "metadataJson must be valid JSON"
        }
        require((ingestionKey == null) == (requestHash == null)) {
            "ingestionKey and requestHash must be provided together"
        }
        if (ingestionKey != null && requestHash != null) {
            require(INGESTION_KEY_PATTERN.matches(ingestionKey)) { "ingestionKey is invalid" }
            require(REQUEST_HASH_PATTERN.matches(requestHash)) { "requestHash must be sha256" }
        }
        chunks.forEachIndexed { index, chunk ->
            require(chunk.chunkIndex == index) { "chunk order must be contiguous" }
            require(chunk.startLine >= 1 && chunk.endLine >= chunk.startLine) {
                "chunk line range is invalid"
            }
            require(chunk.tokenCount >= 0) { "chunk token count is invalid" }
        }
    }

    private fun metadataChunkCount(metadataJson: String): Int? =
        runCatching {
            Json.parseToJsonElement(metadataJson)
                .jsonObject["chunkCount"]
                ?.jsonPrimitive
                ?.intOrNull
        }.getOrNull()

    private data class ChunkDraft(
        val chunkIndex: Int,
        val path: String?,
        val startLine: Int,
        val endLine: Int,
        val startMs: Int?,
        val endMs: Int?,
        val speaker: String?,
        val text: String,
        val textHash: String,
        val tokenCount: Int
    ) {
        companion object {
            fun fromText(chunk: TextChunk) =
                ChunkDraft(
                    chunkIndex = chunk.chunkIndex,
                    path = chunk.path,
                    startLine = chunk.startLine,
                    endLine = chunk.endLine,
                    startMs = null,
                    endMs = null,
                    speaker = null,
                    text = chunk.text,
                    textHash = chunk.textHash,
                    tokenCount = chunk.tokenCount
                )

            fun fromTranscript(chunk: TranscriptChunk) =
                ChunkDraft(
                    chunkIndex = chunk.chunkIndex,
                    path = chunk.path,
                    startLine = chunk.startLine,
                    endLine = chunk.endLine,
                    startMs = chunk.startMs,
                    endMs = chunk.endMs,
                    speaker = chunk.speaker,
                    text = chunk.text,
                    textHash = chunk.textHash,
                    tokenCount = chunk.tokenCount
                )
        }
    }

    private companion object {
        const val CLAIM_PROCESSING = "processing"
        const val CLAIM_COMPLETE = "complete"
        const val GITHUB_SOURCE_TYPE = "GITHUB_REPO"
        val DEFAULT_INGESTION_CLAIM_LEASE: Duration = Duration.ofMinutes(4)
        val FINALIZATION_CLAIM_LEASE: Duration = Duration.ofMinutes(15)
        val MAX_INGESTION_CLAIM_LEASE: Duration = Duration.ofMinutes(30)
        val CONTENT_HASH_PATTERN = Regex("^sha256:[0-9a-f]{64}$")
        val REQUEST_HASH_PATTERN = Regex("^sha256:[0-9a-f]{64}$")
        val INGESTION_KEY_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,255}$")
    }

    suspend fun createSource(
        workspaceId: UUID,
        projectId: UUID,
        sourceType: String,
        displayName: String,
        externalRefJson: String,
        createdBy: UUID?
    ): SourceRecord {
        identities?.requireProject(workspaceId, projectId)
        identities?.requireMember(workspaceId, createdBy)
        return txc.tx {
            val newId = UUID.randomUUID()
            val now = Instant.now()
            SourcesTable.insert {
                it[id] = newId
                it[SourcesTable.workspaceId] = workspaceId
                it[SourcesTable.projectId] = projectId
                it[SourcesTable.sourceType] = sourceType
                it[SourcesTable.displayName] = displayName
                it[externalRef] = externalRefJson
                it[SourcesTable.createdBy] = createdBy
                it[createdAt] = now
                it[updatedAt] = now
            }
            SourceRecord(newId, workspaceId, projectId, sourceType, displayName)
        }
    }

    suspend fun createVersion(
        workspaceId: UUID,
        sourceId: UUID,
        contentHash: String,
        normalizedTextObjectKey: String?,
        rawObjectKey: String?,
        metadataJson: String
    ): SourceVersionRecord {
        identities?.requireSource(workspaceId, sourceId)
        return txc.tx {
            val newId = UUID.randomUUID()
            SourceVersionsTable.insert {
                it[id] = newId
                it[SourceVersionsTable.workspaceId] = workspaceId
                it[SourceVersionsTable.sourceId] = sourceId
                it[SourceVersionsTable.contentHash] = contentHash
                it[SourceVersionsTable.normalizedTextObjectKey] = normalizedTextObjectKey
                it[SourceVersionsTable.rawObjectKey] = rawObjectKey
                it[metadata] = metadataJson
                it[createdAt] = Instant.now()
            }
            SourceVersionRecord(newId, workspaceId, sourceId, contentHash)
        }
    }

    /** Persists chunks for a source version and returns them with their generated ids. */
    suspend fun saveChunks(
        workspaceId: UUID,
        sourceVersionId: UUID,
        chunks: List<TextChunk>
    ): List<StoredChunk> {
        identities?.requireSourceVersion(workspaceId, sourceVersionId)
        return txc.tx {
            val now = Instant.now()
            chunks.map { c ->
                val newId = UUID.randomUUID()
                SourceChunksTable.insert {
                    it[id] = newId
                    it[SourceChunksTable.workspaceId] = workspaceId
                    it[SourceChunksTable.sourceVersionId] = sourceVersionId
                    it[chunkIndex] = c.chunkIndex
                    it[path] = c.path
                    it[startLine] = c.startLine
                    it[endLine] = c.endLine
                    it[startMs] = null
                    it[endMs] = null
                    it[speaker] = null
                    it[content] = c.text
                    it[textHash] = c.textHash
                    it[tokenCount] = c.tokenCount
                    it[metadata] = "{}"
                    it[createdAt] = now
                }
                StoredChunk(
                    id = newId,
                    sourceVersionId = sourceVersionId,
                    chunkIndex = c.chunkIndex,
                    path = c.path,
                    startLine = c.startLine,
                    endLine = c.endLine,
                    text = c.text
                )
            }
        }
    }

    /** Persists transcript chunks for a version, preserving speaker / start_ms / end_ms evidence. */
    suspend fun saveTranscriptChunks(
        workspaceId: UUID,
        sourceVersionId: UUID,
        chunks: List<TranscriptChunk>
    ): List<StoredChunk> {
        identities?.requireSourceVersion(workspaceId, sourceVersionId)
        return txc.tx {
            val now = Instant.now()
            chunks.map { c ->
                val newId = UUID.randomUUID()
                SourceChunksTable.insert {
                    it[id] = newId
                    it[SourceChunksTable.workspaceId] = workspaceId
                    it[SourceChunksTable.sourceVersionId] = sourceVersionId
                    it[chunkIndex] = c.chunkIndex
                    it[path] = c.path
                    it[startLine] = c.startLine
                    it[endLine] = c.endLine
                    it[startMs] = c.startMs
                    it[endMs] = c.endMs
                    it[speaker] = c.speaker
                    it[content] = c.text
                    it[textHash] = c.textHash
                    it[tokenCount] = c.tokenCount
                    it[metadata] = "{}"
                    it[createdAt] = now
                }
                StoredChunk(
                    id = newId,
                    sourceVersionId = sourceVersionId,
                    chunkIndex = c.chunkIndex,
                    path = c.path,
                    startLine = c.startLine,
                    endLine = c.endLine,
                    text = c.text,
                    startMs = c.startMs,
                    endMs = c.endMs,
                    speaker = c.speaker
                )
            }
        }
    }

    suspend fun listChunks(workspaceId: UUID, sourceVersionId: UUID): List<StoredChunk> = txc.tx {
        SourceChunksTable
            .select {
                (SourceChunksTable.workspaceId eq workspaceId) and
                    (SourceChunksTable.sourceVersionId eq sourceVersionId)
            }
            .orderBy(SourceChunksTable.chunkIndex to SortOrder.ASC)
            .map {
                StoredChunk(
                    id = it[SourceChunksTable.id],
                    sourceVersionId = it[SourceChunksTable.sourceVersionId],
                    chunkIndex = it[SourceChunksTable.chunkIndex],
                    path = it[SourceChunksTable.path],
                    startLine = it[SourceChunksTable.startLine],
                    endLine = it[SourceChunksTable.endLine],
                    text = it[SourceChunksTable.content],
                    startMs = it[SourceChunksTable.startMs],
                    endMs = it[SourceChunksTable.endMs],
                    speaker = it[SourceChunksTable.speaker]
                )
            }
    }

    suspend fun listSources(workspaceId: UUID, projectId: UUID): List<SourceRecord> = txc.tx {
        SourcesTable
            .select {
                (SourcesTable.workspaceId eq workspaceId) and
                    (SourcesTable.projectId eq projectId) and
                    SourcesTable.deletedAt.isNull()
            }
            .map {
                SourceRecord(
                    id = it[SourcesTable.id],
                    workspaceId = it[SourcesTable.workspaceId],
                    projectId = it[SourcesTable.projectId],
                    sourceType = it[SourcesTable.sourceType],
                    displayName = it[SourcesTable.displayName]
                )
            }
    }

    suspend fun findSource(workspaceId: UUID, sourceId: UUID): SourceRecord? = txc.tx {
        SourcesTable
            .select { (SourcesTable.id eq sourceId) and (SourcesTable.workspaceId eq workspaceId) }
            .limit(1)
            .map {
                SourceRecord(
                    id = it[SourcesTable.id],
                    workspaceId = it[SourcesTable.workspaceId],
                    projectId = it[SourcesTable.projectId],
                    sourceType = it[SourcesTable.sourceType],
                    displayName = it[SourcesTable.displayName]
                )
            }
            .singleOrNull()
    }
}

/**
 * Uses the database server as the only lease clock. PostgreSQL's clock_timestamp() remains current
 * after row-lock waits; CURRENT_TIMESTAMP is intentionally sufficient for single-process H2 tests.
 */
private fun databaseNow(): Expression<Instant> = DatabaseNowExpression

private fun databaseLeaseExpiry(duration: Duration): Expression<Instant> =
    DatabaseLeaseExpiryExpression(duration.toMillis())

private object DatabaseNowExpression : Expression<Instant>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        when (currentDialect) {
            is PostgreSQLDialect -> queryBuilder.append("clock_timestamp()")
            is H2Dialect -> queryBuilder.append("CURRENT_TIMESTAMP")
            else -> error("Source-ingestion leases support only PostgreSQL and H2")
        }
    }
}

private class DatabaseLeaseExpiryExpression(private val durationMillis: Long) :
    Expression<Instant>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        when (currentDialect) {
            is PostgreSQLDialect ->
                queryBuilder.append(
                    "(clock_timestamp() + INTERVAL '1 millisecond' * $durationMillis)"
                )
            is H2Dialect ->
                queryBuilder.append(
                    "DATEADD('MILLISECOND', $durationMillis, CURRENT_TIMESTAMP)"
                )
            else -> error("Source-ingestion leases support only PostgreSQL and H2")
        }
    }
}
