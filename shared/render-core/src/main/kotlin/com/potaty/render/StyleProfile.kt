/*
 * Copyright (c) 2026, Potaty
 */

package com.potaty.render

/**
 * A curated visual style for a rendered diagram. Style profiles are how Potaty guarantees the
 * `output_sample/` aesthetic: deterministic, tasteful defaults (border style, dashed group
 * containers, arrow heads, rounded elbows) rather than per-diagram guesswork.
 *
 * Style ids map to the engine's predefined styles:
 *  - border/stroke: "S1" (light ─│┌┐), "S2" (bold ━┃┏┓), "S3" (double ═║╔╗)
 *  - anchor (arrow head): "A1" (▶◀▲▼), "A2" (■), "A21" (□), "A5" (●)
 *
 * This is a monochrome (ASCII / box-drawing) engine, so a profile's "palette" is expressed through
 * border weight, arrow-head glyph, corner rounding and dash rhythm rather than RGB colour. The
 * profiles below name themselves after the colour mood they evoke in a colourised export while
 * staying fully deterministic in the text renderer.
 */
data class StyleProfile(
    val id: String,
    val nodeBorderStyleId: String = "S1",
    val groupBorderStyleId: String = "S1",
    val lineStrokeStyleId: String = "S1",
    val endAnchorId: String = "A1",
    val roundedNodeCorners: Boolean = false,
    val roundedElbows: Boolean = true,
    val dashedGroups: Boolean = true,
    val groupDashOn: Int = 1,
    val groupDashOff: Int = 1,
    val inferredDashOn: Int = 1,
    val inferredDashOff: Int = 1
) {
    companion object {
        val CLEAN = StyleProfile(id = "potaty-clean")

        val ROUNDED = StyleProfile(
            id = "potaty-rounded",
            roundedNodeCorners = true,
            endAnchorId = "A1"
        )

        val BLUEPRINT = StyleProfile(
            id = "blueprint",
            nodeBorderStyleId = "S1",
            groupBorderStyleId = "S3",
            endAnchorId = "A21"
        )

        val BOLD = StyleProfile(
            id = "potaty-bold",
            nodeBorderStyleId = "S2",
            lineStrokeStyleId = "S1",
            endAnchorId = "A1"
        )

        /**
         * Cool, structured look: bold node boxes with a tighter group dash rhythm — reads as a
         * "darker" / higher-contrast variant when colourised.
         */
        val SLATE = StyleProfile(
            id = "potaty-slate",
            nodeBorderStyleId = "S2",
            groupBorderStyleId = "S1",
            lineStrokeStyleId = "S1",
            endAnchorId = "A1",
            roundedNodeCorners = false,
            roundedElbows = true,
            dashedGroups = true,
            groupDashOn = 2,
            groupDashOff = 1
        )

        /**
         * Soft, organic look: rounded node corners and a gentle filled-square arrow head — the
         * muted-green mood. Group containers use a longer, calmer dash.
         */
        val SAGE = StyleProfile(
            id = "potaty-sage",
            nodeBorderStyleId = "S1",
            groupBorderStyleId = "S1",
            lineStrokeStyleId = "S1",
            endAnchorId = "A2",
            roundedNodeCorners = true,
            roundedElbows = true,
            dashedGroups = true,
            groupDashOn = 3,
            groupDashOff = 2
        )

        /**
         * Warm, earthy look: rounded boxes with a solid-dot arrow head and solid group borders —
         * the terracotta mood, slightly heavier and rounder than [SAGE].
         */
        val TERRACOTTA = StyleProfile(
            id = "potaty-terracotta",
            nodeBorderStyleId = "S1",
            groupBorderStyleId = "S2",
            lineStrokeStyleId = "S1",
            endAnchorId = "A5",
            roundedNodeCorners = true,
            roundedElbows = true,
            dashedGroups = false
        )

        /**
         * Minimal documentation look: light borders, no rounding, no dashing and a hollow-square
         * arrow head — maximally legible for embedding in docs / READMEs.
         */
        val MONOCHROME_DOCS = StyleProfile(
            id = "potaty-monochrome",
            nodeBorderStyleId = "S1",
            groupBorderStyleId = "S1",
            lineStrokeStyleId = "S1",
            endAnchorId = "A21",
            roundedNodeCorners = false,
            roundedElbows = false,
            dashedGroups = false
        )

        private val ALL =
            listOf(CLEAN, ROUNDED, BLUEPRINT, BOLD, SLATE, SAGE, TERRACOTTA, MONOCHROME_DOCS)
                .associateBy { it.id }

        /** All built-in profiles, keyed by their [StyleProfile.id]. Useful for validation/UX lists. */
        fun all(): Map<String, StyleProfile> = ALL

        fun byId(id: String?): StyleProfile = ALL[id] ?: CLEAN
    }
}
