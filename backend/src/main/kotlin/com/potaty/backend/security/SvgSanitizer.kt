/*
 * Copyright (c) 2026, Potaty
 *
 * SVG sanitizer (plan 2.6 / 5.2: SVG output is untrusted and must be sanitized before it is
 * served or exported). Strips script, event handlers, external references, and dangerous
 * URI schemes so rendered SVG cannot execute or phone home.
 *
 * Defense-in-depth strip of the active-content vectors in SVG: <script>/<style>/<foreignObject>
 * and the HTML-embedding elements (<iframe>/<embed>/<object>); on* event handlers; and href/
 * xlink:href values using javascript: or data: schemes that can carry markup/script
 * (text/html, image/svg+xml). A full element/attribute allowlist via an XML parser is the
 * stronger end state (tracked in the security backlog); this removes the exploitable surface and
 * is what the export / preview paths call before any SVG is served.
 */

package com.potaty.backend.security

object SvgSanitizer {

    private val activeBlocks =
        listOf(
            Regex("""<script\b[^>]*>[\s\S]*?</script>""", RegexOption.IGNORE_CASE),
            Regex("""<style\b[^>]*>[\s\S]*?</style>""", RegexOption.IGNORE_CASE),
            Regex("""<foreignObject\b[^>]*>[\s\S]*?</foreignObject>""", RegexOption.IGNORE_CASE),
            Regex("""<iframe\b[^>]*>[\s\S]*?</iframe>""", RegexOption.IGNORE_CASE),
            Regex(
                """<(?:embed|object)\b[^>]*>(?:[\s\S]*?</(?:embed|object)>)?""",
                RegexOption.IGNORE_CASE
            ),
            // Self-closing / unterminated <script .../> and <style .../> safety net.
            Regex("""<(?:script|style)\b[^>]*/>""", RegexOption.IGNORE_CASE)
        )
    private val onEventAttr =
        Regex("""\son\w+\s*=\s*("[^"]*"|'[^']*'|[^\s>]+)""", RegexOption.IGNORE_CASE)

    // href / xlink:href with an active scheme (javascript:, data:text/html, data:image/svg+xml).
    private val dangerousHref =
        Regex(
            """\s(?:xlink:)?href\s*=\s*("(?:\s*)""" +
                """(?:javascript:|data:text/html|data:image/svg)[^"]*"|""" +
                """'(?:\s*)(?:javascript:|data:text/html|data:image/svg)[^']*')""",
            RegexOption.IGNORE_CASE
        )
    private val styleImport = Regex("""@import\b[^;]*;""", RegexOption.IGNORE_CASE)

    data class SanitizeResult(val svg: String, val removedCount: Int)

    fun sanitize(svg: String): SanitizeResult {
        var removed = 0
        fun apply(input: String, re: Regex): String =
            re.replace(input) {
                removed++
                ""
            }

        var out = svg
        activeBlocks.forEach { out = apply(out, it) }
        out = apply(out, onEventAttr)
        out = apply(out, dangerousHref)
        out = apply(out, styleImport)
        return SanitizeResult(out, removed)
    }
}
