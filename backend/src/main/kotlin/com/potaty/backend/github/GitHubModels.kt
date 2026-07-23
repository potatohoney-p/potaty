/*
 * Copyright (c) 2026, Potaty
 *
 * GitHub REST API DTOs (plan sections 4.2 P1 input #GitHub repo, 18 integration test
 * "GitHub mock -> source sync -> code index"). Only the fields Potaty actually consumes are
 * modelled; @Serializable with ignoreUnknownKeys at the parse site keeps these minimal and
 * forward-compatible with GitHub's richer payloads.
 *
 * These cover three GitHub surfaces:
 *   - GET /repos/{owner}/{repo}/git/trees/{ref}?recursive=1   -> [GitTreeResponse]
 *   - GET /repos/{owner}/{repo}/git/blobs/{sha}               -> [GitBlobResponse]
 *   - POST /app/installations/{id}/access_tokens             -> [InstallationTokenResponse]
 *   - the `push` webhook body                                 -> [PushWebhookPayload]
 */

package com.potaty.backend.github

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A recursive git tree listing. `truncated` true means GitHub capped the listing (plan: huge repos). */
@Serializable
data class GitTreeResponse(
    val sha: String,
    @SerialName("tree")
    val tree: List<GitTreeEntry>,
    val truncated: Boolean
)

/**
 * One entry in a git tree. `type` is "blob" (file) or "tree" (directory); only blobs are indexed.
 * `size` is the byte length (absent for trees). `sha` addresses the blob for a follow-up fetch.
 */
@Serializable
data class GitTreeEntry(
    val path: String,
    val mode: String,
    val type: String,
    val sha: String,
    val size: Long? = null
)

/**
 * A blob. Content is base64 when `encoding == "base64"` (GitHub's default) and may be empty when
 * GitHub returns the blob unencoded. Newlines are interspersed in base64 and must be stripped.
 */
@Serializable
data class GitBlobResponse(
    val sha: String,
    val size: Long,
    val content: String,
    val encoding: String
)

/** Minimal GET /repos/{owner}/{repo} response — used to resolve a repo's default branch by URL. */
@Serializable
data class RepoInfoResponse(
    @SerialName("default_branch")
    val defaultBranch: String? = null,
    @SerialName("full_name")
    val fullName: String = ""
)

/** Response of POST /app/installations/{id}/access_tokens: a short-lived installation token. */
@Serializable
data class InstallationTokenResponse(
    val token: String = "",
    @SerialName("expires_at")
    val expiresAt: String? = null
)

/** Minimal `push` webhook payload — enough to know which repo/ref changed and trigger a re-index. */
@Serializable
data class PushWebhookPayload(
    val ref: String? = null,
    val after: String? = null,
    val repository: WebhookRepository? = null,
    val installation: WebhookInstallation? = null
)

@Serializable
data class WebhookRepository(
    val name: String = "",
    @SerialName("full_name")
    val fullName: String = "",
    @SerialName("default_branch")
    val defaultBranch: String? = null,
    val owner: WebhookRepositoryOwner? = null
)

@Serializable
data class WebhookRepositoryOwner(
    val login: String = ""
)

@Serializable
data class WebhookInstallation(
    val id: Long = 0
)
