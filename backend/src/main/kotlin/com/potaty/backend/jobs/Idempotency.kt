/*
 * Copyright (c) 2026, Potaty
 *
 * Deterministic idempotency-key derivation (plan 11.3). Identical logical jobs collapse to
 * one row via the (workspace_id, idempotency_key) unique constraint.
 */

package com.potaty.backend.jobs

import java.security.MessageDigest

object Idempotency {

    /**
     * Suggested key (plan 11.3):
     *   workspace_id + project_id + source_snapshot_hash + diagram_type +
     *   objective_hash + scope_hash + renderer_version + prompt_version
     */
    fun diagramJobKey(
        workspaceId: String,
        projectId: String?,
        sourceSnapshotHash: String,
        diagramType: String,
        objective: String?,
        scopeCanonical: String,
        rendererVersion: String,
        promptVersion: String
    ): String {
        val material = listOf(
            workspaceId,
            projectId.orEmpty(),
            sourceSnapshotHash,
            diagramType,
            sha256(objective.orEmpty()),
            sha256(scopeCanonical),
            rendererVersion,
            promptVersion
        ).joinToString("|")
        return "djob_" + sha256(material)
    }

    fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
