/*
 * Copyright (c) 2026, Potaty
 *
 * IgnoreRules tests (plan section 18 unit test "path filter"). Verify the default ignores for
 * dependency/build/vendor/.git dirs and minified/binary files, plus parsing a .potatyignore string
 * (comments, blanks, extra globs, and "!" negations).
 */

package com.potaty.backend

import com.potaty.backend.github.IgnoreRules
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IgnoreRulesTest {

    @Test
    fun ignoresDefaultDependencyAndBuildDirs() {
        val rules = IgnoreRules.default()
        assertTrue(rules.isIgnored("node_modules/react/index.js"))
        assertTrue(rules.isIgnored("frontend/node_modules/lodash/lodash.js"))
        assertTrue(rules.isIgnored("build/output.txt"))
        assertTrue(rules.isIgnored("backend/build/classes/Main.class"))
        assertTrue(rules.isIgnored("dist/bundle.js"))
        assertTrue(rules.isIgnored("vendor/github.com/pkg/errors/errors.go"))
        assertTrue(rules.isIgnored(".git/config"))
        assertTrue(rules.isIgnored("target/release/app"))
    }

    @Test
    fun ignoresMinifiedAndBinaryAndLockFiles() {
        val rules = IgnoreRules.default()
        assertTrue(rules.isIgnored("public/app.min.js"))
        assertTrue(rules.isIgnored("assets/styles.min.css"))
        assertTrue(rules.isIgnored("dist/app.js.map"))
        assertTrue(rules.isIgnored("package-lock.json"))
        assertTrue(rules.isIgnored("yarn.lock"))
        assertTrue(rules.isIgnored("docs/logo.png"))
        assertTrue(rules.isIgnored("lib/native.so"))
    }

    @Test
    fun keepsHighSignalSourceFiles() {
        val rules = IgnoreRules.default()
        assertTrue(rules.keep("src/main/kotlin/App.kt"))
        assertTrue(rules.keep("README.md"))
        assertTrue(rules.keep("server/index.ts"))
        assertTrue(rules.keep("pkg/handler/user.go"))
        // a regular (non-minified) js file is kept
        assertTrue(rules.keep("src/app.js"))
        assertFalse(rules.isIgnored("src/app.js"))
    }

    @Test
    fun parsePotatyignoreSkipsCommentsAndBlanks() {
        val content = """
            # a comment
            *.generated.kt

            secrets/
        """.trimIndent()
        val parsed = IgnoreRules.parse(content)
        assertTrue(parsed.contains("*.generated.kt"))
        assertTrue(parsed.contains("secrets/"))
        assertFalse(parsed.any { it.startsWith("#") })
        assertFalse(parsed.any { it.isBlank() })
    }

    @Test
    fun customPotatyignoreAddsAndNegatesPatterns() {
        val content = """
            # ignore generated code
            *.generated.kt
            secrets/
            # but keep one file the defaults would drop
            !public/vendor-allowlist.min.js
        """.trimIndent()
        val rules = IgnoreRules.of(content)

        // custom ignores apply
        assertTrue(rules.isIgnored("model/User.generated.kt"))
        assertTrue(rules.isIgnored("secrets/prod.env"))
        // negation re-includes a path the default *.min.js rule would have ignored
        assertFalse(rules.isIgnored("public/vendor-allowlist.min.js"))
        // defaults still apply to everything else
        assertTrue(rules.isIgnored("node_modules/x/y.js"))
        assertTrue(rules.keep("src/Main.kt"))
    }

    @Test
    fun anchoredPatternMatchesOnlyAtRoot() {
        val rules = IgnoreRules.of("/config.local.json")
        assertTrue(rules.isIgnored("config.local.json"))
        assertFalse(rules.isIgnored("nested/config.local.json"))
    }

    @Test
    fun credentialPathsCannotBeReincludedByRepositoryRules() {
        val rules =
            IgnoreRules.of(
                """
                !.env
                !config/prod.pem
                !nested/.npmrc
                !secrets/token.txt
                """.trimIndent()
            )

        assertTrue(rules.isIgnored(".env"))
        assertTrue(rules.isIgnored("config/prod.pem"))
        assertTrue(rules.isIgnored("nested/.npmrc"))
        assertTrue(rules.isIgnored("secrets/token.txt"))
        assertTrue(rules.keep(".env.example"), "documented templates remain indexable")
    }
}
