/*
 * Copyright (c) 2026, Potaty
 *
 * GET /metrics — UNAUTHENTICATED Prometheus text-exposition endpoint (plan 22 observability).
 * Mounted OUTSIDE /api/v1 (like /health) so a scraper does not need a tenant token. The body is
 * the in-process [Metrics] registry rendered as Prometheus v0.0.4 text; the content type carries
 * the conventional `version=0.0.4` parameter so Prometheus parses it without guessing.
 *
 * Exposing process-wide counters/gauges with no per-workspace labels means there is no tenant
 * data here to protect; the endpoint is intentionally public for the local/P0 deploy.
 */

package com.potaty.backend.api

import com.potaty.backend.observability.Metrics
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** Prometheus 0.0.4 exposition content type: text/plain; version=0.0.4; charset=utf-8. */
private val PROMETHEUS_CONTENT_TYPE: ContentType =
    ContentType.Text.Plain.withParameter("version", "0.0.4").withParameter("charset", "utf-8")

fun Route.metricsRoutes(metrics: Metrics) {
    get("/metrics") {
        call.respondText(
            text = metrics.render(),
            contentType = PROMETHEUS_CONTENT_TYPE,
            status = HttpStatusCode.OK
        )
    }
}
