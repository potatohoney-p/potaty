/*
 * Copyright (c) 2026, Potaty
 *
 * Covers the additive StyleProfile registry: the four built-in profiles are unchanged, the new
 * slate/sage/terracotta/monochrome variants resolve by id, and unknown/null ids still fall back to
 * the default CLEAN profile (so existing rendering output is preserved).
 */

package com.potaty.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StyleProfileTest {

    @Test
    fun unknownAndNullIdsFallBackToClean() {
        assertSame(StyleProfile.CLEAN, StyleProfile.byId(null))
        assertSame(StyleProfile.CLEAN, StyleProfile.byId("does-not-exist"))
        assertSame(StyleProfile.CLEAN, StyleProfile.byId(""))
    }

    @Test
    fun existingProfilesStillResolveUnchanged() {
        // These ids are wire values used by existing IR / golden fixtures and must not change.
        assertSame(StyleProfile.CLEAN, StyleProfile.byId("potaty-clean"))
        assertSame(StyleProfile.ROUNDED, StyleProfile.byId("potaty-rounded"))
        assertSame(StyleProfile.BLUEPRINT, StyleProfile.byId("blueprint"))
        assertSame(StyleProfile.BOLD, StyleProfile.byId("potaty-bold"))
    }

    @Test
    fun defaultCleanProfileDefaultsAreStable() {
        // Pins the defaults that produce the locked golden output.
        val clean = StyleProfile.CLEAN
        assertEquals("potaty-clean", clean.id)
        assertEquals("S1", clean.nodeBorderStyleId)
        assertEquals("S1", clean.groupBorderStyleId)
        assertEquals("S1", clean.lineStrokeStyleId)
        assertEquals("A1", clean.endAnchorId)
        assertEquals(false, clean.roundedNodeCorners)
        assertEquals(true, clean.roundedElbows)
        assertEquals(true, clean.dashedGroups)
    }

    @Test
    fun newProfilesResolveById() {
        assertSame(StyleProfile.SLATE, StyleProfile.byId("potaty-slate"))
        assertSame(StyleProfile.SAGE, StyleProfile.byId("potaty-sage"))
        assertSame(StyleProfile.TERRACOTTA, StyleProfile.byId("potaty-terracotta"))
        assertSame(StyleProfile.MONOCHROME_DOCS, StyleProfile.byId("potaty-monochrome"))
    }

    @Test
    fun newProfilesUseValidEngineStyleIds() {
        val validStroke = setOf("S1", "S2", "S3")
        val validAnchor = setOf("A1", "A2", "A21", "A5")
        for (p in listOf(
            StyleProfile.SLATE,
            StyleProfile.SAGE,
            StyleProfile.TERRACOTTA,
            StyleProfile.MONOCHROME_DOCS
        )) {
            assertTrue(p.nodeBorderStyleId in validStroke, "${p.id} node border id")
            assertTrue(p.groupBorderStyleId in validStroke, "${p.id} group border id")
            assertTrue(p.lineStrokeStyleId in validStroke, "${p.id} line stroke id")
            assertTrue(p.endAnchorId in validAnchor, "${p.id} anchor id")
        }
    }

    @Test
    fun allRegistryIsKeyedByIdAndContainsEveryProfile() {
        val all = StyleProfile.all()
        for (p in listOf(
            StyleProfile.CLEAN,
            StyleProfile.ROUNDED,
            StyleProfile.BLUEPRINT,
            StyleProfile.BOLD,
            StyleProfile.SLATE,
            StyleProfile.SAGE,
            StyleProfile.TERRACOTTA,
            StyleProfile.MONOCHROME_DOCS
        )) {
            assertSame(p, all[p.id], "registry maps ${p.id} to itself")
        }
        assertEquals(8, all.size, "all built-in profiles are registered, no id collisions")
        val byId = assertNotNull(all["potaty-slate"])
        assertEquals("potaty-slate", byId.id)
    }
}
