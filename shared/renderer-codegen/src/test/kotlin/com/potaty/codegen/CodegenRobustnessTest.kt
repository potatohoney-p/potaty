/*
 * Copyright (c) 2026, Potaty
 */

package com.potaty.codegen

import com.potaty.ir.DiagramIR
import com.potaty.ir.DiagramType
import com.potaty.ir.EdgeSourceType
import com.potaty.ir.EdgeType
import com.potaty.ir.EvidenceRef
import com.potaty.ir.IrEdge
import com.potaty.ir.IrNode
import com.potaty.ir.NodeType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Robustness / contract tests for the renderer-codegen helpers. These lock in the behaviour that the
 * P2/P3 review findings flagged (ER cardinality enum mapping, IdentAllocator empty-id totality, the
 * lossy PlantUML angle-bracket transform, Markdown escaping, and percentage formatting) so future
 * refactors cannot regress them.
 */
class CodegenRobustnessTest {

    private fun ev() = EvidenceRef(sourceChunkId = "chk")

    private fun entity(id: String) =
        IrNode(id = id, label = id, type = NodeType.ENTITY, evidence = listOf(ev()))

    private fun erIr(edgeType: EdgeType): DiagramIR = DiagramIR(
        diagramId = "er",
        title = "Schema",
        diagramType = DiagramType.ER,
        nodes = listOf(entity("A"), entity("B")),
        edges = listOf(
            IrEdge(
                id = "r1",
                from = "A",
                to = "B",
                type = edgeType,
                label = "rel",
                edgeSourceType = EdgeSourceType.DATABASE_RELATION,
                evidence = listOf(ev())
            )
        )
    )

    // ----------------------------------------- MermaidCompiler ER cardinality (finding #23)

    @Test
    fun mermaidErCardinalityMapsByEnumNotString() {
        assertTrue(MermaidCompiler.compile(erIr(EdgeType.HAS_MANY)).contains("A ||--o{ B"))
        assertTrue(MermaidCompiler.compile(erIr(EdgeType.HAS_ONE)).contains("A ||--|| B"))
        assertTrue(MermaidCompiler.compile(erIr(EdgeType.REFERENCES)).contains("A }o--|| B"))
        assertTrue(MermaidCompiler.compile(erIr(EdgeType.DEPENDS_ON)).contains("A }o--|| B"))
        // Any other edge type falls through to the non-identifying many-to-one default.
        assertTrue(MermaidCompiler.compile(erIr(EdgeType.RELATES_TO)).contains("A ||--o{ B"))
    }

    // ----------------------------------------- IdentAllocator empty-id totality (finding #5)

    @Test
    fun identAllocatorIsTotalForEmptyRawId() {
        val ids = IdentAllocator()
        val first = ids.identify("")
        assertEquals("n_", first, "empty raw id maps to the safe base 'n_'")
        // Stable / memoised: same (empty) raw id always resolves to the same identifier.
        assertEquals(first, ids.identify(""))
        // A different raw id that also sanitises to 'n_' must be disambiguated, not merged.
        val second = ids.identify("!")
        assertTrue(second != first, "distinct raw ids never collide: $first vs $second")
        assertTrue(second.startsWith("n_"), "disambiguated from the same base: $second")
    }

    // ----------------------------------------- LabelEscaper.plantUml lossy transform (finding #22)

    @Test
    fun plantUmlNeutralisesAnglesToParensWithoutDroppingText() {
        val out = LabelEscaper.plantUml("<b>bold</b>")
        assertFalse(out.contains('<'), "no raw '<' that could start creole/HTML markup: $out")
        assertFalse(out.contains('>'), "no raw '>': $out")
        // The transform is lossy-by-substitution, not deletion: surrounding text survives.
        assertTrue(out.contains("bold"), "label text preserved: $out")
        assertTrue(out.contains("(b)"), "'<b>' rewritten to '(b)': $out")
    }

    // ----------------------------------------- LabelEscaper.markdown escaping (finding #25)

    @Test
    fun markdownEscapesStructuralCharsAndIsDeterministicallyDoubleEscaping() {
        // Raw text: every structural char gets a single backslash.
        assertEquals("\\*x\\*", LabelEscaper.markdown("*x*"))
        // Documented caveat: already-escaped input is double-escaped (no lookahead). Pinning this so
        // the "raw input only" contract is explicit and cannot silently change.
        assertEquals("\\\\\\*", LabelEscaper.markdown("\\*"))
    }

    // ----------------------------------------- MarkdownExporter percentage formatting (finding #4)

    @Test
    fun coveragePercentagesRenderWithOneDecimalAndRoundHalfUp() {
        // Node coverage is 1 of 2 = 50.0%; format must be N.N% with exactly one decimal.
        val ir = DiagramIR(
            diagramId = "p",
            title = "Pct",
            diagramType = DiagramType.ARCHITECTURE,
            nodes = listOf(
                entity("a"),
                IrNode(id = "b", label = "b", type = NodeType.GENERIC) // no evidence -> uncovered
            ),
            edges = emptyList()
        )
        val md = MarkdownExporter.export(ir)
        assertTrue(Regex("\\| Node evidence coverage \\| \\d+\\.\\d% \\|").containsMatchIn(md), md)
        assertTrue(md.contains("| Node evidence coverage | 50.0% |"), "1/2 -> 50.0%: $md")
    }
}
