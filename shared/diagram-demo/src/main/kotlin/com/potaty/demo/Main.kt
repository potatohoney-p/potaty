/*
 * Copyright (c) 2026, Potaty
 */

package com.potaty.demo

import com.potaty.ir.EvidenceCoverageScorer
import com.potaty.ir.IrValidator
import com.potaty.render.ascii.AsciiRenderer

/**
 * Renders every sample diagram to ASCII and prints validation + coverage + layout-quality stats.
 * Run with: `gradlew :diagram-demo:jsNodeDevelopmentRun` (or node on the built bundle).
 */
fun main() {
    val renderer = AsciiRenderer()
    val validator = IrValidator()

    for ((name, ir) in SampleDiagrams.all()) {
        val bar = "=".repeat(70)
        println(bar)
        println("$name  [type=${ir.diagramType}, style=${ir.styleHints.styleProfile}]")
        println(bar)

        val output = renderer.render(ir)
        println(output.text)
        println()

        val report = validator.validate(ir)
        val coverage = EvidenceCoverageScorer.score(ir)
        println(
            "valid=${report.isValid} publishable=${report.isPublishable} " +
                "errors=${report.errors.size} warnings=${report.warnings.size}"
        )
        println(
            "nodeCoverage=${pct(coverage.nodeCoverage)} " +
                "edgeCoverage=${pct(coverage.edgeCoverage)} " +
                "groundedEdges=${pct(coverage.groundedEdgeRatio)}"
        )
        println(
            "layout: nodes=${output.quality.nodeCount} edges=${output.quality.edgeCount} " +
                "overlaps=${output.quality.overlapCount} " +
                "crossings=${output.quality.edgeCrossingCount} " +
                "acceptable=${output.quality.isAcceptable()} engine=${output.layoutEngineVersion}"
        )
        report.issues.forEach { println("  - [${it.severity}] ${it.code} ${it.message}") }
        println()
    }
}

private fun pct(value: Double): String = "${(value * 100).toInt()}%"
