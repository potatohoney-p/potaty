/*
 * Copyright (c) 2026, Potaty
 */

package com.potaty.codegen

import com.potaty.ir.DiagramIR
import com.potaty.ir.EvidenceCoverage
import com.potaty.ir.EvidenceCoverageScorer
import com.potaty.ir.IrValidator
import com.potaty.ir.ValidationReport

/**
 * Produces a `docs/architecture.md`-style Markdown document for a [DiagramIR].
 *
 * The document embeds:
 * 1. a title + objective,
 * 2. an **AI-generated disclosure** banner (plan section 18.6) — diagrams are machine-generated and
 *    must be reviewed before being trusted,
 * 3. the diagram itself as a fenced ```mermaid code block (rendered inline by GitHub),
 * 4. an **evidence & validation summary** table sourced from [EvidenceCoverageScorer] and
 *    [IrValidator] so a reviewer can see grounding/coverage at a glance,
 * 5. any unsupported claims and warnings the IR carries.
 *
 * Pure string assembly; no I/O. JS-compatible.
 */
object MarkdownExporter {

    private const val DISCLOSURE =
        "> **AI-generated diagram.** This diagram was produced automatically " +
            "from source material by Potaty. It may contain inaccuracies or " +
            "inferred relationships. Review it against the cited evidence before " +
            "relying on it. Edges drawn dotted are inferred or low-confidence."

    fun export(ir: DiagramIR): String {
        val coverage = EvidenceCoverageScorer.score(ir)
        val report = IrValidator().validate(ir)
        val mermaid = MermaidCompiler.compile(ir)

        val sb = StringBuilder()

        // 1. Title + objective
        sb.append("# ").append(LabelEscaper.markdown(ir.title)).append("\n\n")
        ir.objective
            ?.takeIf { it.isNotBlank() }
            ?.let {
                sb.append(LabelEscaper.markdown(it)).append("\n\n")
            }

        // 2. AI-generated disclosure (plan 18.6)
        sb.append(DISCLOSURE).append("\n\n")

        // 3. The diagram, as a Mermaid code block.
        sb.append("## Diagram\n\n")
        sb.append("```mermaid\n").append(mermaid).append("\n```\n\n")

        // 4. Evidence & validation summary.
        sb.append(validationSection(ir, coverage, report))

        // 5. Unsupported claims / warnings.
        sb.append(claimsSection(ir))

        // 6. Provenance footer.
        sb.append(provenanceSection(ir))

        return sb.toString().trimEnd('\n') + "\n"
    }

    private fun validationSection(
        ir: DiagramIR,
        coverage: EvidenceCoverage,
        report: ValidationReport
    ): String {
        val sb = StringBuilder()
        sb.append("## Evidence & validation\n\n")
        sb.append("| Metric | Value |\n")
        sb.append("| --- | --- |\n")
        sb.append("| Nodes | ").append(ir.nodes.size).append(" |\n")
        sb.append("| Edges | ").append(ir.edges.size).append(" |\n")
        sb.append("| Node evidence coverage | ").append(pct(coverage.nodeCoverage)).append(" |\n")
        sb.append("| Edge evidence coverage | ").append(pct(coverage.edgeCoverage)).append(" |\n")
        sb.append("| Grounded edge ratio | ").append(pct(coverage.groundedEdgeRatio)).append(" |\n")
        sb.append("| Inferred edges | ").append(coverage.inferredEdgeCount).append(" |\n")
        sb.append("| Low-confidence nodes | ")
            .append(coverage.lowConfidenceNodeCount)
            .append(" |\n")
        sb.append("| Low-confidence edges | ")
            .append(coverage.lowConfidenceEdgeCount)
            .append(" |\n")
        sb.append("| Unsupported critical claims | ")
            .append(coverage.unsupportedCriticalClaims)
            .append(" |\n")
        sb.append("| Structurally valid | ")
            .append(if (report.isValid) "yes" else "no")
            .append(" |\n")
        sb.append("| Publishable | ")
            .append(if (report.isPublishable) "yes" else "no")
            .append(" |\n")
        sb.append("| Meets publish threshold | ")
            .append(if (coverage.meetsThreshold()) "yes" else "no")
            .append(" |\n")
        sb.append('\n')

        if (report.issues.isNotEmpty()) {
            sb.append("### Validation issues\n\n")
            for (issue in report.issues) {
                val sev = issue.severity.name.lowercase()
                val target = issue.targetId?.let { " (`$it`)" } ?: ""
                sb.append("- **")
                    .append(sev)
                    .append("** `")
                    .append(issue.code)
                    .append("`")
                    .append(target)
                    .append(": ")
                    .append(LabelEscaper.markdown(issue.message))
                    .append('\n')
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun claimsSection(ir: DiagramIR): String {
        if (ir.unsupportedClaims.isEmpty() && ir.warnings.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("## Unsupported claims & warnings\n\n")
        for (claim in ir.unsupportedClaims) {
            sb.append("- **")
                .append(claim.severity.name.lowercase())
                .append("**: ")
                .append(LabelEscaper.markdown(claim.claim))
            if (claim.reason.isNotBlank()) {
                sb.append(" — ").append(LabelEscaper.markdown(claim.reason))
            }
            sb.append('\n')
        }
        for (warning in ir.warnings) {
            sb.append("- **warning** `")
                .append(warning.code)
                .append("`: ")
                .append(LabelEscaper.markdown(warning.message))
                .append('\n')
        }
        sb.append('\n')
        return sb.toString()
    }

    private fun provenanceSection(ir: DiagramIR): String {
        val p = ir.provenance
        val hasAny =
            p.generatedBy != null ||
                p.rendererVersion != null ||
                p.layoutEngineVersion != null ||
                p.modelTrace.isNotEmpty()
        if (!hasAny && ir.sourceSnapshotIds.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("## Provenance\n\n")
        p.generatedBy?.let {
            sb.append("- Generated by: ").append(LabelEscaper.markdown(it)).append('\n')
        }
        p.rendererVersion?.let {
            sb.append("- Renderer: ").append(LabelEscaper.markdown(it)).append('\n')
        }
        p.layoutEngineVersion?.let {
            sb.append("- Layout engine: ").append(LabelEscaper.markdown(it)).append('\n')
        }
        if (ir.sourceSnapshotIds.isNotEmpty()) {
            sb.append("- Source snapshots: ")
                .append(ir.sourceSnapshotIds.joinToString(", ") { "`$it`" })
                .append('\n')
        }
        sb.append('\n')
        return sb.toString()
    }

    /**
     * Formats a 0..1 ratio as a percentage with exactly one decimal place (e.g. `0.333` ->
     * `33.3%`).
     *
     * Precision contract: this is a HUMAN-READABLE display value, rounded half-up to one decimal,
     * and is therefore approximate (up to ±0.05 percentage points vs. the exact ratio). It is not
     * intended for machine parsing — consumers that need the exact figure should read the
     * underlying [EvidenceCoverage] / [ValidationReport] numbers, not scrape this string. Rounding
     * uses integer arithmetic (no `Double.toString`/locale formatting) so output is identical on
     * Kotlin/JVM and Kotlin/JS.
     */
    private fun pct(value: Double): String {
        val scaled = (value * 1000.0)
        val rounded = (scaled + (if (scaled >= 0) 0.5 else -0.5)).toInt() // round half-up
        val whole = rounded / 10
        val frac = rounded % 10
        return "$whole.$frac%"
    }
}
