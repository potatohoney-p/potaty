/*
 * Copyright (c) 2026, Potaty
 *
 * Tenant-scoped persistence for diagrams and their immutable versions. Every diagram edit
 * creates a new diagram_version (plan 7.4). All reads/writes filter by workspaceId.
 */

package com.potaty.backend.persistence.repositories

import com.potaty.backend.persistence.DiagramVersionsTable
import com.potaty.backend.persistence.DiagramsTable
import com.potaty.backend.persistence.IdentityRepository
import com.potaty.backend.persistence.RenderingsTable
import com.potaty.backend.persistence.TransactionContext
import java.security.MessageDigest
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select

data class DiagramRecord(
    val id: UUID,
    val workspaceId: UUID,
    val projectId: UUID,
    val title: String,
    val diagramType: String,
    val status: String,
    val createdBy: UUID?,
    val generationJobId: UUID? = null
)

data class DiagramVersionRecord(
    val id: UUID,
    val workspaceId: UUID,
    val diagramId: UUID,
    val versionNumber: Int,
    val cause: String,
    val irJson: String,
    val validationReportJson: String,
    val evidenceCoverageJson: String,
    // Durable reproducibility artifacts (plan 11.5 / 19.5): which sources + models produced this.
    val sourceSnapshotJson: String = "[]",
    val modelTraceJson: String = "[]",
    val createdBy: UUID? = null
)

data class RenderingRecord(
    val id: UUID,
    val workspaceId: UUID,
    val diagramVersionId: UUID,
    val format: String,
    val contentText: String?,
    val contentHash: String,
    val renderStatus: String
)

/** One all-or-nothing generated artifact, including its immutable version and code renderings. */
data class GeneratedDiagramArtifact(
    val diagram: DiagramRecord,
    val version: DiagramVersionRecord,
    val renderings: List<RenderingRecord>
)

class DiagramRepository(
    private val txc: TransactionContext,
    private val identities: IdentityRepository? = null
) {

    /** Stable for queued work, random for synchronous/manual generation. */
    fun diagramIdForGeneration(workspaceId: UUID, generationJobId: UUID?): UUID =
        generationJobId?.let { deterministicUuid("diagram", workspaceId, it) }
            ?: UUID.randomUUID()

    suspend fun createDiagram(
        workspaceId: UUID,
        projectId: UUID,
        title: String,
        diagramType: String,
        createdBy: UUID?
    ): DiagramRecord {
        identities?.requireProject(workspaceId, projectId)
        identities?.requireMember(workspaceId, createdBy)
        return txc.tx {
            val newId = UUID.randomUUID()
            val now = Instant.now()
            DiagramsTable.insert {
                it[id] = newId
                it[DiagramsTable.workspaceId] = workspaceId
                it[DiagramsTable.projectId] = projectId
                it[DiagramsTable.title] = title
                it[DiagramsTable.diagramType] = diagramType
                it[status] = "draft"
                it[generationJobId] = null
                it[DiagramsTable.createdBy] = createdBy
                it[createdAt] = now
                it[updatedAt] = now
            }
            DiagramRecord(
                newId,
                workspaceId,
                projectId,
                title,
                diagramType,
                "draft",
                createdBy,
                generationJobId = null
            )
        }
    }

    /** Returns the complete artifact previously committed by [generationJobId], if any. */
    suspend fun findGeneratedArtifact(
        workspaceId: UUID,
        generationJobId: UUID
    ): GeneratedDiagramArtifact? = txc.tx {
        findGeneratedArtifactInTransaction(workspaceId, generationJobId)
    }

    /**
     * Persists diagram + version + renderings in one transaction. A reclaimed job uses the same
     * deterministic ids and unique job binding, so the first complete commit wins and every retry
     * reuses it instead of creating duplicate artifacts.
     */
    @Suppress("LongParameterList")
    suspend fun persistGeneratedArtifact(
        workspaceId: UUID,
        projectId: UUID,
        diagramId: UUID,
        generationJobId: UUID?,
        title: String,
        diagramType: String,
        irJson: String,
        validationReportJson: String,
        evidenceCoverageJson: String,
        sourceSnapshotJson: String,
        modelTraceJson: String,
        rendererVersion: String,
        layoutEngineVersion: String,
        renderings: List<Pair<String, String>>,
        createdBy: UUID?
    ): GeneratedDiagramArtifact {
        identities?.requireProject(workspaceId, projectId)
        identities?.requireMember(workspaceId, createdBy)

        generationJobId?.let { jobId ->
            findGeneratedArtifact(workspaceId, jobId)?.let { return it }
        }

        try {
            return txc.tx {
                generationJobId?.let { jobId ->
                    findGeneratedArtifactInTransaction(workspaceId, jobId)?.let {
                        return@tx it
                    }
                }

                val now = Instant.now()
                val versionId =
                    generationJobId?.let {
                        deterministicUuid("diagram-version", workspaceId, it)
                    } ?: UUID.randomUUID()

                DiagramsTable.insert {
                    it[id] = diagramId
                    it[DiagramsTable.workspaceId] = workspaceId
                    it[DiagramsTable.projectId] = projectId
                    it[DiagramsTable.title] = title
                    it[DiagramsTable.diagramType] = diagramType
                    it[status] = "draft"
                    it[DiagramsTable.generationJobId] = generationJobId
                    it[DiagramsTable.createdBy] = createdBy
                    it[createdAt] = now
                    it[updatedAt] = now
                }

                DiagramVersionsTable.insert {
                    it[id] = versionId
                    it[DiagramVersionsTable.workspaceId] = workspaceId
                    it[DiagramVersionsTable.diagramId] = diagramId
                    it[versionNumber] = 1
                    it[cause] = "GENERATED"
                    it[ir] = irJson
                    it[validationReport] = validationReportJson
                    it[evidenceCoverage] = evidenceCoverageJson
                    it[sourceSnapshot] = sourceSnapshotJson
                    it[modelTrace] = modelTraceJson
                    it[DiagramVersionsTable.rendererVersion] = rendererVersion
                    it[DiagramVersionsTable.layoutEngineVersion] = layoutEngineVersion
                    it[DiagramVersionsTable.createdBy] = createdBy
                    it[createdAt] = now
                }

                val renderingRecords =
                    renderings.map { (format, content) ->
                        val hash = sha256(content)
                        val renderingId =
                            generationJobId?.let {
                                deterministicUuid("rendering:$format:$hash", workspaceId, it)
                            } ?: UUID.randomUUID()
                        RenderingsTable.insert {
                            it[id] = renderingId
                            it[RenderingsTable.workspaceId] = workspaceId
                            it[diagramVersionId] = versionId
                            it[RenderingsTable.format] = format
                            it[objectKey] = null
                            it[contentText] = content
                            it[contentHash] = hash
                            it[renderStatus] = "ready"
                            it[renderWarnings] = "[]"
                            it[createdAt] = now
                        }
                        RenderingRecord(
                            renderingId,
                            workspaceId,
                            versionId,
                            format,
                            content,
                            hash,
                            "ready"
                        )
                    }

                GeneratedDiagramArtifact(
                    diagram =
                    DiagramRecord(
                        diagramId,
                        workspaceId,
                        projectId,
                        title,
                        diagramType,
                        "draft",
                        createdBy,
                        generationJobId
                    ),
                    version =
                    DiagramVersionRecord(
                        versionId,
                        workspaceId,
                        diagramId,
                        1,
                        "GENERATED",
                        irJson,
                        validationReportJson,
                        evidenceCoverageJson,
                        sourceSnapshotJson,
                        modelTraceJson,
                        createdBy
                    ),
                    renderings = renderingRecords
                )
            }
        } catch (failure: Throwable) {
            // A lease-expiry race may let two attempts reach the unique job binding. The winning
            // transaction is authoritative; recover it in a fresh snapshot after SQLSTATE 23505.
            if (generationJobId != null && failure.isUniqueViolation()) {
                findGeneratedArtifact(workspaceId, generationJobId)?.let { return it }
            }
            throw failure
        }
    }

    suspend fun appendVersion(
        workspaceId: UUID,
        diagramId: UUID,
        cause: String,
        irJson: String,
        validationReportJson: String,
        evidenceCoverageJson: String,
        sourceSnapshotJson: String,
        modelTraceJson: String,
        rendererVersion: String,
        layoutEngineVersion: String,
        createdBy: UUID?
    ): DiagramVersionRecord {
        identities?.requireDiagram(workspaceId, diagramId)
        identities?.requireMember(workspaceId, createdBy)
        return txc.tx {
            val nextVersion = (currentMaxVersion(workspaceId, diagramId) ?: 0) + 1
            val newId = UUID.randomUUID()
            DiagramVersionsTable.insert {
                it[id] = newId
                it[DiagramVersionsTable.workspaceId] = workspaceId
                it[DiagramVersionsTable.diagramId] = diagramId
                it[versionNumber] = nextVersion
                it[DiagramVersionsTable.cause] = cause
                it[ir] = irJson
                it[validationReport] = validationReportJson
                it[evidenceCoverage] = evidenceCoverageJson
                it[sourceSnapshot] = sourceSnapshotJson
                it[modelTrace] = modelTraceJson
                it[DiagramVersionsTable.rendererVersion] = rendererVersion
                it[DiagramVersionsTable.layoutEngineVersion] = layoutEngineVersion
                it[DiagramVersionsTable.createdBy] = createdBy
                it[createdAt] = Instant.now()
            }
            DiagramVersionRecord(
                newId,
                workspaceId,
                diagramId,
                nextVersion,
                cause,
                irJson,
                validationReportJson,
                evidenceCoverageJson,
                sourceSnapshotJson,
                modelTraceJson,
                createdBy
            )
        }
    }

    /** Persists the exact code renderings requested by the generation job. */
    suspend fun saveRenderings(
        workspaceId: UUID,
        diagramVersionId: UUID,
        renderings: List<Pair<String, String>>
    ): List<RenderingRecord> = txc.tx {
        val ownedVersion =
            DiagramVersionsTable.select {
                (DiagramVersionsTable.id eq diagramVersionId) and
                    (DiagramVersionsTable.workspaceId eq workspaceId)
            }
                .limit(1)
                .any()
        if (!ownedVersion) {
            throw com.potaty.backend.persistence.TenantIntegrityException(
                "Diagram version is not available in the authenticated workspace"
            )
        }

        val now = Instant.now()
        renderings.map { (format, content) ->
            val newId = UUID.randomUUID()
            val hash = sha256(content)
            RenderingsTable.insert {
                it[id] = newId
                it[RenderingsTable.workspaceId] = workspaceId
                it[RenderingsTable.diagramVersionId] = diagramVersionId
                it[RenderingsTable.format] = format
                it[objectKey] = null
                it[contentText] = content
                it[contentHash] = hash
                it[renderStatus] = "ready"
                it[renderWarnings] = "[]"
                it[createdAt] = now
            }
            RenderingRecord(
                id = newId,
                workspaceId = workspaceId,
                diagramVersionId = diagramVersionId,
                format = format,
                contentText = content,
                contentHash = hash,
                renderStatus = "ready"
            )
        }
    }

    /** Returns only renderings belonging to the tenant-owned version, in stable format order. */
    suspend fun listRenderings(
        workspaceId: UUID,
        diagramVersionId: UUID
    ): List<RenderingRecord> = txc.tx {
        RenderingsTable.select {
            (RenderingsTable.workspaceId eq workspaceId) and
                (RenderingsTable.diagramVersionId eq diagramVersionId)
        }
            .orderBy(RenderingsTable.format to SortOrder.ASC)
            .map {
                RenderingRecord(
                    id = it[RenderingsTable.id],
                    workspaceId = it[RenderingsTable.workspaceId],
                    diagramVersionId = it[RenderingsTable.diagramVersionId],
                    format = it[RenderingsTable.format],
                    contentText = it[RenderingsTable.contentText],
                    contentHash = it[RenderingsTable.contentHash],
                    renderStatus = it[RenderingsTable.renderStatus]
                )
            }
    }

    private fun currentMaxVersion(workspaceId: UUID, diagramId: UUID): Int? =
        DiagramVersionsTable.select {
            (DiagramVersionsTable.workspaceId eq workspaceId) and
                (DiagramVersionsTable.diagramId eq diagramId)
        }
            .orderBy(DiagramVersionsTable.versionNumber to SortOrder.DESC)
            .limit(1)
            .map { it[DiagramVersionsTable.versionNumber] }
            .singleOrNull()

    suspend fun findVersion(
        workspaceId: UUID,
        diagramId: UUID,
        versionId: UUID
    ): DiagramVersionRecord? = txc.tx {
        DiagramVersionsTable.select {
            (DiagramVersionsTable.id eq versionId) and
                (DiagramVersionsTable.diagramId eq diagramId) and
                (DiagramVersionsTable.workspaceId eq workspaceId)
        }
            .limit(1)
            .map {
                DiagramVersionRecord(
                    id = it[DiagramVersionsTable.id],
                    workspaceId = it[DiagramVersionsTable.workspaceId],
                    diagramId = it[DiagramVersionsTable.diagramId],
                    versionNumber = it[DiagramVersionsTable.versionNumber],
                    cause = it[DiagramVersionsTable.cause],
                    irJson = it[DiagramVersionsTable.ir],
                    validationReportJson = it[DiagramVersionsTable.validationReport],
                    evidenceCoverageJson = it[DiagramVersionsTable.evidenceCoverage],
                    sourceSnapshotJson = it[DiagramVersionsTable.sourceSnapshot],
                    modelTraceJson = it[DiagramVersionsTable.modelTrace],
                    createdBy = it[DiagramVersionsTable.createdBy]
                )
            }
            .singleOrNull()
    }

    /** The newest version of a diagram (highest version_number), tenant-scoped. */
    suspend fun findLatestVersion(workspaceId: UUID, diagramId: UUID): DiagramVersionRecord? =
        txc.tx {
            DiagramVersionsTable.select {
                (DiagramVersionsTable.workspaceId eq workspaceId) and
                    (DiagramVersionsTable.diagramId eq diagramId)
            }
                .orderBy(DiagramVersionsTable.versionNumber to SortOrder.DESC)
                .limit(1)
                .map {
                    DiagramVersionRecord(
                        id = it[DiagramVersionsTable.id],
                        workspaceId = it[DiagramVersionsTable.workspaceId],
                        diagramId = it[DiagramVersionsTable.diagramId],
                        versionNumber = it[DiagramVersionsTable.versionNumber],
                        cause = it[DiagramVersionsTable.cause],
                        irJson = it[DiagramVersionsTable.ir],
                        validationReportJson = it[DiagramVersionsTable.validationReport],
                        evidenceCoverageJson = it[DiagramVersionsTable.evidenceCoverage],
                        sourceSnapshotJson = it[DiagramVersionsTable.sourceSnapshot],
                        modelTraceJson = it[DiagramVersionsTable.modelTrace],
                        createdBy = it[DiagramVersionsTable.createdBy]
                    )
                }
                .singleOrNull()
        }

    suspend fun findDiagram(workspaceId: UUID, diagramId: UUID): DiagramRecord? = txc.tx {
        DiagramsTable.select {
            (DiagramsTable.id eq diagramId) and (DiagramsTable.workspaceId eq workspaceId)
        }
            .limit(1)
            .map {
                DiagramRecord(
                    id = it[DiagramsTable.id],
                    workspaceId = it[DiagramsTable.workspaceId],
                    projectId = it[DiagramsTable.projectId],
                    title = it[DiagramsTable.title],
                    diagramType = it[DiagramsTable.diagramType],
                    status = it[DiagramsTable.status],
                    createdBy = it[DiagramsTable.createdBy],
                    generationJobId = it[DiagramsTable.generationJobId]
                )
            }
            .singleOrNull()
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return "sha256:" + digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun findGeneratedArtifactInTransaction(
        workspaceId: UUID,
        generationJobId: UUID
    ): GeneratedDiagramArtifact? {
        val diagram =
            DiagramsTable.select {
                (DiagramsTable.workspaceId eq workspaceId) and
                    (DiagramsTable.generationJobId eq generationJobId)
            }
                .limit(1)
                .map {
                    DiagramRecord(
                        id = it[DiagramsTable.id],
                        workspaceId = it[DiagramsTable.workspaceId],
                        projectId = it[DiagramsTable.projectId],
                        title = it[DiagramsTable.title],
                        diagramType = it[DiagramsTable.diagramType],
                        status = it[DiagramsTable.status],
                        createdBy = it[DiagramsTable.createdBy],
                        generationJobId = it[DiagramsTable.generationJobId]
                    )
                }
                .singleOrNull()
                ?: return null

        val version =
            DiagramVersionsTable.select {
                (DiagramVersionsTable.workspaceId eq workspaceId) and
                    (DiagramVersionsTable.diagramId eq diagram.id)
            }
                .orderBy(DiagramVersionsTable.versionNumber to SortOrder.DESC)
                .limit(1)
                .map {
                    DiagramVersionRecord(
                        id = it[DiagramVersionsTable.id],
                        workspaceId = it[DiagramVersionsTable.workspaceId],
                        diagramId = it[DiagramVersionsTable.diagramId],
                        versionNumber = it[DiagramVersionsTable.versionNumber],
                        cause = it[DiagramVersionsTable.cause],
                        irJson = it[DiagramVersionsTable.ir],
                        validationReportJson = it[DiagramVersionsTable.validationReport],
                        evidenceCoverageJson = it[DiagramVersionsTable.evidenceCoverage],
                        sourceSnapshotJson = it[DiagramVersionsTable.sourceSnapshot],
                        modelTraceJson = it[DiagramVersionsTable.modelTrace],
                        createdBy = it[DiagramVersionsTable.createdBy]
                    )
                }
                .singleOrNull()
                ?: return null

        val renderings =
            RenderingsTable.select {
                (RenderingsTable.workspaceId eq workspaceId) and
                    (RenderingsTable.diagramVersionId eq version.id)
            }
                .orderBy(RenderingsTable.format to SortOrder.ASC)
                .map {
                    RenderingRecord(
                        id = it[RenderingsTable.id],
                        workspaceId = it[RenderingsTable.workspaceId],
                        diagramVersionId = it[RenderingsTable.diagramVersionId],
                        format = it[RenderingsTable.format],
                        contentText = it[RenderingsTable.contentText],
                        contentHash = it[RenderingsTable.contentHash],
                        renderStatus = it[RenderingsTable.renderStatus]
                    )
                }

        return GeneratedDiagramArtifact(diagram, version, renderings)
    }

    private fun deterministicUuid(kind: String, workspaceId: UUID, jobId: UUID): UUID {
        val bytes =
            MessageDigest.getInstance("SHA-256")
                .digest("potaty:$kind:$workspaceId:$jobId".toByteArray(Charsets.UTF_8))
                .copyOf(16)
        // RFC 9562-compatible variant with a locally assigned version-8 identifier.
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x80).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        val hex = bytes.joinToString("") { "%02x".format(it) }
        return UUID.fromString(
            "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
                "${hex.substring(16, 20)}-${hex.substring(20)}"
        )
    }

    private fun Throwable.isUniqueViolation(): Boolean {
        var current: Throwable? = this
        repeat(16) {
            val failure = current ?: return false
            if (failure is SQLException && failure.sqlState == "23505") return true
            current = failure.cause?.takeUnless { it === failure }
        }
        return false
    }
}
