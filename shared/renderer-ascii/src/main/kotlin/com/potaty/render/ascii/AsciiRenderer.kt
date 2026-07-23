/*
 * Copyright (c) 2026, Potaty
 */

package com.potaty.render.ascii

import com.potaty.bitmap.manager.PotatyBitmapManager
import com.potaty.graphics.board.Highlight
import com.potaty.graphics.board.PotatyBoard
import com.potaty.graphics.geo.Rect
import com.potaty.ir.DiagramIR
import com.potaty.layout.LayoutEngineFactory
import com.potaty.layout.LayoutMetrics
import com.potaty.layout.LayoutQualityScore
import com.potaty.layout.LayoutQualityScorer
import com.potaty.render.IrShapeMapper
import com.potaty.render.StyleProfile
import com.potaty.shape.shape.AbstractShape

/**
 * Renders a [DiagramIR] to ASCII by:
 *  1. running the deterministic layout engine for the diagram type,
 *  2. mapping the layout onto the existing Potaty shape model, and
 *  3. drawing those shapes through the existing [PotatyBoard] (which performs smart line-crossing
 *     merges via CrossingResources) and serializing the bounded window to text.
 *
 * This is the same path the editor uses for export ([com.potaty.export.ExportShapesHelper]); reusing it
 * is what makes generated diagrams look hand-crafted rather than templated.
 */
class AsciiRenderer(
    private val metrics: LayoutMetrics = LayoutMetrics.DEFAULT,
    private val bitmapManager: PotatyBitmapManager = PotatyBitmapManager()
) {
    data class Output(
        val text: String,
        val quality: LayoutQualityScore,
        val layoutEngineVersion: String
    )

    fun renderText(ir: DiagramIR): String = render(ir).text

    fun render(ir: DiagramIR): Output {
        val profile = StyleProfile.byId(ir.styleHints.styleProfile)
        val engine = LayoutEngineFactory.forIr(ir, metrics)
        val layout = engine.layout(ir)
        val shapes = IrShapeMapper.toShapes(layout, profile)
        return Output(
            text = drawToText(shapes),
            quality = LayoutQualityScorer.score(layout),
            layoutEngineVersion = engine.version
        )
    }

    private fun drawToText(shapes: List<AbstractShape>): String {
        if (shapes.isEmpty()) return ""
        val window = Rect.byLTRB(
            shapes.minOf { it.bound.left },
            shapes.minOf { it.bound.top },
            shapes.maxOf { it.bound.right },
            shapes.maxOf { it.bound.bottom }
        )
        val board = PotatyBoard().apply { clearAndSetWindow(window) }
        for (shape in shapes) {
            val bitmap = bitmapManager.getBitmap(shape) ?: continue
            board.fill(shape.bound.position, bitmap, Highlight.NO)
        }
        return board.toStringInBound(window)
            .lines()
            .joinToString("\n") { it.trimEnd() }
            .trim('\n')
    }
}
