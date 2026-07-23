/*
 * Copyright (c) 2026, Potaty
 *
 * Database bootstrap. Two modes (plan section 8 + 22.4 "P0 single backend"):
 *   - postgres: HikariCP + Flyway-managed schema (authoritative pgvector/jsonb DDL) + Exposed.
 *   - h2:       embedded in-memory H2 (PostgreSQL compatibility mode); the Exposed table
 *               objects are created with SchemaUtils.create (the pg DDL is Postgres-only).
 *               This makes the whole service runnable + testable without external infra.
 *
 * The same tenant-scoped Exposed repositories run unchanged on both engines.
 */

package com.potaty.backend.persistence

import com.potaty.backend.config.DatabaseConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.Database as ExposedDatabase

class Database private constructor(
    val dataSource: DataSource,
    val exposed: ExposedDatabase
) {
    /** A bounded dependency probe used by readiness checks; never throws to the HTTP layer. */
    fun isReady(): Boolean = runCatching {
        dataSource.connection.use { connection -> connection.isValid(2) }
    }.getOrDefault(false)

    fun close() {
        (dataSource as? AutoCloseable)?.close()
    }

    companion object {
        /** Exposed tables created directly on H2 (no Flyway). Mirrors db/migration for prod. */
        private val ALL_TABLES = arrayOf(
            WorkspacesTable,
            UsersTable,
            WorkspaceMembersTable,
            ProjectsTable,
            GitHubInstallationsTable,
            GitHubConnectStatesTable,
            SourcesTable,
            SourceVersionsTable,
            SourceChunksTable,
            SourceIngestionClaimsTable,
            DiagramsTable,
            DiagramVersionsTable,
            RenderingsTable,
            JobsTable,
            JobEventsTable,
            LlmCredentialsTable,
            UsageEventsTable,
            CostReservationsTable,
            AuditEventsTable,
            ExtractedEntitiesTable,
            ExtractedRelationsTable
        )

        fun connect(config: DatabaseConfig): Database {
            val hikari = HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = config.jdbcUrl
                    username = config.username
                    password = config.password
                    maximumPoolSize = config.maxPoolSize
                    isAutoCommit = false
                    if (config.isH2) {
                        driverClassName = "org.h2.Driver"
                    } else {
                        driverClassName = "org.postgresql.Driver"
                        // Postgres serialization conflicts are retried at the job layer (RetryPolicy).
                        transactionIsolation = "TRANSACTION_REPEATABLE_READ"
                    }
                }
            )

            if (!config.isH2 && config.runFlywayMigrations) {
                Flyway.configure()
                    .dataSource(hikari)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate()
            }

            val exposed = ExposedDatabase.connect(hikari)

            if (config.isH2) {
                transaction(exposed) {
                    SchemaUtils.create(*ALL_TABLES)
                }
            }

            return Database(hikari, exposed)
        }
    }
}
