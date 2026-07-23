/*
 * Copyright (c) 2026, Potaty
 *
 * GitHub REST request/response DTOs for the PR-publishing path (WS11; plan sections 18.6 + 4.2
 * "PR publish"). Kept in this NEW file (not GitHubModels.kt) so the existing read-only indexing
 * models stay untouched. Only the fields Potaty actually consumes are modelled; the parse site uses
 * a Json { ignoreUnknownKeys = true } so GitHub's richer payloads stay forward-compatible.
 *
 * These cover the REST sequence that opens a pull request WITHOUT merging:
 *   - GET  /repos/{o}/{r}/git/ref/heads/{base}          -> [GitRefResponse]   (base head SHA)
 *   - POST /repos/{o}/{r}/git/refs                       <- [CreateRefRequest] (new branch)
 *   - GET  /repos/{o}/{r}/contents/{path}?ref={branch}   -> [ContentItem]      (existing file sha)
 *   - PUT  /repos/{o}/{r}/contents/{path}                <- [PutContentRequest](create/update blob)
 *   - POST /repos/{o}/{r}/pulls                           <- [CreatePullRequest] -> [PullRequestResponse]
 *
 * NEVER auto-merge: there is intentionally no merge DTO here.
 */

package com.potaty.backend.github

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** GET /repos/{o}/{r}/git/ref/heads/{base} -> the ref object pointing at the base branch head. */
@Serializable
data class GitRefResponse(
    @SerialName("ref")
    val ref: String = "",
    @SerialName("object")
    val obj: GitRefObject = GitRefObject()
)

@Serializable
data class GitRefObject(
    val sha: String = "",
    val type: String = ""
)

/** POST /repos/{o}/{r}/git/refs: create a new branch ref `refs/heads/{branch}` at [sha]. */
@Serializable
data class CreateRefRequest(
    val ref: String,
    val sha: String
)

/**
 * One item from GET /repos/{o}/{r}/contents/{path}. We only need `sha`, which the Contents API
 * requires when *updating* an existing file (omitted when creating). A 404 means the file does not
 * yet exist on the branch and the PUT should create it.
 */
@Serializable
data class ContentItem(
    val sha: String = "",
    val path: String = "",
    val type: String = ""
)

/**
 * PUT /repos/{o}/{r}/contents/{path}: create or update a single file as a commit on [branch].
 * [content] is base64-encoded file bytes. [sha] is the blob sha of the file being replaced — null
 * on create, present on update (GitHub rejects an update that omits it).
 */
@Serializable
data class PutContentRequest(
    val message: String,
    val content: String,
    val branch: String,
    val sha: String? = null
)

/** Response of PUT /repos/{o}/{r}/contents/{path}: the resulting content + commit. Minimal. */
@Serializable
data class PutContentResponse(
    val content: ContentItem? = null,
    val commit: CommitRef? = null
)

@Serializable
data class CommitRef(
    val sha: String = ""
)

/** POST /repos/{o}/{r}/pulls: open a pull request. `head` is the new branch, `base` the target. */
@Serializable
data class CreatePullRequest(
    val title: String,
    val head: String,
    val base: String,
    val body: String,
    /** Always false — Potaty never opens a draft-then-automerge flow; humans review and merge. */
    val draft: Boolean = false
)

/** Response of POST /repos/{o}/{r}/pulls: the opened PR. */
@Serializable
data class PullRequestResponse(
    val number: Int = 0,
    @SerialName("html_url")
    val htmlUrl: String = "",
    val state: String = "",
    val merged: Boolean = false
)
