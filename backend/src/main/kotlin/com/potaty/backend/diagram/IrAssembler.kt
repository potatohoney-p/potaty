/*
 * Copyright (c) 2026, Potaty
 *
 * Assembles a canonical [DiagramIR] from an [ExtractionResult] (plan section 2.1: IR is the
 * canonical artifact). Per-item confidence, evidence, and edge source provenance are preserved.
 *
 * Node ids are allocated injectively from the entity label (sanitised, de-duplicated with a
 * numeric suffix) so distinct entities never collapse. Edges reference those ids. Stable keys are
 * derived from the canonical entity key / relation triple so they survive regeneration.
 */

package com.potaty.backend.diagram

import com.potaty.backend.extraction.ExtractionResult
import com.potaty.ir.DiagramIR
import com.potaty.ir.DiagramType
import com.potaty.ir.IrEdge
import com.potaty.ir.IrNode
import com.potaty.ir.IrProvenance

object IrAssembler {

    fun assemble(
        diagramId: String,
        title: String,
        diagramType: DiagramType,
        objective: String?,
        sourceSnapshotIds: List<String>,
        extraction: ExtractionResult,
        generatedBy: String = "deterministic-extractor"
    ): DiagramIR {
        val idByKey = HashMap<String, String>()
        val taken = HashSet<String>()

        fun idFor(key: String, label: String): String {
            idByKey[key]?.let { return it }
            val base = sanitize(label.ifBlank { key })
            var candidate = base
            var n = 2
            while (!taken.add(candidate)) {
                candidate = base + "_" + n
                n++
            }
            idByKey[key] = candidate
            return candidate
        }

        val nodes = extraction.entities.map { e ->
            IrNode(
                id = idFor(e.key, e.label),
                stableKey = "entity:${e.key}",
                label = e.label,
                type = e.type,
                confidence = e.confidence,
                evidence = e.evidence
            )
        }

        var edgeIndex = 0
        val edges = extraction.relations.mapNotNull { r ->
            val from = idByKey[r.fromKey] ?: return@mapNotNull null
            val to = idByKey[r.toKey] ?: return@mapNotNull null
            IrEdge(
                id = "e${edgeIndex++}",
                stableKey = "rel:${r.fromKey}->${r.toKey}:${r.type.name}",
                from = from,
                to = to,
                type = r.type,
                label = r.label,
                confidence = r.confidence,
                evidence = r.evidence,
                edgeSourceType = r.edgeSourceType
            )
        }

        return DiagramIR(
            diagramId = diagramId,
            title = title,
            objective = objective,
            diagramType = diagramType,
            sourceSnapshotIds = sourceSnapshotIds,
            nodes = nodes,
            edges = edges,
            provenance = IrProvenance(generatedBy = generatedBy)
        )
    }

    private fun sanitize(s: String): String {
        if (s.isEmpty()) return "n_"
        val sb = StringBuilder(s.length + 2)
        if (!(s[0].isLetter() || s[0] == '_')) sb.append("n_")
        for (ch in s) sb.append(if (ch.isLetterOrDigit() || ch == '_') ch else '_')
        return sb.toString().trim('_').ifEmpty { "n_" }
    }
}
