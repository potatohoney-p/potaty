/*
 * Copyright (c) 2026, Potaty
 */

package com.potaty.codegen

/**
 * Per-format label escaping.
 *
 * Every external diagram language has its own quoting / escaping rules and its own set of
 * characters that break the parser or (worse) enable injection. The compilers MUST route every
 * piece of user/LLM-derived text through one of these helpers. The guiding principle is
 * **security-first**: we never emit raw HTML, never emit `click`/`href` directives, and we
 * neutralise newlines and language control characters so a malicious label cannot escape its string
 * context and inject new diagram statements.
 *
 * JS-compatibility note: only plain [String] operations and [Regex] (with [RegexOption]) are used —
 * no JVM-only stdlib. No inline `(?i)` flags (unsupported by JS RegExp).
 */
object LabelEscaper {

    /** Collapse CR/LF/tab runs into a single space so a label stays a single logical line. */
    private fun flatten(text: String): String = text.replace(Regex("[\\r\\n\\t]+"), " ").trim()

    /**
     * Mermaid label text. Mermaid is HTML-rendered by default, so we both strip angle brackets
     * (defence in depth against HTML/script injection even though we also set securityLevel-safe
     * output) and escape the double-quotes used to wrap node/edge text. We additionally encode a
     * handful of structural characters via Mermaid's `#NNN;` entity syntax so brackets/braces
     * inside a label can never be read as shape syntax.
     */
    fun mermaid(text: String): String {
        val flat = flatten(text)
        val sb = StringBuilder(flat.length + 8)
        for (ch in flat) {
            when (ch) {
                // Use Mermaid NUMERIC entity codes (#NNN;) which are universally supported, rather
                // than named codes like #quot; which render literally in some Mermaid versions.
                '"' -> sb.append("#34;")
                '<' -> sb.append("#60;")
                '>' -> sb.append("#62;")
                '#' -> sb.append("#35;")
                '[' -> sb.append("#91;")
                ']' -> sb.append("#93;")
                '{' -> sb.append("#123;")
                '}' -> sb.append("#125;")
                '(' -> sb.append("#40;")
                ')' -> sb.append("#41;")
                '|' -> sb.append("#124;")
                ';' -> sb.append("#59;")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /**
     * D2 label text. D2 strings can be wrapped in double quotes; inside a quoted string a literal
     * double quote is escaped with a backslash and backslashes themselves are doubled.
     */
    fun d2(text: String): String = flatten(text).replace("\\", "\\\\").replace("\"", "\\\"")

    /**
     * PlantUML label text. PlantUML quoted identifiers/labels use double quotes; angle brackets are
     * stripped (PlantUML supports embedded "creole"/HTML-ish markup which we do not want from
     * untrusted text) and double quotes are escaped.
     *
     * Lossy-transform note (security tradeoff): `<` and `>` are rewritten to `(` and `)` rather
     * than escaped. PlantUML's creole/HTML-ish markup (`<b>`, `<color:...>`, `<&icon>`, etc.) is
     * activated by raw angle brackets, and PlantUML has no portable in-string escape for them, so
     * neutralising them by substitution is the only injection-safe option that works across
     * PlantUML versions. This loses the literal `<`/`>` characters from the displayed label.
     * Callers that need the exact original text (e.g. for tooltips or a "view source" affordance)
     * should read it from the IR node label / edge label rather than reverse-engineering it from
     * the compiled PlantUML.
     */
    fun plantUml(text: String): String =
        flatten(text).replace("\"", "\\\"").replace("<", "(").replace(">", ")")

    /**
     * Graphviz DOT label text. DOT double-quoted strings escape `"` and `\`. We flatten newlines
     * rather than emitting the `\n` line-break escape so a label cannot smuggle in extra lines.
     */
    fun dot(text: String): String = flatten(text).replace("\\", "\\\\").replace("\"", "\\\"")

    /**
     * Markdown text (used for the human-readable summary / disclosure, NOT for code-fence bodies).
     * Escapes the characters that would otherwise be interpreted as Markdown structure.
     *
     * Pre-existing-escape caveat: input is assumed to be RAW text without pre-existing backslash
     * escapes. This function escapes every structural character unconditionally (no lookahead), so
     * a caller that passes already-escaped Markdown (e.g. `\*`) will get it double-escaped
     * (`\\\*`). IR labels are plain text, so this is the correct, safe-by-default behaviour for
     * untrusted input; if a caller ever holds Markdown-escaped text it must normalise (unescape) it
     * first.
     */
    fun markdown(text: String): String {
        val flat = flatten(text)
        val sb = StringBuilder(flat.length + 8)
        for (ch in flat) {
            when (ch) {
                '\\',
                '`',
                '*',
                '_',
                '{',
                '}',
                '[',
                ']',
                '(',
                ')',
                '#',
                '+',
                '-',
                '.',
                '!',
                '|',
                '<',
                '>' -> sb.append('\\').append(ch)
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /**
     * Sanitise an arbitrary IR id into the BASE form of a safe identifier matching
     * `[A-Za-z_][A-Za-z0-9_]*`: every non-`[A-Za-z0-9_]` char becomes `_`, a leading non-letter is
     * prefixed with `n_`, and empty input yields `n_`.
     *
     * WARNING — this mapping is intentionally many-to-one ("a.b-c" and "a_b_c" both yield "a_b_c").
     * It is the BASE only. NEVER use it directly to key nodes/edges in a diagram, or distinct ids
     * will silently merge (a correctness AND security problem — a crafted id could collide with
     * another node's id). Use [IdentAllocator] (one instance per compile) to obtain unique,
     * collision-disambiguated identifiers that stay consistent between node declarations and edge
     * endpoint references.
     */
    fun sanitizeIdent(id: String): String {
        if (id.isEmpty()) return "n_"
        val sb = StringBuilder(id.length + 2)
        val first = id[0]
        if (!(first.isLetter() || first == '_')) {
            sb.append('n').append('_')
        }
        for (ch in id) {
            sb.append(if (ch.isLetterOrDigit() || ch == '_') ch else '_')
        }
        return sb.toString()
    }
}
