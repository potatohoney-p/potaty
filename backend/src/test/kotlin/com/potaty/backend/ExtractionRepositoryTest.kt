/*
 * Copyright (c) 2026, Potaty
 *
 * H2 round-trip tests for ExtractionRepository (plan 8.3 / 7.1): persist a grounded
 * entity/relation graph and read it back tenant + project scoped, asserting evidence is preserved
 * and that another workspace/project cannot see the rows (tenant isolation, plan 20.5).
 *
 * The repository is exercised directly over a Database built from testConfig() (H2), so it depends
 * only on the new ExtractionTables being registered in Database.ALL_TABLES (see wiring).
 */

package com.potaty.backend

import com.potaty.backend.extraction.ExtractedEntity
import com.potaty.backend.extraction.ExtractedRelation
import com.potaty.backend.extraction.ExtractionResult
import com.potaty.backend.persistence.Database
import com.potaty.backend.persistence.TransactionContext
import com.potaty.backend.persistence.repositories.ExtractionRepository
import com.potaty.ir.EdgeType
import com.potaty.ir.EvidenceRef
import com.potaty.ir.NodeType
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class ExtractionRepositoryTest {

    private fun newRepo(): ExtractionRepository {
        val database = Database.connect(testConfig().database)
        return ExtractionRepository(TransactionContext(database.exposed))
    }

    private fun sampleResult(chunkId: String): ExtractionResult {
        val ev = EvidenceRef(
            sourceChunkId = chunkId,
            path = "notes.txt",
            startLine = 3,
            endLine = 3,
            startMs = 5_000,
            endMs = 9_000,
            speaker = "Alice",
            quote = "API Gateway calls Auth Service",
            quoteHash = "deadbeef"
        )
        val entities = listOf(
            ExtractedEntity("api gateway", "API Gateway", NodeType.GATEWAY, listOf(ev)),
            ExtractedEntity("auth service", "Auth Service", NodeType.SERVICE, listOf(ev))
        )
        val relations = listOf(
            ExtractedRelation("api gateway", "auth service", EdgeType.CALLS, "verify", listOf(ev))
        )
        return ExtractionResult(entities, relations)
    }

    @Test
    fun savesAndReadsBackEntitiesAndRelations() = runBlocking {
        val repo = newRepo()
        val workspaceId = UUID.randomUUID()
        val projectId = UUID.randomUUID()
        val sourceVersionId = UUID.randomUUID()
        val chunkId = UUID.randomUUID().toString()

        val saved = repo.save(workspaceId, projectId, sourceVersionId, sampleResult(chunkId))
        assertEquals(2, saved.entityIds.size)
        assertEquals(1, saved.relationIds.size)

        val entities = repo.listEntities(workspaceId, projectId, sourceVersionId)
        assertEquals(2, entities.size)
        val gateway = entities.first { it.entityKey == "api gateway" }
        assertEquals("API Gateway", gateway.name)
        assertEquals("gateway", gateway.type)
        assertEquals(1.0, gateway.confidence)
        assertEquals(listOf(chunkId), gateway.evidenceChunkIds)
        assertEquals(1, gateway.evidence.size)
        assertEquals(5_000, gateway.evidence.first().startMs)
        assertEquals("Alice", gateway.evidence.first().speaker)

        val relations = repo.listRelations(workspaceId, projectId, sourceVersionId)
        assertEquals(1, relations.size)
        val rel = relations.first()
        assertEquals("api gateway", rel.fromEntityKey)
        assertEquals("auth service", rel.toEntityKey)
        assertEquals("calls", rel.type)
        assertEquals("verify", rel.label)
        assertEquals(listOf(chunkId), rel.evidenceChunkIds)
        assertEquals(5_000, rel.evidence.first().startMs)
    }

    @Test
    fun isolatesByWorkspaceAndProject() = runBlocking {
        val repo = newRepo()
        val workspaceId = UUID.randomUUID()
        val projectId = UUID.randomUUID()
        val chunkId = UUID.randomUUID().toString()
        repo.save(workspaceId, projectId, null, sampleResult(chunkId))

        // Same project but different workspace sees nothing.
        assertTrue(repo.listEntities(UUID.randomUUID(), projectId).isEmpty())
        assertTrue(repo.listRelations(UUID.randomUUID(), projectId).isEmpty())

        // Same workspace but different project sees nothing.
        assertTrue(repo.listEntities(workspaceId, UUID.randomUUID()).isEmpty())
        assertTrue(repo.listRelations(workspaceId, UUID.randomUUID()).isEmpty())

        // Correct tenant + project sees the rows.
        assertEquals(2, repo.listEntities(workspaceId, projectId).size)
        // Filtering by a non-matching source version returns nothing.
        val scoped = repo.listEntities(workspaceId, projectId, sourceVersionId = UUID.randomUUID())
        assertTrue(scoped.isEmpty())
    }

    @Test
    fun nullSourceVersionIsPersistedAndListable() = runBlocking {
        val repo = newRepo()
        val workspaceId = UUID.randomUUID()
        val projectId = UUID.randomUUID()
        repo.save(workspaceId, projectId, null, sampleResult(UUID.randomUUID().toString()))

        val entities = repo.listEntities(workspaceId, projectId)
        assertEquals(2, entities.size)
        assertNotNull(entities.first())
        assertTrue(entities.all { it.sourceVersionId == null })
    }
}
