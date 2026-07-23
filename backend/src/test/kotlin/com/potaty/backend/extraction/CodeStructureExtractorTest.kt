/*
 * Copyright (c) 2026, Potaty
 *
 * Unit tests for CodeStructureExtractor (plan section 2.3, static code extraction). Pure in-memory
 * tests: hand-built StoredChunk values stand in for an indexed repository, with no database. They
 * assert that import statements collapse to MODULE-level SERVICE nodes and DEPENDS_ON edges, that
 * relative TS specs resolve across module directories, that Kotlin package imports map to their last
 * meaningful segment, that third-party specs are ignored, and that evidence cites the exact line.
 */

package com.potaty.backend.extraction

import com.potaty.backend.persistence.repositories.StoredChunk
import com.potaty.ir.EdgeType
import com.potaty.ir.NodeType
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CodeStructureExtractorTest {

    /** Build a StoredChunk for a whole file starting at line 1 (matches the indexer's common case). */
    private fun fileChunk(path: String, text: String, startLine: Int = 1): StoredChunk =
        StoredChunk(
            id = UUID.randomUUID(),
            sourceVersionId = UUID.randomUUID(),
            chunkIndex = 0,
            path = path,
            startLine = startLine,
            endLine = startLine + text.split("\n").size - 1,
            text = text
        )

    private fun ExtractionResult.hasEdge(from: String, to: String): Boolean =
        relations.any { it.fromKey == from && it.toKey == to && it.type == EdgeType.DEPENDS_ON }

    @Test
    fun tsRelativeImportsResolveAcrossModuleDirectories() {
        // src/ is a generic wrapper, so module() is the NEXT segment: components / services / hooks.
        val button = fileChunk(
            "src/components/Button.tsx",
            """
            import React from 'react';
            import { fetchUser } from '../services/api';
            import { useAuth } from '../hooks/useAuth';
            import { Icon } from './Icon';
            export function Button() { return null; }
            """.trimIndent()
        )
        val api = fileChunk(
            "src/services/api.ts",
            """
            const http = require('../hooks/client');
            export { config } from '../config/env';
            """.trimIndent()
        )

        val result = CodeStructureExtractor.extract(listOf(button, api))

        val keys = result.entities.map { it.key }.toSet()
        // 'react' is a bare specifier (third-party) and must NOT become a module.
        assertTrue("react" !in keys, "third-party import must be skipped; got $keys")
        assertTrue(
            keys.containsAll(listOf("components", "services", "hooks")),
            "expected module nodes; got $keys"
        )

        // Relative specs resolve to sibling modules under src/.
        assertTrue(result.hasEdge("components", "services"), "Button -> api: $result")
        assertTrue(result.hasEdge("components", "hooks"), "Button -> useAuth: $result")
        // './Icon' resolves to src/components/Icon = same module => self-edge, dropped.
        assertTrue(!result.hasEdge("components", "components"), "self-edge must be dropped")
        // require() and `export ... from` are parsed too.
        assertTrue(result.hasEdge("services", "hooks"), "api require('../hooks/client'): $result")
        assertTrue(result.hasEdge("services", "config"), "api export from '../config/env': $result")

        // Every emitted node is a SERVICE module node (readable codebase flow).
        assertTrue(result.entities.all { it.type == NodeType.SERVICE }, "module nodes are SERVICE")
        // No orphan nodes: every node participates in an edge.
        val referenced = result.relations.flatMap { listOf(it.fromKey, it.toKey) }.toSet()
        assertEquals(referenced, keys, "no orphan module nodes")
    }

    @Test
    fun kotlinPackageImportsMapToLastMeaningfulSegment() {
        val rectangle = fileChunk(
            "shape/Rectangle.kt",
            """
            package com.potaty.shape

            import com.potaty.render.Renderer
            import com.potaty.geometry.Point
            import kotlin.math.max

            class Rectangle
            """.trimIndent()
        )
        val renderer = fileChunk(
            "render/Renderer.kt",
            """
            package com.potaty.render

            import com.potaty.shape.Shape

            class Renderer
            """.trimIndent()
        )

        val result = CodeStructureExtractor.extract(listOf(rectangle, renderer))

        val keys = result.entities.map { it.key }.toSet()
        assertTrue(
            keys.containsAll(listOf("shape", "render", "geometry")),
            "expected package modules; got $keys"
        )
        // import target module is the LAST meaningful package segment.
        assertTrue(result.hasEdge("shape", "render"), "Rectangle imports render.Renderer: $result")
        assertTrue(result.hasEdge("shape", "geometry"), "Rectangle imports geometry.Point: $result")
        assertTrue(result.hasEdge("render", "shape"), "Renderer imports shape.Shape: $result")
    }

    @Test
    fun evidenceCitesExactImportLine() {
        val chunk = fileChunk(
            "shape/Rectangle.kt",
            """
            package com.potaty.shape

            import com.potaty.render.Renderer
            """.trimIndent()
        )

        val result = CodeStructureExtractor.extract(listOf(chunk))
        val edge = result.relations.single { it.fromKey == "shape" && it.toKey == "render" }
        val ev = edge.evidence.single()

        // 'import com.potaty.render.Renderer' is the 3rd line of the chunk (startLine 1 + offset 2).
        assertEquals(3, ev.startLine)
        assertEquals(3, ev.endLine)
        assertEquals("shape/Rectangle.kt", ev.path)
        assertEquals(chunk.id.toString(), ev.sourceChunkId)
        assertEquals("import com.potaty.render.Renderer", ev.quote)
        // quoteHash is the sha256 of the trimmed line (64 lowercase hex chars).
        assertEquals(64, ev.quoteHash?.length)
        assertTrue(ev.quoteHash!!.all { it.isDigit() || it in 'a'..'f' }, "hash is lowercase hex")
    }

    @Test
    fun emptyWhenNothingResolves() {
        // A prose chunk with no path, and a code file whose only import is third-party.
        val prose = StoredChunk(UUID.randomUUID(), UUID.randomUUID(), 0, null, 1, 1, "User -> API")
        val onlyExternal = fileChunk("src/app/main.ts", "import express from 'express';")

        assertEquals(
            ExtractionResult(emptyList(), emptyList()),
            CodeStructureExtractor.extract(listOf(prose, onlyExternal))
        )
    }

    @Test
    fun goImportsSingleAndGroupedResolve() {
        // Repo root is example.com/app per Go convention; internal packages resolve to their leaf.
        val server = fileChunk(
            "server/main.go",
            """
            package main

            import "example.com/app/store"

            import (
                "fmt"
                "example.com/app/handler"
                alias "example.com/app/auth/session"
            )
            """.trimIndent()
        )

        val result = CodeStructureExtractor.extract(listOf(server))
        val keys = result.entities.map { it.key }.toSet()
        // 'fmt' is stdlib (single segment) => skipped.
        assertTrue("fmt" !in keys, "stdlib import skipped; got $keys")
        assertTrue(result.hasEdge("server", "store"), "single import: $result")
        assertTrue(result.hasEdge("server", "handler"), "grouped import: $result")
        assertTrue(
            result.hasEdge("server", "session"),
            "aliased grouped import (leaf segment): $result"
        )
    }
}
