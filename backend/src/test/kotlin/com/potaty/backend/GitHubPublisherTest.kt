/*
 * Copyright (c) 2026, Potaty
 *
 * GitHubPublisher test (WS11; plan 18.6, 4.2 "PR publish"). A MockEngine simulates the GitHub REST
 * sequence — resolve base head, create branch, look up + commit file(s), open the PR — with NO
 * network. Asserts that:
 *   - the full create-branch -> commit -> open-PR sequence is exercised in order,
 *   - a new file is created with `sha=null` (no spurious update), an existing file with its sha,
 *   - the opened PR body carries the AI-generated disclosure, the evidence/validation summary and
 *     the human review checklist (plan 18.6),
 *   - NO merge call is ever made (Potaty never auto-merges).
 */

package com.potaty.backend

import com.potaty.backend.github.GitHubConfig
import com.potaty.backend.github.GitHubPublisher
import com.potaty.backend.github.PublishEvidence
import com.potaty.backend.github.PublishFile
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class GitHubPublisherTest {

    private val config =
        GitHubConfig(
            apiBaseUrl = "https://api.github.test",
            appId = "12345",
            privateKeyPem = "unused-in-this-test",
            webhookSecret = "secret",
            maxFileBytes = 1_000_000,
            maxFilesPerIndex = 100
        )

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    /** Records every request the publisher makes so the test can assert the sequence + payloads. */
    private data class Call(
        val method: HttpMethod,
        val path: String,
        val query: String,
        val body: String
    )

    private fun recordingClient(existingFile: Boolean, calls: MutableList<Call>): HttpClient =
        HttpClient(
            MockEngine { request ->
                // All publisher request bodies are set via setBody(String) -> TextContent; read
                // .text.
                val body = (request.body as? TextContent)?.text ?: ""
                calls.add(
                    Call(request.method, request.url.encodedPath, request.url.encodedQuery, body)
                )
                val path = request.url.encodedPath
                when {
                    // 1. base head sha
                    request.method == HttpMethod.Get && path.contains("/git/ref/heads/") ->
                        respond(
                            """
                            {
                              "ref": "refs/heads/main",
                              "object": {"sha": "basesha123", "type": "commit"}
                            }
                            """.trimIndent(),
                            HttpStatusCode.OK,
                            jsonHeaders
                        )

                    // 2. create branch ref
                    request.method == HttpMethod.Post && path.endsWith("/git/refs") ->
                        respond(
                            """{"ref":"refs/heads/potaty/x","object":{"sha":"basesha123"}}""",
                            HttpStatusCode.Created,
                            jsonHeaders
                        )

                    // 3a. existing-file lookup
                    request.method == HttpMethod.Get && path.contains("/contents/") ->
                        if (existingFile) {
                            respond(
                                """{"sha":"oldblob","path":"docs/d.md","type":"file"}""",
                                HttpStatusCode.OK,
                                jsonHeaders
                            )
                        } else {
                            respond(
                                """{"message":"Not Found"}""",
                                HttpStatusCode.NotFound,
                                jsonHeaders
                            )
                        }

                    // 3b. commit file
                    request.method == HttpMethod.Put && path.contains("/contents/") ->
                        respond(
                            """
                            {
                              "content": {"sha": "newblob", "path": "docs/d.md"},
                              "commit": {"sha": "commit1"}
                            }
                            """.trimIndent(),
                            HttpStatusCode.OK,
                            jsonHeaders
                        )

                    // 4. open PR
                    request.method == HttpMethod.Post && path.endsWith("/pulls") ->
                        respond(
                            """
                            {
                              "number": 42,
                              "html_url": "https://github.test/octo/demo/pull/42",
                              "state": "open",
                              "merged": false
                            }
                            """.trimIndent(),
                            HttpStatusCode.Created,
                            jsonHeaders
                        )

                    else ->
                        respond(
                            """{"message":"unexpected ${'$'}path"}""",
                            HttpStatusCode.NotFound,
                            jsonHeaders
                        )
                }
            }
        )

    private val evidence =
        PublishEvidence(
            nodeCount = 3,
            edgeCount = 2,
            nodeCoverage = 1.0,
            edgeCoverage = 0.5,
            groundedEdgeRatio = 0.5,
            inferredEdgeCount = 1,
            unsupportedCriticalClaims = 0,
            validationValid = true,
            validationErrorCount = 0,
            validationWarningCount = 1,
            meetsPublishThreshold = false,
            sourceSummary = "octo/demo@main"
        )

    @Test
    fun opensPullRequestWithoutMergingForNewFile() = runBlocking {
        val calls = mutableListOf<Call>()
        val publisher =
            GitHubPublisher(config, recordingClient(existingFile = false, calls = calls))

        val result =
            publisher.publishPullRequest(
                installationToken = "ghs_token",
                owner = "octo",
                repo = "demo",
                baseBranch = "main",
                branch = "potaty/diagram-v1",
                title = "docs: add Demo diagram (Potaty)",
                commitMessage = "docs: add diagram",
                diagramSummary = "Adds `docs/d.md` describing the system.",
                files = listOf(PublishFile(path = "docs/d.md", content = "# Demo\n")),
                evidence = evidence
            )

        assertEquals(42, result.number)
        assertEquals("https://github.test/octo/demo/pull/42", result.htmlUrl)
        assertEquals("open", result.state)
        assertEquals("potaty/diagram-v1", result.branch)

        // Sequence: base ref GET, create-ref POST, contents GET, contents PUT, pulls POST.
        assertEquals(5, calls.size, "expected the full publish sequence, got $calls")
        assertTrue(
            calls[0].method == HttpMethod.Get && calls[0].path.contains("/git/ref/heads/main")
        )
        assertTrue(calls[1].method == HttpMethod.Post && calls[1].path.endsWith("/git/refs"))
        assertTrue(
            calls[1].body.contains("refs/heads/potaty/diagram-v1"),
            "branch ref created from base sha"
        )
        assertTrue(calls[1].body.contains("basesha123"))
        assertTrue(calls[2].method == HttpMethod.Get && calls[2].path.contains("/contents/"))
        assertTrue(calls[3].method == HttpMethod.Put && calls[3].path.contains("/contents/"))
        // New file -> NO sha in the PUT body (creating, not updating).
        assertFalse(
            calls[3].body.contains("\"sha\""),
            "creating a new file must not send a blob sha"
        )
        assertTrue(calls[3].body.contains("\"branch\":\"potaty/diagram-v1\""))
        assertTrue(calls[4].method == HttpMethod.Post && calls[4].path.endsWith("/pulls"))

        // The PR body carries disclosure + evidence + checklist.
        val prBody = calls[4].body
        assertTrue(prBody.contains("AI-generated diagram"), "PR body must disclose AI generation")
        assertTrue(
            prBody.contains("review before merging", ignoreCase = true),
            "PR body must instruct human review"
        )
        assertTrue(prBody.contains("Evidence & validation"), "PR body must summarize evidence")
        assertTrue(prBody.contains("Node evidence coverage"))
        assertTrue(prBody.contains("Review checklist"), "PR body must include a review checklist")
        assertTrue(prBody.contains("- [ ]"), "checklist items must be unchecked boxes")
        assertTrue(prBody.contains("never auto-merges", ignoreCase = true))
        // head = our branch, base = main, draft=false.
        assertTrue(prBody.contains("\"head\":\"potaty/diagram-v1\""))
        assertTrue(prBody.contains("\"base\":\"main\""))
        assertTrue(prBody.contains("\"draft\":false"))

        // CRITICAL: no merge call anywhere (Potaty never auto-merges).
        assertTrue(calls.none { it.path.contains("/merge") }, "must never call the merge endpoint")
        assertTrue(calls.none { it.method == HttpMethod.Put && it.path.endsWith("/merge") })
    }

    @Test
    fun updatingExistingFileSendsItsBlobSha() = runBlocking {
        val calls = mutableListOf<Call>()
        val publisher = GitHubPublisher(config, recordingClient(existingFile = true, calls = calls))

        publisher.publishPullRequest(
            installationToken = "ghs_token",
            owner = "octo",
            repo = "demo",
            baseBranch = "main",
            branch = "potaty/diagram-v2",
            title = "t",
            commitMessage = "m",
            diagramSummary = "s",
            files = listOf(PublishFile(path = "docs/d.md", content = "# Demo v2\n")),
            evidence = evidence
        )

        val put = calls.single { it.method == HttpMethod.Put }
        assertTrue(
            put.body.contains("\"sha\":\"oldblob\""),
            "updating an existing file must send its current blob sha"
        )
        // Still no merge.
        assertTrue(calls.none { it.path.contains("/merge") })
    }

    @Test
    fun buildPrBodyContainsAllRequiredSections() {
        val publisher =
            GitHubPublisher(
                config,
                HttpClient(MockEngine { respond("{}", HttpStatusCode.OK, jsonHeaders) })
            )
        val body = publisher.buildPrBody("A test diagram.", evidence)
        assertTrue(body.startsWith("> **AI-generated diagram"), "disclosure must lead the body")
        assertTrue(
            body.contains("| Node evidence coverage | 100.0% |"),
            "node coverage percent rendered"
        )
        assertTrue(
            body.contains("| Edge evidence coverage | 50.0% |"),
            "edge coverage percent rendered"
        )
        assertTrue(body.contains("| Meets publish threshold | no |"))
        assertTrue(body.contains("octo/demo@main"))
        assertTrue(body.contains("## Review checklist"))
        assertTrue(body.contains("Potaty never auto-merges"))
    }
}
