/*
 * Copyright (c) 2026, Potaty
 */

package com.potaty.backend

import kotlin.test.Test
import kotlin.test.assertTrue

class SchemaContractTest {
    @Test
    fun postgresRepairMigrationMatchesExtractionAndTenantOwnershipContract() {
        val sql = checkNotNull(
            javaClass.getResource("/db/migration/V3__align_identity_and_extraction_schema.sql")
        )
            .readText()
            .lowercase()

        listOf(
            "rename column canonical_key to entity_key",
            "rename column entity_type to type",
            "add column canonical_name text",
            "add column evidence_chunk_ids jsonb",
            "fk_sources_workspace_project",
            "fk_source_versions_workspace_source",
            "fk_diagrams_workspace_project",
            "fk_jobs_workspace_project"
        ).forEach { required ->
            assertTrue(required in sql, "schema migration must contain '$required'")
        }
    }

    @Test
    fun sourceIngestionMigrationBindsKeysToRequestHashes() {
        val sql = checkNotNull(
            javaClass.getResource("/db/migration/V9__bind_source_ingestion_requests.sql")
        )
            .readText()
            .lowercase()

        listOf(
            "ingestion_request_hash",
            "ck_sources_ingestion_request_hash",
            "ingestion_request_hash is not null",
            "sha256:[0-9a-f]{64}"
        ).forEach { required ->
            assertTrue(required in sql, "source ingestion migration must contain '$required'")
        }
    }

    @Test
    fun sourceIngestionClaimMigrationFencesOwnershipAndTenantResults() {
        val sql = checkNotNull(
            javaClass.getResource("/db/migration/V10__fence_source_ingestion.sql")
        )
            .readText()
            .lowercase()

        listOf(
            "create table source_ingestion_claims",
            "uq_source_ingestion_claims_workspace_key",
            "ck_source_ingestion_claims_key_hash",
            "ck_source_ingestion_claims_state",
            "processing_token",
            "lease_expires_at",
            "fk_source_ingestion_claims_workspace_project",
            "fk_source_ingestion_claims_workspace_source",
            "fk_source_ingestion_claims_workspace_version",
            "idx_source_ingestion_claims_recovery"
        ).forEach { required ->
            assertTrue(required in sql, "source claim migration must contain '$required'")
        }
    }
}
