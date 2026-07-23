/*
 * Copyright (c) 2026, Potaty
 *
 * GitHub PR publishing (WS11; plan sections 18.6 "AI-generated disclosure + review gate" and 4.2
 * "PR publish"). Given an installation token (minted by [GitHubAppService]) and owner/repo, it
 * opens a pull request that adds/updates one or more files (typically a `docs/diagram.md` carrying the
 * diagram as Mermaid). The REST sequence is:
 *
 *   1. GET  /repos/{o}/{r}/git/ref/heads/{base}   -> the base branch head SHA.
 *   2. POST /repos/{o}/{r}/git/refs               -> create a NEW branch ref at that SHA.
 *   3. For each file:
 *        GET /repos/{o}/{r}/contents/{path}?ref={branch}  -> existing blob sha (404 = create).
 *        PUT /repos/{o}/{r}/contents/{path}               -> commit the file on the new branch.
 *   4. POST /repos/{o}/{r}/pulls                   -> open the PR (body = disclosure + evidence
 *      summary + review checklist).
 *
 * It NEVER merges: there is no merge call anywhere in this class, and the PR is opened in the
 * default (non-draft, un-merged) state for a human to review. All GitHub failures map to
 * [GitHubTokenException] (reused from GitHubAppService so the route's existing catch handles them).
 *
 * Pure outbound HTTP via the Ktor client; deterministic and MockEngine-testable (no network).
 */

@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.potaty.backend.github

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLPath
import java.util.Base64
import kotlinx.serialization.json.Json

/** A single file to publish in the PR: repository [path] and raw UTF-8 [content]. */
data class PublishFile(
    val path: String,
    val content: String
)

/**
 * The evidence/validation figures embedded in the PR body so a reviewer can judge grounding at a
 * glance (plan 18.6, 21.3). All optional with safe defaults so callers that lack a metric can omit
 * it; the route fills these from the stored EvidenceCoverage + ValidationReport.
 */
data class PublishEvidence(
    val nodeCount: Int = 0,
    val edgeCount: Int = 0,
    val nodeCoverage: Double = 0.0,
    val edgeCoverage: Double = 0.0,
    val groundedEdgeRatio: Double = 0.0,
    val inferredEdgeCount: Int = 0,
    val unsupportedCriticalClaims: Int = 0,
    val validationValid: Boolean = true,
    val validationErrorCount: Int = 0,
    val validationWarningCount: Int = 0,
    val meetsPublishThreshold: Boolean = false,
    /**
     * e.g. "octo/demo@main" — the sources this diagram was grounded in (free-form, for the body).
     */
    val sourceSummary: String? = null
)

/** What the caller gets back: the PR's number + html url + state (for the API response). */
data class PublishResult(
    val number: Int,
    val htmlUrl: String,
    val branch: String,
    val state: String
)

class GitHubPublisher(
    private val config: GitHubConfig,
    private val httpClient: HttpClient
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    /**
     * Opens a pull request on [owner]/[repo] adding/updating [files] on a new [branch] cut from
     * [baseBranch]. [title] is the PR title; [diagramSummary] a one-line human summary; [evidence]
     * feeds the validation table. Returns the opened PR. Throws [GitHubTokenException] on any
     * non-2xx GitHub response. Does NOT merge.
     */
    suspend fun publishPullRequest(
        installationToken: String,
        owner: String,
        repo: String,
        baseBranch: String,
        branch: String,
        title: String,
        commitMessage: String,
        diagramSummary: String,
        files: List<PublishFile>,
        evidence: PublishEvidence
    ): PublishResult {
        require(owner.isNotBlank() && repo.isNotBlank()) { "owner and repo are required" }
        require(baseBranch.isNotBlank() && branch.isNotBlank()) {
            "baseBranch and branch are required"
        }
        require(files.isNotEmpty()) { "at least one file is required to open a PR" }

        // 1. Base branch head SHA.
        val baseSha = baseHeadSha(installationToken, owner, repo, baseBranch)

        // 2. New branch ref at the base head.
        createBranch(installationToken, owner, repo, branch, baseSha)

        // 3. Commit each file onto the new branch (create or update).
        for (file in files) {
            commitFile(installationToken, owner, repo, branch, file, commitMessage)
        }

        // 4. Open the PR (never merged, never draft-auto-merge).
        val body = buildPrBody(diagramSummary, evidence)
        return openPull(installationToken, owner, repo, baseBranch, branch, title, body)
    }

    /**
     * Public for tests / reuse: the exact PR body Potaty posts (disclosure + evidence + checklist).
     */
    fun buildPrBody(diagramSummary: String, evidence: PublishEvidence): String {
        val sb = StringBuilder()

        // AI-generated disclosure (plan 18.6) — must be unmistakable at the top of the PR.
        sb.append("> **AI-generated diagram — review before merging.** ")
        sb.append("This pull request was produced automatically by Potaty from the cited source ")
        sb.append("material. It may contain inaccuracies or inferred relationships. Do not merge ")
        sb.append("until a human has reviewed it against the evidence below.\n\n")

        if (diagramSummary.isNotBlank()) {
            sb.append(diagramSummary.trim()).append("\n\n")
        }

        // Evidence & validation summary (plan 18.6 / 21.3 thresholds).
        sb.append("## Evidence & validation\n\n")
        sb.append("| Metric | Value |\n")
        sb.append("| --- | --- |\n")
        sb.append("| Nodes | ").append(evidence.nodeCount).append(" |\n")
        sb.append("| Edges | ").append(evidence.edgeCount).append(" |\n")
        sb.append("| Node evidence coverage | ").append(pct(evidence.nodeCoverage)).append(" |\n")
        sb.append("| Edge evidence coverage | ").append(pct(evidence.edgeCoverage)).append(" |\n")
        sb.append("| Grounded edge ratio | ").append(pct(evidence.groundedEdgeRatio)).append(" |\n")
        sb.append("| Inferred edges | ").append(evidence.inferredEdgeCount).append(" |\n")
        sb.append("| Unsupported critical claims | ")
            .append(evidence.unsupportedCriticalClaims)
            .append(" |\n")
        sb.append("| Structurally valid | ").append(yesNo(evidence.validationValid)).append(" |\n")
        sb.append("| Validation errors / warnings | ")
            .append(evidence.validationErrorCount)
            .append(" / ")
            .append(evidence.validationWarningCount)
            .append(" |\n")
        sb.append("| Meets publish threshold | ")
            .append(yesNo(evidence.meetsPublishThreshold))
            .append(" |\n")
        evidence.sourceSummary
            ?.takeIf { it.isNotBlank() }
            ?.let {
                sb.append("| Sources | `").append(it).append("` |\n")
            }
        sb.append('\n')

        // Review checklist (plan 18.6): the human reviewer must tick these before merging.
        sb.append("## Review checklist\n\n")
        sb.append("- [ ] Every node corresponds to a real component in the source material.\n")
        sb.append("- [ ] Every edge reflects an actual relationship (inferred edges are dotted).\n")
        sb.append("- [ ] No unsupported critical claims remain.\n")
        sb.append("- [ ] Labels and direction match the cited evidence.\n")
        sb.append("- [ ] This diagram is safe to publish in the repository.\n\n")

        sb.append(
            "_Generated by Potaty. Merging is a human decision — Potaty never auto-merges._\n"
        )
        return sb.toString()
    }

    private suspend fun baseHeadSha(
        token: String,
        owner: String,
        repo: String,
        base: String
    ): String {
        val url = "${config.apiBaseUrl}/repos/${seg(owner)}/${seg(repo)}/git/ref/heads/${seg(base)}"
        val response = httpClient.get(url) { ghHeaders(token) }
        requireOk(response, "resolve base branch '$base'")
        val ref = json.decodeFromString(GitRefResponse.serializer(), response.bodyAsText())
        if (ref.obj.sha.isBlank()) {
            throw GitHubTokenException("base branch '$base' has no head sha")
        }
        return ref.obj.sha
    }

    private suspend fun createBranch(
        token: String,
        owner: String,
        repo: String,
        branch: String,
        sha: String
    ) {
        val url = "${config.apiBaseUrl}/repos/${seg(owner)}/${seg(repo)}/git/refs"
        val response =
            httpClient.post(url) {
                ghHeaders(token)
                contentType(ContentType.Application.Json)
                setBody(
                    json.encodeToString(
                        CreateRefRequest.serializer(),
                        CreateRefRequest("refs/heads/$branch", sha)
                    )
                )
            }
        requireOk(response, "create branch '$branch'")
    }

    private suspend fun commitFile(
        token: String,
        owner: String,
        repo: String,
        branch: String,
        file: PublishFile,
        commitMessage: String
    ) {
        val existingSha = existingFileSha(token, owner, repo, branch, file.path)
        val encoded = Base64.getEncoder().encodeToString(file.content.toByteArray(Charsets.UTF_8))
        val url =
            "${config.apiBaseUrl}/repos/${seg(owner)}/${seg(repo)}/contents/${path(file.path)}"
        val request =
            PutContentRequest(
                message = commitMessage,
                content = encoded,
                branch = branch,
                sha = existingSha
            )
        val response =
            httpClient.put(url) {
                ghHeaders(token)
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(PutContentRequest.serializer(), request))
            }
        requireOk(response, "commit '${file.path}'")
    }

    /**
     * Returns the blob sha of [path] on [branch], or null when the file does not exist yet (404).
     */
    private suspend fun existingFileSha(
        token: String,
        owner: String,
        repo: String,
        branch: String,
        path: String
    ): String? {
        val url =
            "${config.apiBaseUrl}/repos/${seg(owner)}/${seg(repo)}/contents/${path(path)}?ref=${seg(
                branch
            )}"
        val response = httpClient.get(url) { ghHeaders(token) }
        if (response.status.value == 404) return null
        requireOk(response, "look up existing '$path'")
        return runCatching {
            json.decodeFromString(ContentItem.serializer(), response.bodyAsText()).sha.takeIf {
                it.isNotBlank()
            }
        }
            .getOrNull()
    }

    private suspend fun openPull(
        token: String,
        owner: String,
        repo: String,
        base: String,
        head: String,
        title: String,
        body: String
    ): PublishResult {
        val url = "${config.apiBaseUrl}/repos/${seg(owner)}/${seg(repo)}/pulls"
        val request =
            CreatePullRequest(title = title, head = head, base = base, body = body, draft = false)
        val response =
            httpClient.post(url) {
                ghHeaders(token)
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(CreatePullRequest.serializer(), request))
            }
        requireOk(response, "open pull request")
        val pr = json.decodeFromString(PullRequestResponse.serializer(), response.bodyAsText())
        if (pr.htmlUrl.isBlank()) {
            throw GitHubTokenException("pull request response had no html_url")
        }
        return PublishResult(
            number = pr.number,
            htmlUrl = pr.htmlUrl,
            branch = head,
            state = pr.state
        )
    }

    private suspend fun requireOk(response: HttpResponse, action: String) {
        if (response.status.value !in 200..299) {
            throw GitHubTokenException("failed to $action (${response.status.value})")
        }
    }

    private fun HttpRequestBuilder.ghHeaders(token: String) {
        header("Authorization", "Bearer $token")
        header("Accept", "application/vnd.github+json")
        header("X-GitHub-Api-Version", "2022-11-28")
    }

    private fun seg(s: String): String = s.encodeURLPath()

    /** Encodes a repo file path, preserving '/' segment separators. */
    private fun path(p: String): String = p.split("/").joinToString("/") { it.encodeURLPath() }

    private fun yesNo(b: Boolean): String = if (b) "yes" else "no"

    /**
     * Formats a 0..1 ratio as a percentage with one decimal place using integer arithmetic (matches
     * MarkdownExporter.pct so the PR body and the doc agree). Half-up rounding.
     */
    private fun pct(value: Double): String {
        val scaled = value * 1000.0
        val rounded = (scaled + (if (scaled >= 0) 0.5 else -0.5)).toInt()
        val whole = rounded / 10
        val frac = if (rounded % 10 < 0) -(rounded % 10) else rounded % 10
        return "$whole.$frac%"
    }
}
