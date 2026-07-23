/*
 * Copyright (c) 2026, Potaty
 *
 * Path filtering for repository indexing (plan section 18 unit test "path filter"). Indexing a
 * whole repo is wasteful and noisy: vendored deps, build outputs, lockfiles, minified bundles and
 * binaries carry little architectural signal and would drown the high-signal source. IgnoreRules
 * encodes a sensible default ignore list plus support for a repo-local `.potatyignore` (one glob
 * per line, `#` comments, `!` negations) layered ON TOP of the defaults.
 *
 * Glob semantics are intentionally small (gitignore-like, not full PCRE):
 *   - a trailing "/" matches a directory prefix (e.g. "build/" ignores build/ and everything under)
 *   - "*" matches any run of non-"/" characters
 *   - "**" matches across "/" (any depth)
 *   - a leading "/" anchors to the repo root; otherwise the pattern matches at any depth
 *   - "!" negates (re-includes) a previously-ignored path
 */

package com.potaty.backend.github

class IgnoreRules private constructor(
    private val patterns: List<CompiledPattern>
) {
    private data class CompiledPattern(val regex: Regex, val negated: Boolean)

    /**
     * True if [path] (a forward-slash repo-relative path, no leading slash) should be EXCLUDED from
     * indexing. Later patterns win, so a negation (`!keep/this.js`) can re-include a path the
     * defaults excluded.
     */
    fun isIgnored(path: String): Boolean {
        val normalized = path.trimStart('/')
        // Credential-bearing paths are a hard safety boundary, not a relevance preference.
        // Repository-controlled negations must never re-include them.
        if (isHardDenied(normalized)) return true
        var ignored = false
        for (p in patterns) {
            if (p.regex.matches(normalized)) {
                ignored = !p.negated
            }
        }
        return ignored
    }

    fun keep(path: String): Boolean = !isIgnored(path)

    companion object {
        private val SAFE_ENV_TEMPLATES =
            setOf(".env.example", ".env.sample", ".env.template")
        private val HARD_DENY_REGEXES: List<Regex> =
            listOf(
                ".env",
                ".env.*",
                "*.pem",
                "*.key",
                "id_rsa",
                "id_ed25519",
                ".npmrc",
                ".pypirc",
                ".aws/credentials",
                "secrets/"
            ).map { compile(it).regex }

        private fun isHardDenied(path: String): Boolean {
            val lower = path.lowercase()
            val fileName = lower.substringAfterLast('/')
            if (fileName in SAFE_ENV_TEMPLATES) return false
            return HARD_DENY_REGEXES.any { it.matches(lower) }
        }

        /** Built-in ignores applied before any `.potatyignore`. */
        val DEFAULT_PATTERNS: List<String> = listOf(
            // dependency / vendor directories
            "node_modules/",
            "vendor/",
            "bower_components/",
            "**/node_modules/",
            "**/vendor/",
            // build / output directories
            "build/",
            "dist/",
            "out/",
            "target/",
            "bin/",
            "obj/",
            ".gradle/",
            ".next/",
            ".nuxt/",
            ".svelte-kit/",
            "coverage/",
            "__pycache__/",
            ".venv/",
            "venv/",
            "**/build/",
            "**/dist/",
            "**/__pycache__/",
            // VCS / tooling metadata
            ".git/",
            ".idea/",
            ".vscode/",
            // minified / map / lock artifacts (low architectural signal)
            "*.min.js",
            "*.min.css",
            "*.map",
            "package-lock.json",
            "yarn.lock",
            "pnpm-lock.yaml",
            "poetry.lock",
            "Cargo.lock",
            "composer.lock",
            // binaries / media / archives
            "*.png", "*.jpg", "*.jpeg", "*.gif", "*.webp", "*.ico", "*.svg",
            "*.pdf", "*.zip", "*.gz", "*.tar", "*.jar", "*.war", "*.class",
            "*.exe", "*.dll", "*.so", "*.dylib", "*.bin", "*.wasm",
            "*.woff", "*.woff2", "*.ttf", "*.eot",
            "*.mp4", "*.mov", "*.mp3", "*.wav"
        )

        /** Default rules with no repo-local overrides. */
        fun default(): IgnoreRules = of(null)

        /**
         * Builds rules from the [DEFAULT_PATTERNS] plus the optional [potatyignore] file contents
         * (may be null/blank). Lines are trimmed; blank lines and `#` comments are skipped.
         */
        fun of(potatyignore: String?): IgnoreRules {
            val lines = DEFAULT_PATTERNS.toMutableList()
            potatyignore?.let { lines.addAll(parse(it)) }
            return IgnoreRules(lines.map { compile(it) })
        }

        /** Parses a `.potatyignore` string into its significant (non-comment, non-blank) lines. */
        fun parse(content: String): List<String> =
            content.split("\n")
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }

        private fun compile(rawPattern: String): CompiledPattern {
            var pattern = rawPattern
            val negated = pattern.startsWith("!")
            if (negated) pattern = pattern.substring(1)

            val anchored = pattern.startsWith("/")
            if (anchored) pattern = pattern.substring(1)

            val dirOnly = pattern.endsWith("/")
            if (dirOnly) pattern = pattern.dropLast(1)

            val regexBody = globToRegex(pattern)
            // Anchored patterns match from root; unanchored ones may match at any depth (prefixed
            // with an optional "<any dirs>/" segment).
            val prefix = if (anchored) "" else "(?:.*/)?"
            // Directory patterns also match anything nested beneath the directory.
            val suffix = if (dirOnly) "(?:/.*)?" else ""
            val full = "^$prefix$regexBody$suffix$"
            return CompiledPattern(Regex(full), negated)
        }

        /** Translates a gitignore-style glob into a regex fragment (no anchors). */
        private fun globToRegex(glob: String): String {
            val sb = StringBuilder()
            var i = 0
            while (i < glob.length) {
                val c = glob[i]
                when (c) {
                    '*' -> {
                        if (i + 1 < glob.length && glob[i + 1] == '*') {
                            // "**" -> match across path separators (any depth)
                            sb.append(".*")
                            i++
                            // swallow a following "/" so "**/" doesn't force a separator
                            if (i + 1 < glob.length && glob[i + 1] == '/') i++
                        } else {
                            // "*" -> match within a single path segment
                            sb.append("[^/]*")
                        }
                    }
                    '?' -> sb.append("[^/]")
                    '.', '(', ')', '+', '|', '^', '$', '{', '}', '[', ']', '\\' ->
                        sb.append('\\').append(c)
                    else -> sb.append(c)
                }
                i++
            }
            return sb.toString()
        }
    }
}
