/*
 * Copyright (c) 2026, Potaty
 */

package com.potaty.backend

import com.potaty.backend.api.JobScope
import com.potaty.backend.extraction.ExtractedEntity
import com.potaty.backend.extraction.ExtractedRelation
import com.potaty.backend.extraction.ExtractionResult
import com.potaty.backend.extraction.ExtractionScopeReducer
import com.potaty.ir.EdgeType
import com.potaty.ir.NodeType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExtractionScopeReducerTest {

    @Test
    fun executiveDetailCapsAtEightConnectedEntitiesDeterministically() {
        val entities = (1..20).map { index -> entity("node-$index") }
        val relations = (1 until 20).map { index -> relation("node-$index", "node-${index + 1}") }

        val result =
            ExtractionScopeReducer.reduce(
                ExtractionResult(entities, relations),
                JobScope(abstractionLevel = "high")
            )

        assertEquals(8, result.entities.size)
        assertTrue(
            result.relations.all { edge ->
                result.entities.any { it.key == edge.fromKey } &&
                    result.entities.any { it.key == edge.toKey }
            }
        )
        assertEquals(
            result.entities,
            ExtractionScopeReducer.reduce(
                ExtractionResult(entities, relations),
                JobScope(abstractionLevel = "high")
            )
                .entities
        )
    }

    @Test
    fun deepTechnicalAllowsThirtyTwoEntities() {
        val source = ExtractionResult((1..28).map { entity("module-$it") }, emptyList())
        assertEquals(
            28,
            ExtractionScopeReducer.reduce(source, JobScope(abstractionLevel = "low")).entities.size
        )
        assertEquals(
            16,
            ExtractionScopeReducer.reduce(source, JobScope(abstractionLevel = "medium"))
                .entities
                .size
        )
    }

    @Test
    fun includeKeepsMatchedEntityAndOneHopContextWhileExcludeWins() {
        val source =
            ExtractionResult(
                entities =
                listOf(
                    entity("browser"),
                    entity("api"),
                    entity("database"),
                    entity("analytics")
                ),
                relations =
                listOf(
                    relation("browser", "api"),
                    relation("api", "database"),
                    relation("api", "analytics")
                )
            )

        val result =
            ExtractionScopeReducer.reduce(
                source,
                JobScope(
                    include = listOf("api"),
                    exclude = listOf("analytics"),
                    abstractionLevel = "medium"
                )
            )

        assertEquals(setOf("browser", "api", "database"), result.entities.map { it.key }.toSet())
        assertFalse(result.entities.any { it.key == "analytics" })
        assertTrue(result.relations.none { it.fromKey == "analytics" || it.toKey == "analytics" })
    }

    private fun entity(key: String) =
        ExtractedEntity(
            key = key,
            label = key,
            type = NodeType.GENERIC,
            evidence = emptyList()
        )

    private fun relation(from: String, to: String) =
        ExtractedRelation(
            fromKey = from,
            toKey = to,
            type = EdgeType.RELATES_TO,
            label = null,
            evidence = emptyList()
        )
}
