/*
 * Copyright (c) 2026, Potaty
 *
 * Deterministic MODULE-LEVEL import/dependency extraction for indexed source code (plan section 2.3:
 * static extraction before LLM interpretation). Where [EntityRelationExtractor] grounds a diagram in
 * the explicit structure of *prose*, this extractor grounds a "codebase flow" diagram in the explicit
 * structure of *code*: it parses import statements per language and collapses both the importing file
 * and its import targets onto MODULE nodes, so the resulting graph stays small and readable rather
 * than exploding into one node per file.
 *
 * Every edge cites the exact import line as evidence (path + line number + verbatim quote + hash), so
 * the graph is renderable and publishable from this deterministic pass alone — no live model needed —
 * which is what makes the pipeline testable. The LLM layer may later enrich (group, summarise, infer),
 * but the grounded skeleton produced here is the source of truth for STATIC_IMPORT edges.
 */

package com.potaty.backend.extraction

import com.potaty.backend.persistence.repositories.StoredChunk
import com.potaty.ir.EdgeType
import com.potaty.ir.EvidenceRef
import com.potaty.ir.NodeType

object CodeStructureExtractor {

    /**
     * Bound the graph so it stays a readable "flow" rather than a hairball (plan: small & legible).
     */
    private const val MAX_NODES = 40
    private const val MAX_EDGES = 80

    /** A leading directory segment that is a generic wrapper, not a meaningful module name. */
    private val GENERIC_WRAPPERS =
        setOf("src", "lib", "app", "pkg", "internal", "main", "kotlin", "java")

    /** Source-code file extensions we know how to read, grouped by language family. */
    private val JS_EXTS = setOf("js", "jsx", "ts", "tsx", "mjs", "cjs")
    private val PY_EXTS = setOf("py")
    private val JVM_EXTS = setOf("kt", "java")
    private val GO_EXTS = setOf("go")

    // --- import parsers (deterministic) -------------------------------------------------------

    // import x from 'spec';  import x from "spec";  export {x} from 'spec'
    private val JS_IMPORT_FROM = Regex("""\bimport\b[^'\"]*?\bfrom\s*['\"]([^'\"]+)['\"]""")
    private val JS_EXPORT_FROM = Regex("""\bexport\b[^'\"]*?\bfrom\s*['\"]([^'\"]+)['\"]""")

    // const x = require('spec')
    private val JS_REQUIRE = Regex("""\brequire\s*\(\s*['\"]([^'\"]+)['\"]\s*\)""")

    // side-effect import:  import 'spec';
    private val JS_IMPORT_BARE = Regex("""\bimport\s*['\"]([^'\"]+)['\"]""")

    // import a.b.c   /   import a.b.c as x   /   from a.b.c import x
    private val PY_IMPORT = Regex("""^\s*import\s+([\w.]+)""")
    private val PY_FROM = Regex("""^\s*from\s+([\w.]+)\s+import\b""")

    // import a.b.C   /   import a.b.C as Y   /   import static a.b.C.m  (Java)
    private val JVM_IMPORT = Regex("""^\s*import\s+(?:static\s+)?([\w.]+)""")

    // import "spec"   /   import alias "spec"  (Go, single line)
    private val GO_IMPORT_SINGLE = Regex("""^\s*import\s+(?:[\w.]+\s+)?[\"`]([^\"`]+)[\"`]""")

    // start of a grouped block:  import (
    private val GO_IMPORT_BLOCK_OPEN = Regex("""^\s*import\s*\(\s*$""")

    // a spec line inside a grouped import ( ... ) block:   alias "spec"   or   "spec"
    private val GO_GROUPED_SPEC = Regex("""^\s*(?:[\w.]+\s+)?[\"`]([^\"`]+)[\"`]""")

    private enum class Lang {
        JS,
        PY,
        JVM,
        GO,
        OTHER
    }

    private data class RawEdge(val from: String, val to: String, val evidence: EvidenceRef)

    /**
     * Mutable accumulator threaded through the walk. All collections are insertion-ordered so the
     * extractor is fully deterministic for a given chunk list.
     */
    private class Graph {
        val nodeEvidence =
            LinkedHashMap<String, MutableList<EvidenceRef>>() // canonicalKey -> evidence
        val nodeLabel = LinkedHashMap<String, String>() // canonicalKey -> display label
        val degree = LinkedHashMap<String, Int>() // canonicalKey -> incident edges
        val edgeSeen = LinkedHashSet<String>() // "from->to" dedupe
        val edges = mutableListOf<RawEdge>()

        fun touchNode(key: String, label: String, ev: EvidenceRef) {
            nodeLabel.getOrPut(key) { label }
            val list = nodeEvidence.getOrPut(key) { mutableListOf() }
            if (
                list.none { it.sourceChunkId == ev.sourceChunkId && it.startLine == ev.startLine }
            ) {
                list.add(ev)
            }
        }
    }

    /**
     * @param chunks indexed source chunks; each carries a [StoredChunk.path] and an optional
     *   [StoredChunk.startLine] so evidence line numbers can be reconstructed. Chunks without a
     *   path are ignored (we cannot place a path-less chunk on a module node).
     * @return a module dependency graph (SERVICE nodes, DEPENDS_ON edges) or an empty result if no
     *   import resolved to a repo-internal module.
     */
    fun extract(chunks: List<StoredChunk>): ExtractionResult {
        // 1. Group chunks by file path (insertion-ordered for determinism). A single file may be
        //    split across multiple chunks; we keep them in chunk order so line numbers stay sane.
        val byPath = LinkedHashMap<String, MutableList<StoredChunk>>()
        for (chunk in chunks) {
            val path = chunk.path?.trim()
            if (path.isNullOrEmpty()) continue
            byPath.getOrPut(normalizePath(path)) { mutableListOf() }.add(chunk)
        }
        if (byPath.isEmpty()) return ExtractionResult(emptyList(), emptyList())

        val graph = Graph()

        // 2. Walk each file, detect language, parse imports, map to modules.
        for ((path, fileChunks) in byPath) {
            val lang = languageOf(path)
            if (lang == Lang.OTHER) continue
            val fromModule = moduleOfPath(path)
            if (fromModule.isEmpty()) continue
            walkFile(graph, lang, path, fromModule, fileChunks)
        }

        if (graph.edges.isEmpty()) return ExtractionResult(emptyList(), emptyList())

        // 3. Bound the graph: keep highest-degree modules first (most central to the flow). Ties
        //    break by first-seen insertion order so output stays deterministic.
        val keptModules = LinkedHashSet<String>()
        if (graph.nodeLabel.size <= MAX_NODES) {
            keptModules.addAll(graph.nodeLabel.keys)
        } else {
            val insertionIndex = graph.nodeLabel.keys.withIndex().associate { it.value to it.index }
            graph.nodeLabel.keys
                .sortedWith(
                    compareByDescending<String> {
                        graph.degree[it] ?: 0
                    }
                        .thenBy { insertionIndex[it] ?: 0 }
                )
                .take(MAX_NODES)
                .forEach { keptModules.add(it) }
        }

        // 4. Keep edges whose endpoints both survived, deduped, capped. Insertion order preserved.
        val keptEdgeKeys = LinkedHashSet<String>()
        val relations = mutableListOf<ExtractedRelation>()
        for (e in graph.edges) {
            if (relations.size >= MAX_EDGES) break
            if (e.from !in keptModules || e.to !in keptModules) continue
            if (!keptEdgeKeys.add("${e.from}->${e.to}")) continue
            relations.add(
                ExtractedRelation(
                    fromKey = e.from,
                    toKey = e.to,
                    type = EdgeType.DEPENDS_ON,
                    label = null,
                    evidence = listOf(e.evidence)
                )
            )
        }
        if (relations.isEmpty()) return ExtractionResult(emptyList(), emptyList())

        // 5. Emit only modules that actually participate in a kept edge (no orphan nodes).
        val referenced = LinkedHashSet<String>()
        for (r in relations) {
            referenced.add(r.fromKey)
            referenced.add(r.toKey)
        }
        val entities =
            graph.nodeLabel.keys
                .filter { it in referenced }
                .map { key ->
                    ExtractedEntity(
                        key = key,
                        label = graph.nodeLabel[key] ?: key,
                        type = NodeType.SERVICE,
                        evidence = graph.nodeEvidence[key]?.toList() ?: emptyList()
                    )
                }

        return ExtractionResult(entities, relations)
    }

    // --- per-file walk ------------------------------------------------------------------------

    private fun walkFile(
        graph: Graph,
        lang: Lang,
        path: String,
        fromModule: String,
        fileChunks: List<StoredChunk>
    ) {
        // Go's grouped `import ( ... )` block spans lines, so its state persists across the file.
        var inGoImportBlock = false
        for (chunk in fileChunks) {
            val baseLine = chunk.startLine ?: 1
            chunk.text.split("\n").forEachIndexed { offset, line ->
                val lineNo = baseLine + offset
                if (lang == Lang.GO && inGoImportBlock) {
                    if (line.trim().startsWith(")")) {
                        inGoImportBlock = false
                    } else {
                        GO_GROUPED_SPEC.find(line)?.groupValues?.get(1)?.let { spec ->
                            addModuleEdge(graph, fromModule, spec, lang, path, chunk, lineNo, line)
                        }
                    }
                    return@forEachIndexed
                }
                if (lang == Lang.GO && GO_IMPORT_BLOCK_OPEN.containsMatchIn(line)) {
                    inGoImportBlock = true
                    return@forEachIndexed
                }
                for (spec in parseImportSpecs(lang, line)) {
                    addModuleEdge(graph, fromModule, spec, lang, path, chunk, lineNo, line)
                }
            }
        }
    }

    /**
     * Resolves one import [spec] declared in [fromModule]'s file to a target module and, if it maps
     * to a repo-internal module distinct from [fromModule], records the node(s) + a deduped edge.
     */
    private fun addModuleEdge(
        graph: Graph,
        fromModule: String,
        spec: String,
        lang: Lang,
        path: String,
        chunk: StoredChunk,
        lineNo: Int,
        line: String
    ) {
        val toModule = resolveTargetModule(lang, path, spec) ?: return
        if (toModule.isEmpty() || toModule == fromModule) return

        val fromKey = canonical(fromModule)
        val toKey = canonical(toModule)
        if (fromKey.isEmpty() || toKey.isEmpty() || fromKey == toKey) return

        val ev = evidenceFor(chunk, lineNo, line.trim())
        graph.touchNode(fromKey, fromModule, ev)
        graph.touchNode(toKey, toModule, ev)

        if (graph.edgeSeen.add("$fromKey->$toKey")) {
            graph.edges.add(RawEdge(fromKey, toKey, ev))
            graph.degree[fromKey] = (graph.degree[fromKey] ?: 0) + 1
            graph.degree[toKey] = (graph.degree[toKey] ?: 0) + 1
        }
    }

    // --- import parsing -----------------------------------------------------------------------

    private fun parseImportSpecs(lang: Lang, line: String): List<String> =
        when (lang) {
            Lang.JS -> {
                val out = LinkedHashSet<String>()
                JS_IMPORT_FROM.findAll(line).forEach { out.add(it.groupValues[1]) }
                JS_EXPORT_FROM.findAll(line).forEach { out.add(it.groupValues[1]) }
                JS_REQUIRE.findAll(line).forEach { out.add(it.groupValues[1]) }
                // A bare `import 'x'` only when there was no `from` (side-effect import).
                if (out.isEmpty()) {
                    JS_IMPORT_BARE.findAll(line).forEach { out.add(it.groupValues[1]) }
                }
                out.toList()
            }
            Lang.PY -> {
                PY_FROM.find(line)?.groupValues?.get(1)?.let { listOf(it) }
                    ?: PY_IMPORT.find(line)?.groupValues?.get(1)?.let { listOf(it) }
                    ?: emptyList()
            }
            Lang.JVM ->
                JVM_IMPORT.find(line)?.groupValues?.get(1)?.let { listOf(it) } ?: emptyList()
            Lang.GO ->
                GO_IMPORT_SINGLE.find(line)?.groupValues?.get(1)?.let { listOf(it) } ?: emptyList()
            Lang.OTHER -> emptyList()
        }

    // --- module resolution --------------------------------------------------------------------

    /**
     * Maps an import [spec] to a repo-internal MODULE label, or null when the spec is third-party /
     * stdlib (i.e. does not resolve to a path inside the repo and looks external).
     */
    private fun resolveTargetModule(lang: Lang, fromPath: String, spec: String): String? {
        val s = spec.trim()
        if (s.isEmpty()) return null
        return when (lang) {
            Lang.JS -> {
                if (s.startsWith("./") || s.startsWith("../") || s == "." || s == "..") {
                    // Relative spec: resolve against the importing file's directory, then module().
                    moduleOfPath(resolveRelative(fromPath, s))
                } else {
                    // Bare specifier (react, lodash, @scope/pkg, node:fs) => external, skip.
                    null
                }
            }
            // Kotlin/Java: `import a.b.shape.Shape` always ends in the imported symbol, so we drop
            // the final segment and take the last meaningful *package* segment (a.b.shape.X ->
            // shape).
            Lang.JVM -> {
                val pkg = s.split('.').dropLast(1) // strip the trailing class/member name
                lastMeaningfulSegment(pkg)
            }
            // Python: `from a.b.services import x` captures the package `a.b.services` (no symbol),
            // and `import a.b.c` captures the module path `a.b.c`. In both cases the last segment
            // is
            // the module/package itself, so keep it. We cannot prove a package is repo-internal
            // without a full index; the GENERIC_WRAPPERS skip + de-dup keep the graph sane.
            Lang.PY -> lastMeaningfulSegment(s.split('.'))
            Lang.GO -> {
                // Go import paths are slash-separated. A single-segment spec ("fmt", "os", "io") is
                // stdlib; a dotted first segment ("github.com/...", "golang.org/...") is
                // third-party,
                // and its bare two-segment root (host + user) is not a repo module either.
                val segs = s.split('/').filter { it.isNotEmpty() }
                if (segs.size <= 1) return null // stdlib short name, skip
                val looksExternal = segs.first().contains('.')
                if (looksExternal && segs.size <= 2) return null // bare third-party root, skip
                lastMeaningfulSegment(segs)
            }
            Lang.OTHER -> null
        }
    }

    /**
     * The importing file's top *meaningful* directory segment. A leading generic wrapper
     * (src/lib/app/pkg/internal/...) is skipped in favour of the next segment. A file with no
     * directory falls back to its base name without extension.
     */
    private fun moduleOfPath(rawPath: String): String {
        val path = normalizePath(rawPath)
        if (path.isEmpty()) return ""
        val segs = path.split('/').filter { it.isNotEmpty() }
        if (segs.isEmpty()) return ""
        if (segs.size == 1) return baseNameWithoutExt(segs[0]) // no directory => file base name
        // Skip a chain of generic wrappers, then take the first meaningful directory segment.
        var i = 0
        while (i < segs.size - 1 && segs[i].lowercase() in GENERIC_WRAPPERS) i++
        // If everything before the file name was a wrapper, fall back to the file base name.
        return if (i >= segs.size - 1) baseNameWithoutExt(segs.last()) else segs[i]
    }

    /** Last segment of a package/path that is not a generic wrapper (a.b.services -> services). */
    private fun lastMeaningfulSegment(segments: List<String>): String {
        val segs = segments.filter { it.isNotEmpty() }
        if (segs.isEmpty()) return ""
        for (idx in segs.indices.reversed()) {
            if (segs[idx].lowercase() !in GENERIC_WRAPPERS) return segs[idx]
        }
        return segs.last()
    }

    /** Resolve a relative JS spec against the importing file's directory into a repo path. */
    private fun resolveRelative(fromPath: String, spec: String): String {
        val dir = normalizePath(fromPath).substringBeforeLast('/', "")
        val baseParts = if (dir.isEmpty()) mutableListOf() else dir.split('/').toMutableList()
        for (part in spec.split('/')) {
            when (part) {
                "",
                "." -> {}
                ".." -> if (baseParts.isNotEmpty()) baseParts.removeAt(baseParts.size - 1)
                else -> baseParts.add(part)
            }
        }
        return baseParts.joinToString("/")
    }

    // --- small helpers ------------------------------------------------------------------------

    private fun languageOf(path: String): Lang =
        when (extensionOf(path)) {
            in JS_EXTS -> Lang.JS
            in PY_EXTS -> Lang.PY
            in JVM_EXTS -> Lang.JVM
            in GO_EXTS -> Lang.GO
            else -> Lang.OTHER
        }

    private fun extensionOf(path: String): String {
        val base = path.substringAfterLast('/')
        val dot = base.lastIndexOf('.')
        return if (dot <= 0) "" else base.substring(dot + 1).lowercase()
    }

    private fun baseNameWithoutExt(segment: String): String {
        val dot = segment.lastIndexOf('.')
        return if (dot <= 0) segment else segment.substring(0, dot)
    }

    /** Normalise Windows separators and collapse a leading "./". */
    private fun normalizePath(path: String): String =
        path.replace('\\', '/').removePrefix("./").trim().trimEnd('/')

    private fun canonical(module: String): String = module.lowercase().trim()

    private fun evidenceFor(chunk: StoredChunk, lineNo: Int, line: String): EvidenceRef =
        EvidenceRef(
            sourceChunkId = chunk.id.toString(),
            path = chunk.path,
            startLine = lineNo,
            endLine = lineNo,
            quote = line.take(240),
            quoteHash = sha256(line)
        )

    private fun sha256(value: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(value.toByteArray(Charsets.UTF_8)).joinToString("") {
            "%02x".format(it)
        }
    }
}
