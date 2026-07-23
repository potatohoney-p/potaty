/*
 * Copyright (c) 2026, Potaty
 *
 * Tenant-scoped persistence for the deterministic extraction layer (plan 8.3 / 7.1). Stores the
 * grounded entity/relation graph derived from source chunks so a diagram run is reproducible and
 * auditable. EVERY method takes workspaceId AND projectId and filters by both (plan 20.5): there is
 * no method that can read across workspaces or projects.
 *
 * Evidence (a list of EvidenceRef) and chunk-id lists are serialized to JSON text, matching the
 * jsonb columns of the prod DDL while staying H2-compatible. The caller passes an already-built
 * kotlinx Json so encoding stays consistent with the rest of the app graph.
 */

package com.potaty.backend.persistence.repositories

import com.potaty.backend.extraction.ExtractionResult
import com.potaty.backend.persistence.ExtractedEntitiesTable
import com.potaty.backend.persistence.ExtractedRelationsTable
import com.potaty.backend.persistence.IdentityRepository
import com.potaty.backend.persistence.TransactionContext
import com.potaty.ir.EvidenceRef
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select

/** A persisted extracted entity, read back tenant-scoped. */
data class ExtractedEntityRecord(
    val id: UUID,
    val workspaceId: UUID,
    val projectId: UUID,
    val sourceVersionId: UUID?,
    val entityKey: String,
    val type: String,
    val name: String,
    val canonicalName: String,
    val summary: String?,
    val confidence: Double,
    val evidenceChunkIds: List<String>,
    val evidence: List<EvidenceRef>
)

/** A persisted extracted relation, read back tenant-scoped. */
data class ExtractedRelationRecord(
    val id: UUID,
    val workspaceId: UUID,
    val projectId: UUID,
    val sourceVersionId: UUID?,
    val fromEntityKey: String,
    val toEntityKey: String,
    val type: String,
    val label: String?,
    val confidence: Double,
    val evidenceChunkIds: List<String>,
    val evidence: List<EvidenceRef>
)

@OptIn(ExperimentalSerializationApi::class)
class ExtractionRepository(
    private val txc: TransactionContext,
    private val json: Json = Json { encodeDefaults = true; explicitNulls = false },
    private val identities: IdentityRepository? = null
) {
    private val evidenceListSerializer = ListSerializer(EvidenceRef.serializer())
    private val stringListSerializer = ListSerializer(String.serializer())

    /**
     * Persists a full [ExtractionResult] (entities + relations) for a (workspace, project, source
     * version) and returns the entity/relation ids. Persisting extraction is purely additive: it
     * does not gate diagram generation, so the grounded path keeps working even if this is skipped.
     */
    suspend fun save(
        workspaceId: UUID,
        projectId: UUID,
        sourceVersionId: UUID?,
        result: ExtractionResult,
        defaultConfidence: Double = 1.0
    ): SaveResult {
        require(defaultConfidence in 0.0..1.0) { "defaultConfidence must be between 0 and 1" }
        identities?.requireProject(workspaceId, projectId)
        if (sourceVersionId != null) {
            identities?.requireSourceVersionsInProject(
                workspaceId,
                projectId,
                listOf(sourceVersionId)
            )
        }
        return txc.tx {
            val now = Instant.now()
            val entityIds = result.entities.map { entity ->
                val newId = UUID.randomUUID()
                ExtractedEntitiesTable.insert {
                    it[id] = newId
                    it[ExtractedEntitiesTable.workspaceId] = workspaceId
                    it[ExtractedEntitiesTable.projectId] = projectId
                    it[ExtractedEntitiesTable.sourceVersionId] = sourceVersionId
                    it[entityKey] = entity.key
                    it[type] = entity.type.name.lowercase()
                    it[name] = entity.label
                    it[canonicalName] = entity.key
                    it[summary] = null
                    it[confidence] = BigDecimal.valueOf(defaultConfidence)
                    it[evidenceChunkIds] = encodeChunkIds(entity.evidence)
                    it[evidence] = json.encodeToString(evidenceListSerializer, entity.evidence)
                    it[metadata] = "{}"
                    it[createdAt] = now
                }
                newId
            }
            val relationIds = result.relations.map { relation ->
                val newId = UUID.randomUUID()
                ExtractedRelationsTable.insert {
                    it[id] = newId
                    it[ExtractedRelationsTable.workspaceId] = workspaceId
                    it[ExtractedRelationsTable.projectId] = projectId
                    it[ExtractedRelationsTable.sourceVersionId] = sourceVersionId
                    it[fromEntityKey] = relation.fromKey
                    it[toEntityKey] = relation.toKey
                    it[type] = relation.type.name.lowercase()
                    it[label] = relation.label
                    it[confidence] = BigDecimal.valueOf(defaultConfidence)
                    it[evidenceChunkIds] = encodeChunkIds(relation.evidence)
                    it[evidence] = json.encodeToString(evidenceListSerializer, relation.evidence)
                    it[metadata] = "{}"
                    it[createdAt] = now
                }
                newId
            }
            SaveResult(entityIds, relationIds)
        }
    }

    suspend fun listEntities(
        workspaceId: UUID,
        projectId: UUID,
        sourceVersionId: UUID? = null
    ): List<ExtractedEntityRecord> = txc.tx {
        ExtractedEntitiesTable
            .select {
                var cond = (ExtractedEntitiesTable.workspaceId eq workspaceId) and
                    (ExtractedEntitiesTable.projectId eq projectId)
                if (sourceVersionId != null) {
                    cond = cond and (ExtractedEntitiesTable.sourceVersionId eq sourceVersionId)
                }
                cond
            }
            .orderBy(
                ExtractedEntitiesTable.createdAt to SortOrder.ASC,
                ExtractedEntitiesTable.entityKey to SortOrder.ASC
            )
            .map {
                ExtractedEntityRecord(
                    id = it[ExtractedEntitiesTable.id],
                    workspaceId = it[ExtractedEntitiesTable.workspaceId],
                    projectId = it[ExtractedEntitiesTable.projectId],
                    sourceVersionId = it[ExtractedEntitiesTable.sourceVersionId],
                    entityKey = it[ExtractedEntitiesTable.entityKey],
                    type = it[ExtractedEntitiesTable.type],
                    name = it[ExtractedEntitiesTable.name],
                    canonicalName = it[ExtractedEntitiesTable.canonicalName],
                    summary = it[ExtractedEntitiesTable.summary],
                    confidence = it[ExtractedEntitiesTable.confidence].toDouble(),
                    evidenceChunkIds = decodeChunkIds(it[ExtractedEntitiesTable.evidenceChunkIds]),
                    evidence = decodeEvidence(it[ExtractedEntitiesTable.evidence])
                )
            }
    }

    suspend fun listRelations(
        workspaceId: UUID,
        projectId: UUID,
        sourceVersionId: UUID? = null
    ): List<ExtractedRelationRecord> = txc.tx {
        ExtractedRelationsTable
            .select {
                var cond = (ExtractedRelationsTable.workspaceId eq workspaceId) and
                    (ExtractedRelationsTable.projectId eq projectId)
                if (sourceVersionId != null) {
                    cond = cond and (ExtractedRelationsTable.sourceVersionId eq sourceVersionId)
                }
                cond
            }
            .orderBy(
                ExtractedRelationsTable.createdAt to SortOrder.ASC,
                ExtractedRelationsTable.fromEntityKey to SortOrder.ASC
            )
            .map {
                ExtractedRelationRecord(
                    id = it[ExtractedRelationsTable.id],
                    workspaceId = it[ExtractedRelationsTable.workspaceId],
                    projectId = it[ExtractedRelationsTable.projectId],
                    sourceVersionId = it[ExtractedRelationsTable.sourceVersionId],
                    fromEntityKey = it[ExtractedRelationsTable.fromEntityKey],
                    toEntityKey = it[ExtractedRelationsTable.toEntityKey],
                    type = it[ExtractedRelationsTable.type],
                    label = it[ExtractedRelationsTable.label],
                    confidence = it[ExtractedRelationsTable.confidence].toDouble(),
                    evidenceChunkIds = decodeChunkIds(it[ExtractedRelationsTable.evidenceChunkIds]),
                    evidence = decodeEvidence(it[ExtractedRelationsTable.evidence])
                )
            }
    }

    private fun encodeChunkIds(evidence: List<EvidenceRef>): String =
        json.encodeToString(stringListSerializer, evidence.map { it.sourceChunkId }.distinct())

    private fun decodeChunkIds(raw: String): List<String> =
        runCatching { json.decodeFromString(stringListSerializer, raw) }.getOrDefault(emptyList())

    private fun decodeEvidence(raw: String): List<EvidenceRef> =
        runCatching { json.decodeFromString(evidenceListSerializer, raw) }.getOrDefault(emptyList())

    data class SaveResult(
        val entityIds: List<UUID>,
        val relationIds: List<UUID>
    )
}
