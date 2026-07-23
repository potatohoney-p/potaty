/*
 * Copyright (c) 2026, Potaty
 *
 * Typed client over the Potaty /api/v1 contract for the browser workbench: create a source,
 * enqueue a diagram job, poll it, fetch the rendered version. Pure logic over an injected
 * [HttpTransport]; bearer token attached when present.
 */

package com.potaty.workbench

class PotatyApiClient(
    private val baseUrl: String,
    private val transport: HttpTransport,
    private val token: String? = null
) {
    private fun headers(jsonBody: Boolean): Map<String, String> = buildMap {
        token?.let { put("Authorization", "Bearer $it") }
        if (jsonBody) put("Content-Type", "application/json")
    }

    suspend fun createSource(
        projectId: String,
        req: CreateSourceRequest,
        idempotencyKey: String
    ): CreateSourceResponse {
        val body = WorkbenchJson.encodeToString(CreateSourceRequest.serializer(), req)
        val resp = transport.send(
            "POST",
            "$baseUrl/api/v1/projects/$projectId/sources",
            headers(true) + ("Idempotency-Key" to idempotencyKey),
            body
        )
        require(resp.isSuccess) { "createSource failed: ${resp.status} ${resp.body}" }
        return WorkbenchJson.decodeFromString(CreateSourceResponse.serializer(), resp.body)
    }

    suspend fun indexGitHubUrl(
        projectId: String,
        req: GitHubIndexUrlRequest,
        idempotencyKey: String
    ): GitHubIndexResponse {
        val body = WorkbenchJson.encodeToString(GitHubIndexUrlRequest.serializer(), req)
        val resp = transport.send(
            "POST",
            "$baseUrl/api/v1/projects/$projectId/github/index-url",
            headers(true) + ("Idempotency-Key" to idempotencyKey),
            body
        )
        require(resp.isSuccess) { "indexGitHubUrl failed: ${resp.status} ${resp.body}" }
        return WorkbenchJson.decodeFromString(GitHubIndexResponse.serializer(), resp.body)
    }

    suspend fun createDiagramJob(
        projectId: String,
        req: DiagramJobRequest,
        idempotencyKey: String
    ): DiagramJobResponse {
        val body = WorkbenchJson.encodeToString(DiagramJobRequest.serializer(), req)
        val h = headers(true) + ("Idempotency-Key" to idempotencyKey)
        val resp = transport.send(
            "POST",
            "$baseUrl/api/v1/projects/$projectId/diagram-jobs",
            h,
            body
        )
        require(resp.isSuccess) { "createDiagramJob failed: ${resp.status} ${resp.body}" }
        return WorkbenchJson.decodeFromString(DiagramJobResponse.serializer(), resp.body)
    }

    suspend fun getJob(jobId: String): JobStatusResponse {
        val resp = transport.send("GET", "$baseUrl/api/v1/jobs/$jobId", headers(false), null)
        require(resp.isSuccess) { "getJob failed: ${resp.status} ${resp.body}" }
        return WorkbenchJson.decodeFromString(JobStatusResponse.serializer(), resp.body)
    }

    suspend fun cancelJob(jobId: String): CancelJobResponse {
        val resp = transport.send(
            "POST",
            "$baseUrl/api/v1/jobs/$jobId/cancel",
            headers(false),
            null
        )
        require(resp.isSuccess) { "cancelJob failed: ${resp.status} ${resp.body}" }
        return WorkbenchJson.decodeFromString(CancelJobResponse.serializer(), resp.body)
    }

    suspend fun getDiagramVersion(diagramId: String, versionId: String): DiagramVersionResponse {
        val resp = transport.send(
            "GET",
            "$baseUrl/api/v1/diagrams/$diagramId/versions/$versionId",
            headers(false),
            null
        )
        require(resp.isSuccess) { "getDiagramVersion failed: ${resp.status} ${resp.body}" }
        return WorkbenchJson.decodeFromString(DiagramVersionResponse.serializer(), resp.body)
    }
}
