/*
 * Copyright (c) 2026, Potaty
 *
 * Shared test config: an isolated in-memory H2 database per test (unique name) with dev auth on.
 */

package com.potaty.backend

import com.potaty.backend.config.AppConfig
import com.potaty.backend.config.EnvConfig
import java.util.UUID

const val TEST_TOKEN = "test-token-12345"

fun testConfig(
    dbName: String = "test_" + UUID.randomUUID().toString().replace("-", "")
): AppConfig = AppConfig.fromEnv(
    EnvConfig.of(
        mapOf(
            "POTATY_DB_MODE" to "h2",
            "POTATY_DB_URL" to "jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
            "POTATY_ENV" to "test",
            "POTATY_DEV_AUTH" to "true",
            "POTATY_DEV_TOKEN" to TEST_TOKEN
        )
    )
)
