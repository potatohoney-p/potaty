/*
 * Copyright (c) 2026, Potaty
 *
 * Trust-boundary merge for optional model enrichment.
 */

package com.potaty.backend.extraction

import com.potaty.ir.EdgeSourceType

data class ExtractionMerge(
    val extraction: ExtractionResult,
    val inferredEntityCount: Int,
    val inferredRelationCount: Int
) {
    val hasInferredAdditions: Boolean
        get() = inferredEntityCount > 0 || inferredRelationCount > 0
}

object ExtractionMerger {
    private const val INFERRED_CONFIDENCE = 0.55

    /**
     * Keeps [grounded] authoritative and only appends genuinely new model suggestions. Evidence
     * supplied by the model is always discarded; it is not a source citation.
     */
    fun merge(grounded: ExtractionResult, inferred: ExtractionResult): ExtractionMerge {
        val entities = LinkedHashMap<String, ExtractedEntity>()
        grounded.entities.forEach { entities.putIfAbsent(it.key, it) }

        val aliases = HashMap<String, String>()
        entities.keys.forEach { aliases[it] = it }
        val keyByLabel = entities.values.associate { canonical(it.label) to it.key }.toMutableMap()
        var addedEntities = 0

        inferred.entities.forEach { candidate ->
            val existingKey = entities[candidate.key]?.key ?: keyByLabel[canonical(candidate.label)]
            if (existingKey != null) {
                aliases[candidate.key] = existingKey
                return@forEach
            }

            val safe = candidate.copy(
                evidence = emptyList(),
                confidence = INFERRED_CONFIDENCE
            )
            entities[safe.key] = safe
            aliases[candidate.key] = safe.key
            keyByLabel[canonical(safe.label)] = safe.key
            addedEntities++
        }

        val relations = grounded.relations.toMutableList()
        val relationKeys = grounded.relations
            .mapTo(LinkedHashSet()) { RelationKey(it.fromKey, it.toKey, it.type.name) }
        var addedRelations = 0

        inferred.relations.forEach { candidate ->
            val from = aliases[candidate.fromKey] ?: candidate.fromKey.takeIf { it in entities }
            val to = aliases[candidate.toKey] ?: candidate.toKey.takeIf { it in entities }
            if (from == null || to == null || from == to) return@forEach
            val key = RelationKey(from, to, candidate.type.name)
            if (!relationKeys.add(key)) return@forEach

            relations += candidate.copy(
                fromKey = from,
                toKey = to,
                evidence = emptyList(),
                confidence = INFERRED_CONFIDENCE,
                edgeSourceType = EdgeSourceType.LLM_INFERRED
            )
            addedRelations++
        }

        return ExtractionMerge(
            extraction = ExtractionResult(entities.values.toList(), relations),
            inferredEntityCount = addedEntities,
            inferredRelationCount = addedRelations
        )
    }

    private data class RelationKey(val from: String, val to: String, val type: String)

    private fun canonical(value: String): String =
        value.lowercase().replace(Regex("""\s+"""), " ").trim()
}
