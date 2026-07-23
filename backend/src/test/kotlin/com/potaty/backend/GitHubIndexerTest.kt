/*
 * Copyright (c) 2026, Potaty
 *
 * GitHubIndexer integration test (plan section 18 "GitHub mock -> source sync -> code index").
 * A MockEngine serves a small fake git tree + blobs (no network). Asserts that:
 *   - ignored paths (node_modules, *.min.js) are skipped,
 *   - high-signal source files are fetched, normalized, chunked and PERSISTED via SourceRepository,
 *   - the persisted chunks are tenant-scoped and carry the repo path for evidence.
 * Runs against embedded H2 (testConfig()).
 */

package com.potaty.backend

import com.potaty.backend.github.GitHubConfig
import com.potaty.backend.github.GitHubIndexer
import com.potaty.backend.github.GitHubTokenException
import com.potaty.backend.jobs.Idempotency
import com.potaty.backend.persistence.repositories.IdempotencyConflictException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

class GitHubIndexerTest {

    private fun b64(s: String): String =
        Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))

    private val repoSecret = "ghp_" + "R".repeat(36)
    private val appKt =
        "package com.example\n\n" +
            "class App {\n    fun run() {\n" +
            "        println(\"hello\")\n    }\n}\n"
    private val readme =
        "# Demo\n\nA small service.\n\nGITHUB_TOKEN=$repoSecret\n\nUser -> API -> Postgres\n"

    private val config =
        GitHubConfig(
            apiBaseUrl = "https://api.github.test",
            appId = "12345",
            privateKeyPem = "unused-in-this-test",
            webhookSecret = "secret",
            maxFileBytes = 1_000_000,
            maxFilesPerIndex = 100
        )

    /** MockEngine routing on URL path: the recursive tree, then blobs keyed by sha. */
    private fun mockClient(requestCount: AtomicInteger? = null): HttpClient {
        val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
        val tree =
            """
            {
              "sha": "treesha",
              "truncated": false,
              "tree": [
                {"path": "src/App.kt", "mode": "100644", "type": "blob", "sha": "sha-app", "size": ${appKt.length}},
                {"path": "README.md", "mode": "100644", "type": "blob", "sha": "sha-readme", "size": ${readme.length}},
                {"path": "node_modules/dep/index.js", "mode": "100644", "type": "blob", "sha": "sha-dep", "size": 10},
                {"path": "public/app.min.js", "mode": "100644", "type": "blob", "sha": "sha-min", "size": 10},
                {"path": "src", "mode": "040000", "type": "tree", "sha": "sha-dir"}
              ]
            }
        """
                .trimIndent()

        val blobs =
            mapOf(
                "sha-app" to
                    """{"sha":"sha-app","size":${appKt.length},"encoding":"base64","content":"${b64(
                        appKt
                    )}"}""",
                "sha-readme" to
                    """{"sha":"sha-readme","size":${readme.length},"encoding":"base64","content":"${b64(
                        readme
                    )}"}"""
            )

        return HttpClient(
            MockEngine { request ->
                requestCount?.incrementAndGet()
                val path = request.url.encodedPath
                when {
                    path.contains("/git/trees/") -> respond(tree, HttpStatusCode.OK, jsonHeaders)
                    path.contains("/git/blobs/") -> {
                        val sha = path.substringAfterLast("/")
                        val body = blobs[sha]
                        if (body != null) {
                            respond(body, HttpStatusCode.OK, jsonHeaders)
                        } else {
                            respond(
                                """{"message":"not found"}""",
                                HttpStatusCode.NotFound,
                                jsonHeaders
                            )
                        }
                    }
                    else ->
                        respond(
                            """{"message":"unexpected ${'$'}path"}""",
                            HttpStatusCode.NotFound,
                            jsonHeaders
                        )
                }
            }
        )
    }

    private fun failOnceBlobClient(
        failingSha: String,
        includePotatyIgnore: Boolean,
        failureCount: AtomicInteger = AtomicInteger()
    ): HttpClient {
        val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
        val ignoreText = "README.md\n"
        val ignoreEntry =
            if (includePotatyIgnore) {
                """,
                {"path":".potatyignore","mode":"100644","type":"blob",
                 "sha":"sha-ignore","size":${ignoreText.length}}"""
            } else {
                ""
            }
        val tree =
            """
            {
              "sha":"treesha",
              "truncated":false,
              "tree":[
                {"path":"src/App.kt","mode":"100644","type":"blob","sha":"sha-app","size":${appKt.length}}
                $ignoreEntry
              ]
            }
            """.trimIndent()
        val blobs =
            mapOf(
                "sha-app" to
                    """{
                        "sha":"sha-app","size":${appKt.length},"encoding":"base64",
                        "content":"${b64(appKt)}"
                    }
                    """.trimIndent(),
                "sha-ignore" to
                    """{
                        "sha":"sha-ignore","size":${ignoreText.length},"encoding":"base64",
                        "content":"${b64(ignoreText)}"
                    }
                    """.trimIndent()
            )

        return HttpClient(
            MockEngine { request ->
                val path = request.url.encodedPath
                when {
                    path.contains("/git/trees/") -> respond(tree, HttpStatusCode.OK, jsonHeaders)
                    path.contains("/git/blobs/") -> {
                        val sha = path.substringAfterLast("/")
                        if (sha == failingSha && failureCount.getAndIncrement() == 0) {
                            respond(
                                """{"message":"temporary upstream failure"}""",
                                HttpStatusCode.ServiceUnavailable,
                                jsonHeaders
                            )
                        } else {
                            respond(checkNotNull(blobs[sha]), HttpStatusCode.OK, jsonHeaders)
                        }
                    }
                    else -> respond("{}", HttpStatusCode.NotFound, jsonHeaders)
                }
            }
        )
    }

    private fun malformedShapeClient(
        malformedTree: Boolean,
        missingMandatoryField: Boolean = false
    ): HttpClient {
        val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
        val tree =
            if (malformedTree) {
                if (missingMandatoryField) {
                    """{"sha":"treesha","truncated":false}"""
                } else {
                    "{}"
                }
            } else {
                val expectedSize = if (missingMandatoryField) 0 else appKt.length
                """{
                    "sha":"treesha","truncated":false,
                    "tree":[{"path":"src/App.kt","mode":"100644","type":"blob",
                    "sha":"sha-app","size":$expectedSize}]
                }
                """.trimIndent()
            }
        return HttpClient(
            MockEngine { request ->
                if (request.url.encodedPath.contains("/git/trees/")) {
                    respond(tree, HttpStatusCode.OK, jsonHeaders)
                } else {
                    val blob =
                        if (missingMandatoryField) {
                            """{"sha":"sha-app"}"""
                        } else {
                            "{}"
                        }
                    respond(blob, HttpStatusCode.OK, jsonHeaders)
                }
            }
        )
    }

    @Test
    fun indexesRepoAndPersistsChunks() = runBlocking {
        val cfg = testConfig()
        val graph = AppGraph.create(cfg)
        try {
            val workspaceId = UUID.fromString(cfg.auth.devWorkspaceId)
            val userId = UUID.fromString(cfg.auth.devUserId)
            val projectId = UUID.fromString(cfg.auth.devProjectId)

            val requestCount = AtomicInteger()
            val indexer = GitHubIndexer(config, mockClient(requestCount), graph.sources)
            val requestHash = "sha256:" + Idempotency.sha256("octo/demo/main")
            val result =
                indexer.index(
                    workspaceId = workspaceId,
                    projectId = projectId,
                    owner = "octo",
                    repo = "demo",
                    ref = "main",
                    createdBy = userId,
                    ingestionKey = "github:test-index-1",
                    requestHash = requestHash,
                    installationTokenProvider = { "ghs_faketoken" }
                )

            // only the two high-signal files were indexed; node_modules + *.min.js were skipped.
            assertEquals(2, result.filesIndexed, "App.kt and README.md should be indexed")
            assertTrue(result.filesSkipped >= 2, "node_modules and *.min.js should be skipped")
            assertTrue(result.chunkCount > 0, "chunks should have been produced")
            assertTrue(result.contentHash.startsWith("sha256:"))
            assertEquals(false, result.treeTruncated)

            // chunks are actually persisted and tenant-scoped, and carry the repo path.
            val chunks = graph.sources.listChunks(workspaceId, result.sourceVersionId)
            assertEquals(result.chunkCount, chunks.size, "persisted chunk count matches")
            assertTrue(chunks.isNotEmpty())
            val paths = chunks.mapNotNull { it.path }.toSet()
            assertTrue(
                paths.contains("src/App.kt"),
                "App.kt chunk persisted with its path, got $paths"
            )
            assertTrue(
                paths.contains("README.md"),
                "README.md chunk persisted with its path, got $paths"
            )
            assertTrue(paths.none { it.startsWith("node_modules/") }, "no node_modules chunks")
            val storedText = chunks.joinToString("\n") { it.text }
            assertTrue(repoSecret !in storedText, "raw repository credential must never persist")
            assertTrue(
                "[REDACTED:" in storedText,
                "redaction marker should preserve understandable context"
            )

            // tenant isolation: a different workspace cannot read these chunks.
            val other = graph.sources.listChunks(UUID.randomUUID(), result.sourceVersionId)
            assertTrue(other.isEmpty(), "another workspace sees no chunks")

            // the source itself is retrievable and typed as a GitHub repo.
            val source = graph.sources.findSource(workspaceId, result.sourceId)
            assertNotNull(source)
            assertEquals("GITHUB_REPO", source.sourceType)

            val externalRequestsAfterFirstIndex = requestCount.get()
            val replay =
                indexer.index(
                    workspaceId = workspaceId,
                    projectId = projectId,
                    owner = "octo",
                    repo = "demo",
                    ref = "main",
                    createdBy = userId,
                    ingestionKey = "github:test-index-1",
                    requestHash = requestHash,
                    installationTokenProvider = { "rotated-token-is-not-opened" }
                )
            assertEquals(result.sourceId, replay.sourceId)
            assertEquals(result.sourceVersionId, replay.sourceVersionId)
            assertEquals(
                externalRequestsAfterFirstIndex,
                requestCount.get(),
                "stored replay must not call GitHub again"
            )

            assertFailsWith<IdempotencyConflictException> {
                indexer.index(
                    workspaceId = workspaceId,
                    projectId = projectId,
                    owner = "octo",
                    repo = "demo",
                    ref = "main",
                    createdBy = userId,
                    ingestionKey = "github:test-index-1",
                    requestHash = "sha256:" + Idempotency.sha256("different request"),
                    installationTokenProvider = { "unused" }
                )
            }
            assertEquals(externalRequestsAfterFirstIndex, requestCount.get())
        } finally {
            graph.stop()
        }
    }

    @Test
    fun concurrentEquivalentIndexesClaimBeforeTokenAndRemoteCalls() = runBlocking {
        val cfg = testConfig()
        val graph = AppGraph.create(cfg)
        try {
            val workspaceId = UUID.fromString(cfg.auth.devWorkspaceId)
            val userId = UUID.fromString(cfg.auth.devUserId)
            val projectId = UUID.fromString(cfg.auth.devProjectId)
            val tokenRequests = AtomicInteger()
            val githubRequests = AtomicInteger()
            val indexer = GitHubIndexer(config, mockClient(githubRequests), graph.sources)
            val requestHash = "sha256:" + Idempotency.sha256("octo/demo/main/concurrent")

            val results = coroutineScope {
                (1..8).map {
                    async(Dispatchers.IO) {
                        indexer.index(
                            workspaceId = workspaceId,
                            projectId = projectId,
                            owner = "octo",
                            repo = "demo",
                            ref = "main",
                            createdBy = userId,
                            ingestionKey = "github:concurrent-index",
                            requestHash = requestHash,
                            installationTokenProvider = {
                                tokenRequests.incrementAndGet()
                                delay(75)
                                "ghs_faketoken"
                            }
                        )
                    }
                }.awaitAll()
            }

            assertEquals(1, results.map { it.sourceId }.toSet().size)
            assertEquals(1, results.map { it.sourceVersionId }.toSet().size)
            assertEquals(1, tokenRequests.get(), "only the claim owner may open credentials")
            assertEquals(3, githubRequests.get(), "one tree and two eligible blobs are fetched")
            assertEquals(1, graph.sources.listSources(workspaceId, projectId).size)
            assertEquals(1, graph.sources.countAtomicIngestionClaims(workspaceId))
        } finally {
            graph.stop()
        }
    }

    @Test
    fun eligibleBlobFailureAbortsSnapshotAndReleasesClaimForRetry() = runBlocking {
        assertFailClosedBlobRetry(failingSha = "sha-app", includePotatyIgnore = false)
    }

    @Test
    fun potatyIgnoreFailureAbortsSnapshotAndReleasesClaimForRetry() = runBlocking {
        assertFailClosedBlobRetry(failingSha = "sha-ignore", includePotatyIgnore = true)
    }

    @Test
    fun malformedSuccessfulTreeDoesNotPersistPartialSnapshot() = runBlocking {
        assertMalformedShapeRejected(malformedTree = true)
    }

    @Test
    fun malformedSuccessfulBlobDoesNotPersistPartialSnapshot() = runBlocking {
        assertMalformedShapeRejected(malformedTree = false)
    }

    @Test
    fun successfulTreeMissingMandatoryArrayDoesNotBecomeEmptySnapshot() = runBlocking {
        assertMalformedShapeRejected(malformedTree = true, missingMandatoryField = true)
    }

    @Test
    fun zeroByteBlobMissingMandatoryWireFieldsIsRejected() = runBlocking {
        assertMalformedShapeRejected(malformedTree = false, missingMandatoryField = true)
    }

    private suspend fun assertMalformedShapeRejected(
        malformedTree: Boolean,
        missingMandatoryField: Boolean = false
    ) {
        val cfg = testConfig()
        val graph = AppGraph.create(cfg)
        try {
            val workspaceId = UUID.fromString(cfg.auth.devWorkspaceId)
            val userId = UUID.fromString(cfg.auth.devUserId)
            val projectId = UUID.fromString(cfg.auth.devProjectId)
            val suffix =
                (if (malformedTree) "tree" else "blob") +
                    (if (missingMandatoryField) "-missing" else "")
            val indexer =
                GitHubIndexer(
                    config,
                    malformedShapeClient(malformedTree, missingMandatoryField),
                    graph.sources
                )

            assertFailsWith<GitHubTokenException> {
                indexer.index(
                    workspaceId = workspaceId,
                    projectId = projectId,
                    owner = "octo",
                    repo = "demo",
                    ref = "main",
                    createdBy = userId,
                    ingestionKey = "github:malformed-$suffix",
                    requestHash = "sha256:" + Idempotency.sha256("malformed-$suffix"),
                    installationTokenProvider = { "ghs_faketoken" }
                )
            }
            assertEquals(0, graph.sources.listSources(workspaceId, projectId).size)
            assertEquals(0, graph.sources.countAtomicIngestionClaims(workspaceId))
        } finally {
            graph.stop()
        }
    }

    private suspend fun assertFailClosedBlobRetry(
        failingSha: String,
        includePotatyIgnore: Boolean
    ) {
        val cfg = testConfig()
        val graph = AppGraph.create(cfg)
        try {
            val workspaceId = UUID.fromString(cfg.auth.devWorkspaceId)
            val userId = UUID.fromString(cfg.auth.devUserId)
            val projectId = UUID.fromString(cfg.auth.devProjectId)
            val failures = AtomicInteger()
            val indexer =
                GitHubIndexer(
                    config,
                    failOnceBlobClient(failingSha, includePotatyIgnore, failures),
                    graph.sources
                )
            val requestHash = "sha256:" + Idempotency.sha256("fail-closed/$failingSha")
            val ingestionKey = "github:fail-closed-$failingSha"

            assertFailsWith<GitHubTokenException> {
                indexer.index(
                    workspaceId = workspaceId,
                    projectId = projectId,
                    owner = "octo",
                    repo = "demo",
                    ref = "main",
                    createdBy = userId,
                    ingestionKey = ingestionKey,
                    requestHash = requestHash,
                    installationTokenProvider = { "ghs_faketoken" }
                )
            }
            assertEquals(0, graph.sources.listSources(workspaceId, projectId).size)
            assertEquals(0, graph.sources.countAtomicIngestionClaims(workspaceId))

            val retried =
                indexer.index(
                    workspaceId = workspaceId,
                    projectId = projectId,
                    owner = "octo",
                    repo = "demo",
                    ref = "main",
                    createdBy = userId,
                    ingestionKey = ingestionKey,
                    requestHash = requestHash,
                    installationTokenProvider = { "ghs_faketoken" }
                )
            assertEquals(1, retried.filesIndexed)
            assertEquals(1, graph.sources.listSources(workspaceId, projectId).size)
            assertEquals(1, graph.sources.countAtomicIngestionClaims(workspaceId))
        } finally {
            graph.stop()
        }
    }
}
