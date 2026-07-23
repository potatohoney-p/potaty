/*
 * Copyright (c) 2026, Potaty
 */

package com.potaty.backend

import com.potaty.backend.github.parseGitHubRepoUrl
import com.potaty.backend.github.validGitHubCoordinates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitHubRouteInputTest {
    @Test
    fun acceptsOnlyGitHubRepositoryHostsAndShorthand() {
        assertEquals("octocat", parseGitHubRepoUrl("octocat/Hello-World")?.owner)
        assertEquals(
            "Hello-World",
            parseGitHubRepoUrl("https://github.com/octocat/Hello-World.git")?.repo
        )
        assertEquals(
            "feature/safe-ref",
            parseGitHubRepoUrl(
                "https://www.github.com/octocat/Hello-World/tree/feature/safe-ref"
            )?.ref
        )
        assertEquals("octocat", parseGitHubRepoUrl("git@github.com:octocat/Hello-World.git")?.owner)

        assertNull(parseGitHubRepoUrl("https://example.com/octocat/Hello-World"))
        assertNull(parseGitHubRepoUrl("https://github.com.evil.example/octocat/Hello-World"))
        assertNull(parseGitHubRepoUrl("git@example.com:octocat/Hello-World"))
    }

    @Test
    fun rejectsUnsafeCoordinatesAndRefs() {
        assertTrue(validGitHubCoordinates("octocat", "Hello-World", "main"))
        assertTrue(validGitHubCoordinates("octo-cat", "repo.name", "feature/hangul-한글"))

        assertFalse(validGitHubCoordinates("-octocat", "Hello-World", "main"))
        assertFalse(validGitHubCoordinates("octocat", "repo/name", "main"))
        assertFalse(validGitHubCoordinates("octocat", "Hello-World", "refs//heads/main"))
        assertFalse(validGitHubCoordinates("octocat", "Hello-World", "main.lock"))
        assertFalse(validGitHubCoordinates("octocat", "Hello-World", "main\nnext"))
    }
}
