/*
 * Copyright (c) 2026, Potaty
 *
 * Deterministic scope/detail reduction. UI detail controls must change the artifact, not merely
 * decorate the request. Selection is stable and evidence-aware: connected entities rank first,
 * explicit include terms receive priority, excluded entities are removed, and only relations whose
 * endpoints survive are retained.
 */

package com.potaty.backend.extraction

import com.potaty.backend.api.JobScope

object ExtractionScopeReducer {

    fun reduce(extraction: ExtractionResult, scope: JobScope): ExtractionResult {
        if (extraction.entities.isEmpty()) return extraction

        val includes = normalizedTerms(scope.include)
        val excludes = normalizedTerms(scope.exclude)
        val byKey = extraction.entities.associateBy { it.key }
        val degree = mutableMapOf<String, Int>()
        extraction.relations.forEach { relation ->
            degree[relation.fromKey] = (degree[relation.fromKey] ?: 0) + 1
            degree[relation.toKey] = (degree[relation.toKey] ?: 0) + 1
        }

        val allowed = extraction.entities.filterNot { entity -> matches(entity, excludes) }
        val includeMatches =
            if (includes.isEmpty()) {
                emptySet()
            } else {
                allowed.filter { matches(it, includes) }.mapTo(linkedSetOf()) { it.key }
            }

        // An include filter with no matches should not silently erase the whole artifact. The
        // caller still gets a useful result and can see that its requested term was absent.
        val candidates =
            if (includeMatches.isEmpty()) {
                allowed
            } else {
                val adjacent =
                    extraction.relations
                        .flatMap { relation ->
                            when {
                                relation.fromKey in includeMatches -> listOf(relation.toKey)
                                relation.toKey in includeMatches -> listOf(relation.fromKey)
                                else -> emptyList()
                            }
                        }
                        .toSet()
                allowed.filter { it.key in includeMatches || it.key in adjacent }
            }

        val limit =
            when (scope.abstractionLevel.trim().lowercase()) {
                "high" -> 8
                "low" -> 32
                else -> 16
            }
        val originalOrder =
            extraction.entities.mapIndexed { index, entity -> entity.key to index }.toMap()
        val selectedKeys =
            candidates
                .sortedWith(
                    compareByDescending<ExtractedEntity> { it.key in includeMatches }
                        .thenByDescending { degree[it.key] ?: 0 }
                        .thenByDescending { it.evidence.size }
                        .thenByDescending { it.confidence }
                        .thenBy { originalOrder[it.key] ?: Int.MAX_VALUE }
                )
                .take(limit)
                .mapTo(linkedSetOf()) { it.key }

        val selectedEntities =
            extraction.entities.filter { it.key in selectedKeys && it.key in byKey }
        val selectedRelations =
            extraction.relations.filter {
                it.fromKey in selectedKeys && it.toKey in selectedKeys
            }
        return ExtractionResult(selectedEntities, selectedRelations)
    }

    private fun normalizedTerms(values: List<String>): List<String> =
        values.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()

    private fun matches(entity: ExtractedEntity, terms: List<String>): Boolean {
        if (terms.isEmpty()) return false
        val haystack = "${entity.key} ${entity.label}".lowercase()
        return terms.any(haystack::contains)
    }
}
