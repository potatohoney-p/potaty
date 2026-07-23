/*
 * Copyright (c) 2026, Potaty
 *
 * Read-only GitHub repository indexer (plan section 4.2 P1 GitHub input; section 18 integration
 * test "GitHub mock -> source sync -> code index"). Given an installation token and owner/repo/ref:
 *
 *   1. GET /repos/{owner}/{repo}/git/trees/{ref}?recursive=1  -> the full file tree.
 *   2. Filter the blobs through [IgnoreRules] (defaults + repo-local .potatyignore) and size caps,
 *      keeping high-signal source files.
 *   3. For each kept blob, GET /repos/{owner}/{repo}/git/blobs/{sha} and base64-decode the content.
 *   4. Normalize + redact through SourceSafetyGateway, then chunk each file, tagging chunks with
 *      their repo path so IR evidence points back at the exact file + line span.
 *   5. Persist one Source ("GITHUB_REPO") + SourceVersion (content_hash over all files) + all chunks
 *      via SourceRepository — the SAME tenant-scoped persistence the paste-ingest path uses.
 *
 * Everything is workspace-scoped; the indexer never reads or writes outside the given workspaceId.
 */

package com.potaty.backend.github

import com.potaty.backend.persistence.repositories.AtomicIngestionClaim
import com.potaty.backend.persistence.repositories.AtomicSourceRecord
import com.potaty.backend.persistence.repositories.IdempotencyConflictException
import com.potaty.backend.persistence.repositories.SourceRepository
import com.potaty.backend.source.Chunker
import com.potaty.backend.source.SourceSafetyGateway
import com.potaty.backend.source.TextChunk
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLPath
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Result of an index run: ids the caller (route/job) returns to the client. */
data class IndexResult(
    val sourceId: UUID,
    val sourceVersionId: UUID,
    val contentHash: String,
    val filesIndexed: Int,
    val filesSkipped: Int,
    val chunkCount: Int,
    val treeTruncated: Boolean,
    val owner: String,
    val repo: String,
    val ref: String
)

class GitHubIndexInProgressException(message: String) : RuntimeException(message)

@Serializable
private data class GitHubIngestionMetadata(
    val kind: String = "github",
    val owner: String,
    val repo: String,
    val ref: String,
    val filesIndexed: Int,
    val filesSkipped: Int,
    val chunkCount: Int,
    val treeTruncated: Boolean
)

class GitHubIndexer(
    private val config: GitHubConfig,
    private val httpClient: HttpClient,
    private val sources: SourceRepository
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Claims ownership before credentials or GitHub are opened, then persists one atomic snapshot. */
    @Suppress("LongParameterList")
    suspend fun index(
        workspaceId: UUID,
        projectId: UUID,
        owner: String,
        repo: String,
        ref: String?,
        createdBy: UUID?,
        ingestionKey: String,
        requestHash: String,
        installationTokenProvider: suspend () -> String = { "" }
    ): IndexResult {
        val requestedRef = ref?.trim()?.takeIf { it.isNotEmpty() }
        require(owner.isNotBlank() && repo.isNotBlank()) {
            "owner and repo are required"
        }

        replay(workspaceId, projectId, ingestionKey, requestHash)?.let { stored ->
            return validateReplay(stored, owner, repo, requestedRef)
        }

        return when (
            val claim =
                awaitClaim(
                    workspaceId,
                    projectId,
                    ingestionKey,
                    requestHash
                )
        ) {
            is AtomicIngestionClaim.Complete ->
                validateReplay(claim.source.toIndexResult(), owner, repo, requestedRef)

            is AtomicIngestionClaim.Acquired -> {
                try {
                    val installationToken =
                        withClaimLease(workspaceId, ingestionKey, claim.token) {
                            installationTokenProvider()
                        }
                    val resolvedRef =
                        requestedRef
                            ?: withClaimLease(workspaceId, ingestionKey, claim.token) {
                                fetchDefaultBranch(owner, repo, installationToken)
                            }
                    indexOwned(
                        workspaceId = workspaceId,
                        projectId = projectId,
                        owner = owner,
                        repo = repo,
                        ref = resolvedRef,
                        installationToken = installationToken,
                        createdBy = createdBy,
                        ingestionKey = ingestionKey,
                        requestHash = requestHash,
                        claimToken = claim.token
                    )
                } catch (cause: Throwable) {
                    withContext(NonCancellable) {
                        sources.releaseAtomicIngestionClaim(
                            workspaceId,
                            ingestionKey,
                            claim.token
                        )
                    }
                    throw cause
                }
            }

            is AtomicIngestionClaim.Busy -> error("awaitClaim must not return a busy claim")
        }
    }

    @Suppress("LongParameterList", "LongMethod")
    private suspend fun indexOwned(
        workspaceId: UUID,
        projectId: UUID,
        owner: String,
        repo: String,
        ref: String,
        installationToken: String,
        createdBy: UUID?,
        ingestionKey: String,
        requestHash: String,
        claimToken: UUID
    ): IndexResult {
        val tree =
            withClaimLease(workspaceId, ingestionKey, claimToken) {
                fetchTree(owner, repo, ref, installationToken)
            }

        // Discover an optional repo-local .potatyignore so a repo can tune what gets indexed.
        val potatyignoreEntry =
            tree.tree.firstOrNull { it.type == "blob" && it.path == ".potatyignore" }
        val potatyignore =
            potatyignoreEntry?.let { entry ->
                when (
                    val result =
                        withClaimLease(workspaceId, ingestionKey, claimToken) {
                            fetchBlob(
                                owner,
                                repo,
                                entry.sha,
                                entry.size,
                                installationToken
                            )
                        }
                ) {
                    is BlobRead.Text -> result.value
                    BlobRead.Binary,
                    BlobRead.TooLarge ->
                        throw GitHubTokenException(".potatyignore is not a readable text file")
                }
            }
        val rules = IgnoreRules.of(potatyignore)

        val blobs = tree.tree.filter { it.type == "blob" }
        var skipped = 0
        val eligible =
            blobs.filter { entry ->
                val keep =
                    entry.path != ".potatyignore" &&
                        rules.keep(entry.path) &&
                        (entry.size == null || entry.size <= config.maxFileBytes)
                if (!keep) skipped++
                keep
            }.sortedBy { it.path }
        val toIndex = eligible.take(config.maxFilesPerIndex)
        // Account for files dropped purely by the maxFilesPerIndex cap.
        skipped += (eligible.size - toIndex.size)

        val allChunks = mutableListOf<TextChunk>()
        val hashAccumulator = MessageDigest.getInstance("SHA-256")
        var chunkIndex = 0
        var filesIndexed = 0

        for (entry in toIndex) {
            val raw =
                when (
                    val result =
                        withClaimLease(workspaceId, ingestionKey, claimToken) {
                            fetchBlob(
                                owner,
                                repo,
                                entry.sha,
                                entry.size,
                                installationToken
                            )
                        }
                ) {
                    is BlobRead.Text -> result.value
                    BlobRead.Binary,
                    BlobRead.TooLarge -> {
                        skipped++
                        continue
                    }
                }
            val safe = SourceSafetyGateway.process(raw)
            hashAccumulator.update(entry.path.toByteArray(Charsets.UTF_8))
            hashAccumulator.update(0.toByte())
            hashAccumulator.update(safe.canonicalText.toByteArray(Charsets.UTF_8))
            hashAccumulator.update(0.toByte())
            val fileChunks = Chunker.chunk(safe.canonicalText, path = entry.path)
            // Re-index globally so chunkIndex is monotonic within the whole version.
            for (c in fileChunks) {
                allChunks.add(c.copy(chunkIndex = chunkIndex++))
            }
            filesIndexed++
        }

        val contentHash =
            "sha256:" + hashAccumulator.digest().joinToString("") { "%02x".format(it) }

        val metadata =
            GitHubIngestionMetadata(
                owner = owner,
                repo = repo,
                ref = ref,
                filesIndexed = filesIndexed,
                filesSkipped = skipped,
                chunkCount = allChunks.size,
                treeTruncated = tree.truncated
            )
        val stored =
            sources.createTextSourceAtomic(
                workspaceId = workspaceId,
                projectId = projectId,
                sourceType = "GITHUB_REPO",
                displayName = "$owner/$repo@$ref",
                externalRefJson = externalRef(owner, repo, ref),
                createdBy = createdBy,
                contentHash = contentHash,
                metadataJson =
                json.encodeToString(GitHubIngestionMetadata.serializer(), metadata),
                chunks = allChunks,
                ingestionKey = ingestionKey,
                requestHash = requestHash,
                claimToken = claimToken
            )
        return stored.toIndexResult()
    }

    private suspend fun awaitClaim(
        workspaceId: UUID,
        projectId: UUID,
        ingestionKey: String,
        requestHash: String
    ): AtomicIngestionClaim {
        val deadline = System.nanoTime() + CLAIM_WAIT_TIMEOUT.toNanos()
        var pollDelayMs = CLAIM_INITIAL_POLL_DELAY_MS
        while (true) {
            when (
                val claim =
                    sources.acquireAtomicIngestionClaim(
                        workspaceId = workspaceId,
                        projectId = projectId,
                        sourceType = GITHUB_SOURCE_TYPE,
                        ingestionKey = ingestionKey,
                        requestHash = requestHash,
                        leaseDuration = CLAIM_LEASE
                    )
            ) {
                is AtomicIngestionClaim.Acquired,
                is AtomicIngestionClaim.Complete -> return claim
                is AtomicIngestionClaim.Busy -> {
                    if (System.nanoTime() >= deadline) {
                        throw GitHubIndexInProgressException(
                            "An equivalent GitHub index is still in progress; " +
                                "retry with the same key"
                        )
                    }
                    delay(pollDelayMs)
                    pollDelayMs =
                        (pollDelayMs * 2).coerceAtMost(CLAIM_MAX_POLL_DELAY_MS)
                }
            }
        }
    }

    private suspend fun <T> withClaimLease(
        workspaceId: UUID,
        ingestionKey: String,
        claimToken: UUID,
        block: suspend () -> T
    ): T {
        renewClaim(workspaceId, ingestionKey, claimToken)
        val result = block()
        renewClaim(workspaceId, ingestionKey, claimToken)
        return result
    }

    private suspend fun renewClaim(
        workspaceId: UUID,
        ingestionKey: String,
        claimToken: UUID
    ) {
        sources.renewAtomicIngestionClaim(
            workspaceId,
            ingestionKey,
            claimToken,
            CLAIM_LEASE
        )
    }

    private fun validateReplay(
        stored: IndexResult,
        owner: String,
        repo: String,
        requestedRef: String?
    ): IndexResult {
        if (
            stored.owner != owner ||
            stored.repo != repo ||
            (requestedRef != null && stored.ref != requestedRef)
        ) {
            throw IdempotencyConflictException(
                "Idempotency-Key was already used for a different GitHub request"
            )
        }
        return stored
    }

    /** Replays a completed index without reopening GitHub or a workspace installation token. */
    suspend fun replay(
        workspaceId: UUID,
        projectId: UUID,
        ingestionKey: String,
        requestHash: String
    ): IndexResult? =
        sources.findAtomicIngestion(
            workspaceId = workspaceId,
            projectId = projectId,
            sourceType = "GITHUB_REPO",
            ingestionKey = ingestionKey,
            requestHash = requestHash
        )?.toIndexResult()

    private fun AtomicSourceRecord.toIndexResult(): IndexResult {
        val metadata = runCatching {
            json.decodeFromString(GitHubIngestionMetadata.serializer(), metadataJson)
        }.getOrElse { throw IllegalStateException("Stored GitHub metadata is invalid", it) }
        check(metadata.chunkCount == chunkCount) { "Stored GitHub chunks are incomplete" }
        return IndexResult(
            sourceId = sourceId,
            sourceVersionId = sourceVersionId,
            contentHash = contentHash,
            filesIndexed = metadata.filesIndexed,
            filesSkipped = metadata.filesSkipped,
            chunkCount = chunkCount,
            treeTruncated = metadata.treeTruncated,
            owner = metadata.owner,
            repo = metadata.repo,
            ref = metadata.ref
        )
    }

    private suspend fun fetchTree(
        owner: String,
        repo: String,
        ref: String,
        token: String
    ): GitTreeResponse {
        val url =
            "${config.apiBaseUrl}/repos/${seg(owner)}/${seg(repo)}/git/trees/" +
                "${seg(ref)}?recursive=1"
        val response = httpClient.get(url) { ghHeaders(token) }
        val text = response.bodyAsText()
        if (response.status.value !in 200..299) {
            throw GitHubTokenException("git tree request failed (${response.status.value})")
        }
        val tree = runCatching {
            json.decodeFromString(GitTreeResponse.serializer(), text)
        }.getOrElse {
            throw GitHubTokenException("git tree response was invalid")
        }
        val seenBlobPaths = mutableSetOf<String>()
        if (
            tree.sha.isBlank() ||
            tree.tree.any { entry ->
                entry.path.isBlank() ||
                    entry.sha.isBlank() ||
                    entry.type !in GIT_TREE_ENTRY_TYPES ||
                    (
                        entry.type == "blob" &&
                            (
                                entry.size == null ||
                                    entry.size < 0 ||
                                    !seenBlobPaths.add(entry.path)
                                )
                        )
            }
        ) {
            throw GitHubTokenException("git tree response was invalid")
        }
        return tree
    }

    /** Transient/remote failures throw; only content-classified binary/oversize files are skipped. */
    private suspend fun fetchBlob(
        owner: String,
        repo: String,
        sha: String,
        expectedSize: Long?,
        token: String
    ): BlobRead {
        val url = "${config.apiBaseUrl}/repos/${seg(owner)}/${seg(repo)}/git/blobs/${seg(sha)}"
        val response = httpClient.get(url) { ghHeaders(token) }
        if (response.status.value !in 200..299) {
            throw GitHubTokenException("git blob request failed (${response.status.value})")
        }
        val blob =
            runCatching {
                json.decodeFromString(GitBlobResponse.serializer(), response.bodyAsText())
            }.getOrElse {
                throw GitHubTokenException("git blob response was invalid")
            }
        if (
            blob.sha != sha ||
            blob.size < 0 ||
            (expectedSize != null && blob.size != expectedSize)
        ) {
            throw GitHubTokenException("git blob response was inconsistent")
        }
        if (blob.size > config.maxFileBytes) return BlobRead.TooLarge
        val decoded =
            when (blob.encoding.lowercase()) {
                "base64" -> {
                    val bytes =
                        runCatching {
                            Base64.getDecoder().decode(blob.content.filterNot(Char::isWhitespace))
                        }.getOrElse {
                            throw GitHubTokenException("git blob response contained invalid base64")
                        }
                    if (bytes.size > config.maxFileBytes) return BlobRead.TooLarge
                    runCatching {
                        Charsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes))
                            .toString()
                    }.getOrElse { return BlobRead.Binary }
                }

                "utf-8",
                "utf8",
                "" -> blob.content
                else -> throw GitHubTokenException("git blob response used an unsupported encoding")
            }
        val decodedBytes = decoded.toByteArray(Charsets.UTF_8)
        if (decodedBytes.size > config.maxFileBytes) {
            return BlobRead.TooLarge
        }
        if (decodedBytes.size.toLong() != blob.size) {
            throw GitHubTokenException("git blob response size did not match its content")
        }
        return if (looksBinary(decoded)) BlobRead.Binary else BlobRead.Text(decoded)
    }

    /** Resolves the default branch without guessing; remote/shape failures abort the snapshot. */
    private suspend fun fetchDefaultBranch(owner: String, repo: String, token: String): String {
        val url = "${config.apiBaseUrl}/repos/${seg(owner)}/${seg(repo)}"
        val response = httpClient.get(url) { ghHeaders(token) }
        if (response.status.value !in 200..299) {
            throw GitHubTokenException(
                "default branch request failed (${response.status.value})"
            )
        }
        return runCatching {
            json.decodeFromString(RepoInfoResponse.serializer(), response.bodyAsText())
                .defaultBranch
                ?.takeIf { it.isNotBlank() }
                ?: error("missing default branch")
        }.getOrElse {
            throw GitHubTokenException("default branch response was invalid")
        }
    }

    private fun HttpRequestBuilder.ghHeaders(token: String) {
        // Authorization is omitted for anonymous public-repo access (token blank); GitHub's
        // unauthenticated API serves public repos (at a lower rate limit), which is enough to index
        // a public repo pasted by URL without a GitHub App installation.
        if (token.isNotBlank()) header("Authorization", "Bearer $token")
        header("Accept", "application/vnd.github+json")
        header("X-GitHub-Api-Version", "2022-11-28")
    }

    private fun externalRef(owner: String, repo: String, ref: String): String =
        """{"provider":"github","owner":"${esc(owner)}",""" +
            """"repo":"${esc(repo)}","ref":"${esc(ref)}"}"""

    private fun esc(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun seg(s: String): String = s.encodeURLPath()

    /** Heuristic: a NUL character means the decoded blob is binary; skip it. */
    private val nul: Char = 0.toChar()

    private fun looksBinary(text: String): Boolean = text.indexOf(nul) >= 0

    private sealed interface BlobRead {
        data class Text(val value: String) : BlobRead

        object Binary : BlobRead

        object TooLarge : BlobRead
    }

    private companion object {
        const val GITHUB_SOURCE_TYPE = "GITHUB_REPO"
        const val CLAIM_INITIAL_POLL_DELAY_MS = 25L
        const val CLAIM_MAX_POLL_DELAY_MS = 500L
        val GIT_TREE_ENTRY_TYPES = setOf("blob", "tree", "commit")
        val CLAIM_LEASE: Duration = Duration.ofMinutes(4)
        val CLAIM_WAIT_TIMEOUT: Duration = Duration.ofMinutes(15)
    }
}
