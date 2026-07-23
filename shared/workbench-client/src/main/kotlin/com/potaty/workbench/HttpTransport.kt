/*
 * Copyright (c) 2026, Potaty
 *
 * Pluggable HTTP transport for the workbench client. The client logic depends only on this
 * interface, so it is fully unit-testable with a fake transport. [FetchTransport] is the default
 * browser/Node adapter over the global `fetch`; a host app may inject its own instead.
 */

package com.potaty.workbench

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.js.Promise

data class HttpResponse(val status: Int, val body: String) {
    val isSuccess: Boolean
        get() = status in 200..299
}

interface HttpTransport {
    suspend fun send(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?
    ): HttpResponse
}

/** Minimal typed view of the global fetch + Response so `.then` type inference is unambiguous. */
private external interface FetchResponse {
    val status: Int

    fun text(): Promise<String>
}

/**
 * The global `fetch`. File-private so it never clashes with org.w3c.fetch if that is imported
 * elsewhere.
 */
private external fun fetch(url: String, init: dynamic): Promise<FetchResponse>

private external class AbortController {
    val signal: dynamic

    fun abort()
}

private external fun setTimeout(
    handler: () -> Unit,
    timeout: Int
): Int

private external fun clearTimeout(handle: Int)

/**
 * Browser/Node `fetch`-backed transport. Every request has a whole-response deadline and an abort
 * signal. [abortInFlight] is used when the host invalidates a UI flow: the suspended continuation
 * is failed immediately, while late fetch/text callbacks are ignored by the single-settlement
 * guard. The caller can then safely retain its idempotency key as an unknown outcome.
 */
class FetchTransport(
    private val requestTimeoutMs: Int = DEFAULT_REQUEST_TIMEOUT_MS
) : HttpTransport {
    private class ActiveRequest(val abort: () -> Unit)

    private val activeRequests = mutableListOf<ActiveRequest>()

    init {
        require(requestTimeoutMs in 1..MAX_REQUEST_TIMEOUT_MS) {
            "requestTimeoutMs must be between 1 and $MAX_REQUEST_TIMEOUT_MS"
        }
    }

    /** Abort and settle every request currently owned by this transport. */
    fun abortInFlight() {
        activeRequests.toList().forEach { it.abort() }
    }

    override suspend fun send(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?
    ): HttpResponse = suspendCoroutine { cont ->
        val controller = AbortController()
        val init: dynamic = js("({})")
        init.method = method
        init.signal = controller.signal
        val h: dynamic = js("({})")
        for ((k, v) in headers) h[k] = v
        init.headers = h
        if (body != null) init.body = body

        var settled = false
        var timeoutHandle: Int? = null
        var activeRequest: ActiveRequest? = null

        fun cleanup() {
            timeoutHandle?.let(::clearTimeout)
            timeoutHandle = null
            activeRequest?.let(activeRequests::remove)
            activeRequest = null
        }

        fun succeed(response: HttpResponse) {
            if (settled) return
            settled = true
            cleanup()
            cont.resume(response)
        }

        fun fail(message: String) {
            if (settled) return
            settled = true
            cleanup()
            cont.resumeWithException(RuntimeException(message))
        }

        val request =
            ActiveRequest {
                runCatching { controller.abort() }
                fail("request aborted")
            }
        activeRequest = request
        activeRequests += request
        timeoutHandle =
            setTimeout(
                {
                    runCatching { controller.abort() }
                    fail("request timed out")
                },
                requestTimeoutMs
            )

        try {
            fetch(url, init)
                .then<Unit>(
                    { resp ->
                        val status = resp.status
                        try {
                            resp.text()
                                .then<Unit>(
                                    { text -> succeed(HttpResponse(status, text)) },
                                    { _ -> fail("response read failed") }
                                )
                        } catch (_: Throwable) {
                            fail("response read failed")
                        }
                        Unit
                    },
                    { _ -> fail("fetch failed") }
                )
        } catch (_: Throwable) {
            fail("fetch failed")
        }
    }

    private companion object {
        const val DEFAULT_REQUEST_TIMEOUT_MS = 120_000
        const val MAX_REQUEST_TIMEOUT_MS = 15 * 60 * 1_000
    }
}
