/*
 * Copyright (c) 2026, Potaty
 *
 * Ktor entrypoint and application module. Wires ContentNegotiation(JSON), CORS, StatusPages
 * (ValidationException -> 422; Forbidden -> 403; NotAuthenticated -> 401), CallLogging, and
 * mounts the API under /api/v1 (plan section 10).
 */

@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.potaty.backend

import com.potaty.backend.api.ApiError
import com.potaty.backend.api.adminRoutes
import com.potaty.backend.api.diagramRoutes
import com.potaty.backend.api.jobRoutes
import com.potaty.backend.api.metricsRoutes
import com.potaty.backend.api.sourceRoutes
import com.potaty.backend.auth.ForbiddenException
import com.potaty.backend.auth.NotAuthenticatedException
import com.potaty.backend.auth.installSessionAuth
import com.potaty.backend.config.AppConfig
import com.potaty.backend.cost.CostReservationConflictException
import com.potaty.backend.cost.CostReservationStateConflictException
import com.potaty.backend.cost.QuotaExceededException
import com.potaty.backend.github.GitHubIndexInProgressException
import com.potaty.backend.github.gitHubPublishRoutes
import com.potaty.backend.github.githubConnectionRoutes
import com.potaty.backend.github.githubRoutes
import com.potaty.backend.ir.ValidationException
import com.potaty.backend.persistence.TenantIntegrityException
import com.potaty.backend.persistence.repositories.IdempotencyConflictException
import com.potaty.backend.persistence.repositories.IngestionClaimLostException
import com.potaty.backend.security.PromptInjectionException
import com.potaty.backend.transcription.transcriptionRoutes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

private val applicationLog = LoggerFactory.getLogger("com.potaty.backend.Application")

fun main() {
    val config = AppConfig.fromEnv()
    embeddedServer(
        Netty,
        port = config.server.port,
        host = config.server.host
    ) {
        module(config)
    }
        .start(wait = true)
}

/**
 * The Ktor application module. Pass an explicit [AppConfig] (and optionally a pre-built [AppGraph])
 * in tests so the graph's wired collaborators can be inspected and closed.
 */
fun Application.module(
    config: AppConfig = AppConfig.fromEnv(),
    graph: AppGraph = AppGraph.create(config)
) {
    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
                explicitNulls = false
            }
        )
    }

    install(CORS) {
        config.cors.allowedOrigins.forEach { origin ->
            // Preserve the configured scheme; an HTTPS allow-list entry must not also admit HTTP.
            val scheme = origin.substringBefore("://")
            val authority = origin.substringAfter("://")
            allowHost(authority, schemes = listOf(scheme))
        }
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Options)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowHeader("Idempotency-Key")
        allowCredentials = true
    }

    install(CallLogging) {
        level = Level.INFO
    }

    install(StatusPages) {
        // IR validation failures (plan: ValidationReport -> 422)
        exception<ValidationException> { call, cause ->
            call.respond(
                HttpStatusCode.UnprocessableEntity,
                ApiError(
                    error = "validation_failed",
                    message = cause.message ?: "IR validation failed",
                    details = null
                )
            )
        }
        exception<NotAuthenticatedException> { call, cause ->
            call.respond(
                HttpStatusCode.Unauthorized,
                ApiError("unauthenticated", cause.message ?: "unauthenticated")
            )
        }
        exception<ForbiddenException> { call, cause ->
            call.respond(
                HttpStatusCode.Forbidden,
                ApiError("forbidden", cause.message ?: "forbidden")
            )
        }
        exception<TenantIntegrityException> { call, cause ->
            call.respond(
                HttpStatusCode.NotFound,
                ApiError("not_found", cause.message ?: "resource not found")
            )
        }
        exception<PromptInjectionException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ApiError("prompt_injection", cause.message ?: "blocked")
            )
        }
        exception<QuotaExceededException> { call, cause ->
            call.respond(
                HttpStatusCode.PaymentRequired,
                ApiError("quota_exceeded", cause.message ?: "workspace monthly cost cap exceeded")
            )
        }
        exception<IdempotencyConflictException> { call, cause ->
            call.respond(
                HttpStatusCode.Conflict,
                ApiError("idempotency_conflict", cause.message ?: "idempotency key conflict")
            )
        }
        exception<GitHubIndexInProgressException> { call, cause ->
            call.respond(
                HttpStatusCode.Conflict,
                ApiError("ingestion_in_progress", cause.message ?: "source ingestion in progress")
            )
        }
        exception<IngestionClaimLostException> { call, cause ->
            call.respond(
                HttpStatusCode.Conflict,
                ApiError(
                    "ingestion_in_progress",
                    cause.message ?: "source ingestion ownership changed"
                )
            )
        }
        exception<CostReservationConflictException> { call, cause ->
            call.respond(
                HttpStatusCode.Conflict,
                ApiError("idempotency_conflict", cause.message ?: "idempotency key conflict")
            )
        }
        exception<CostReservationStateConflictException> { call, cause ->
            call.respond(
                HttpStatusCode.Conflict,
                ApiError("external_spend_conflict", cause.message ?: "external attempt conflict")
            )
        }
        // Parser exception messages can include fragments of the untrusted body. Keep the client
        // response stable and source-free; the request contract tells callers what to correct.
        exception<BadRequestException> { call, _ ->
            call.respond(
                HttpStatusCode.BadRequest,
                ApiError("bad_request", "Invalid request body; expected valid JSON for this route")
            )
        }
        exception<Throwable> { call, cause ->
            val requestId = UUID.randomUUID().toString()
            applicationLog.error(
                "Unhandled request failure requestId={} method={} path={} exceptionType={}",
                requestId,
                call.request.httpMethod.value,
                call.request.path(),
                cause::class.qualifiedName ?: cause::class.simpleName ?: "Throwable",
                sanitizeThrowableForLogging(cause)
            )
            call.respond(
                HttpStatusCode.InternalServerError,
                ApiError(
                    error = "internal_error",
                    message = "An unexpected server error occurred",
                    details = buildJsonObject { put("requestId", requestId) }
                )
            )
        }
    }

    // Resolve the per-call TenantContext from the bearer token (plan section 5 / 20.5).
    installSessionAuth(graph.sessionStore)

    // Background worker pool: claims queued diagram jobs and runs the pipeline.
    graph.start()
    environment.monitor.subscribe(ApplicationStopping) { graph.stop() }

    routing {
        // Liveness proves the process can serve requests. Readiness additionally verifies the
        // database dependency and returns 503 while the instance should be removed from traffic.
        get("/health") {
            call.respond(buildJsonObject { put("status", "ok") })
        }
        get("/health/live") {
            call.respond(buildJsonObject { put("status", "ok") })
        }
        get("/health/ready") {
            val ready = graph.isReady()
            call.respond(
                if (ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
                buildJsonObject { put("status", if (ready) "ready" else "unavailable") }
            )
        }
        metricsRoutes(graph.metrics) // GET /metrics (Prometheus text, unauthenticated)
        route("/api/v1") {
            sourceRoutes(graph)
            diagramRoutes(graph)
            jobRoutes(graph)
            githubRoutes(graph)
            githubConnectionRoutes(graph.gitHubConnectionService)
            gitHubPublishRoutes(graph, graph.gitHubPublisher)
            transcriptionRoutes(
                service = graph.audioTranscriptionService,
                credentialResolver = graph.transcriptionCredentialResolver,
                ingestor = graph.transcriptIngestor,
                completer = graph.transcriptionCompleter,
                projectExists = { workspaceId, projectId ->
                    graph.identities.findProject(workspaceId, projectId) != null
                },
                transcriptionModel = graph.transcriptionModel,
                quotaGuard = graph.quotaGuard,
                usage = graph.usage,
                costConfig = graph.costConfig,
                json = graph.json
            )
            adminRoutes(graph)
        }
    }
}

/**
 * Preserve stack frames and the cause/suppressed shape for diagnosis without copying exception
 * messages, which may contain SQL text, provider payloads, credentials, or source content.
 */
private fun sanitizeThrowableForLogging(cause: Throwable, depth: Int = 0): Throwable {
    val type = cause::class.qualifiedName ?: cause::class.simpleName ?: "Throwable"
    val sanitized = RuntimeException(type).also { it.stackTrace = cause.stackTrace }
    if (depth >= 8) return sanitized

    cause.cause
        ?.takeUnless { it === cause }
        ?.let { sanitized.initCause(sanitizeThrowableForLogging(it, depth + 1)) }
    cause.suppressed.take(8).forEach {
        sanitized.addSuppressed(sanitizeThrowableForLogging(it, depth + 1))
    }
    return sanitized
}
