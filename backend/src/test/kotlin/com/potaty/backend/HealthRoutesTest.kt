/*
 * Copyright (c) 2026, Potaty
 */

package com.potaty.backend

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class HealthRoutesTest {

    @Test
    fun livenessAndReadinessAreSeparateAndDoNotExposeDatabaseMode() = testApplication {
        val config = testConfig()
        application { module(config, AppGraph.create(config)) }

        val legacy = client.get("/health")
        val live = client.get("/health/live")
        val ready = client.get("/health/ready")

        assertEquals(HttpStatusCode.OK, legacy.status)
        assertEquals(HttpStatusCode.OK, live.status)
        assertEquals(HttpStatusCode.OK, ready.status)
        assertEquals("{\"status\":\"ready\"}", ready.bodyAsText())
        assertFalse(legacy.bodyAsText().contains("dbMode"))
    }

    @Test
    fun readinessTurnsFalseAfterDependenciesClose() {
        val graph = AppGraph.create(testConfig())
        assertEquals(true, graph.isReady())

        graph.stop()

        assertEquals(false, graph.isReady())
    }
}
