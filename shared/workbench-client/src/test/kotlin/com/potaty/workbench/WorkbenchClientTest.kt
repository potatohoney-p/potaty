/*
 * Copyright (c) 2026, Potaty
 *
 * Pure-logic tests for the workbench client (no DOM, no network): DTO (de)serialization mirrors
 * the backend wire shapes, forward-compatibility (unknown keys ignored), and the JobPoller status
 * helpers. The suspend client/controller paths are compile-verified by the module build.
 */

package com.potaty.workbench

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

class WorkbenchClientTest {

    private data class SentRequest(
        val url: String,
        val headers: Map<String, String>
    )

    private class RecordingTransport(
        private val createdJobStatus: String = "failed",
        private val cancelResponse: HttpResponse = HttpResponse(503, "{}")
    ) : HttpTransport {
        val requests = mutableListOf<SentRequest>()

        override suspend fun send(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: String?
        ): HttpResponse {
            requests += SentRequest(url, headers)
            return when {
                url.endsWith("/sources") ->
                    HttpResponse(
                        200,
                        """{
                            "sourceId":"s","sourceVersionId":"sv",
                            "contentHash":"h","status":"normalized"
                        }
                        """.trimIndent()
                    )
                url.endsWith("/diagram-jobs") ->
                    HttpResponse(200, """{"jobId":"j","status":"$createdJobStatus"}""")
                url.endsWith("/jobs/j/cancel") -> cancelResponse
                url.endsWith("/jobs/j") ->
                    HttpResponse(
                        200,
                        """{
                            "jobId":"j","status":"succeeded",
                            "output":{"diagramId":"d","versionId":"v"}
                        }
                        """.trimIndent()
                    )
                url.endsWith("/diagrams/d/versions/v") ->
                    HttpResponse(
                        200,
                        """{"diagramId":"d","versionId":"v","status":"ready","ir":{}}"""
                    )
                else -> HttpResponse(404, "{}")
            }
        }
    }

    private class MemoryRetryStore : WorkbenchRetryStateStore {
        var state: WorkbenchRetryState? = null
        var failRead = false
        var failWrite = false

        override fun read(): WorkbenchRetryState? {
            if (failRead) error("read unavailable")
            return state
        }

        override fun write(state: WorkbenchRetryState) {
            if (failWrite) error("write unavailable")
            this.state = state
        }
    }

    @Test
    fun diagramJobRequestRoundTrips() {
        val req =
            DiagramJobRequest(
                sourceVersionIds = listOf("sv1", "sv2"),
                diagramType = "architecture",
                objective = "explain it",
                outputFormats = listOf("mermaid", "d2")
            )
        val encoded = WorkbenchJson.encodeToString(DiagramJobRequest.serializer(), req)
        val decoded = WorkbenchJson.decodeFromString(DiagramJobRequest.serializer(), encoded)
        assertEquals(req, decoded)
    }

    @Test
    fun createSourceResponseIgnoresUnknownKeysAndReadsCounts() {
        val json =
            """
            |{"sourceId":"s","sourceVersionId":"sv","contentHash":"h","status":"normalized",
            |"secretsRedacted":2,"piiWarnings":1,"serverOnlyField":"ignored"}
            """
                .trimMargin()
        val r = WorkbenchJson.decodeFromString(CreateSourceResponse.serializer(), json)
        assertEquals("sv", r.sourceVersionId)
        assertEquals(2, r.secretsRedacted)
        assertEquals(1, r.piiWarnings)
    }

    @Test
    fun githubIndexResponsePreservesCompletenessSignals() {
        val json =
            """{"sourceId":"s","sourceVersionId":"sv","filesIndexed":10,""" +
                """"filesSkipped":3,"chunkCount":12,"treeTruncated":true,""" +
                """"owner":"o","repo":"r","ref":"main"}"""
        val response = WorkbenchJson.decodeFromString(GitHubIndexResponse.serializer(), json)

        assertEquals(3, response.filesSkipped)
        assertTrue(response.treeTruncated)
    }

    @Test
    fun jobStatusOutputParses() {
        val json =
            """{"jobId":"j","status":"succeeded","output":{"diagramId":"d1","versionId":"v1"}}"""
        val r = WorkbenchJson.decodeFromString(JobStatusResponse.serializer(), json)
        assertEquals("succeeded", r.status)
        assertEquals("d1", r.output!!["diagramId"]!!.jsonPrimitive.content)
    }

    @Test
    fun terminalJobReasonParses() {
        val json =
            """{"jobId":"j","status":"needs_input","reason":"Add named steps"}"""
        val response = WorkbenchJson.decodeFromString(JobStatusResponse.serializer(), json)

        assertEquals("Add named steps", response.reason)
        assertTrue(JobPoller.isTerminal(response.status))
    }

    @Test
    fun diagramVersionPreservesServerValidationReport() {
        val json =
            """
            |{
            |  "diagramId":"d","versionId":"v","status":"needs_review","ir":{},
            |  "validationReport":{
            |    "valid":false,
            |    "violations":[{
            |      "rule":"IR-R011","severity":"error","message":"cycle",
            |      "target_id":"edge-1"
            |    }],
            |    "warnings":[]
            |  },
            |  "evidenceCoverage":{
            |    "nodeCoverage":1.0,"edgeCoverage":1.0,"unsupportedCriticalClaims":0
            |  }
            |}
            """.trimMargin()
        val response = WorkbenchJson.decodeFromString(DiagramVersionResponse.serializer(), json)

        assertFalse(response.validationReport.valid)
        assertEquals("IR-R011", response.validationReport.violations.single().rule)
        assertEquals("edge-1", response.validationReport.violations.single().targetId)
    }

    @Test
    fun jobPollerTerminalAndSuccessLogic() {
        assertTrue(JobPoller.isTerminal("succeeded"))
        assertTrue(JobPoller.isSuccess("SUCCEEDED"))
        assertTrue(JobPoller.isTerminal("failed"))
        assertFalse(JobPoller.isSuccess("failed"))
        assertTrue(JobPoller.isTerminal("needs_input"))
        assertFalse(JobPoller.isSuccess("needs_input"))
        assertFalse(JobPoller.isTerminal("running"))
        assertFalse(JobPoller.isTerminal("queued"))
    }

    @Test
    fun retryKeysSurviveUnknownOutcomeAndRotateAtKnownBoundaries() {
        var sequence = 0
        val keys = WorkbenchRetryKeys(nextKey = { "key-${++sequence}" })

        val first = keys.forAttempt("source-a", "generation-a")
        val responseLost = keys.forAttempt("source-a", "generation-a")
        assertEquals(first, responseLost, "unknown outcome must reuse both keys")

        keys.finishAttempt("generation-a", first.jobKey)
        val afterTerminal = keys.forAttempt("source-a", "generation-a")
        assertEquals(first.sourceKey, afterTerminal.sourceKey)
        assertNotEquals(first.jobKey, afterTerminal.jobKey)

        val changedGeneration = keys.forAttempt("source-a", "generation-b")
        assertEquals(first.sourceKey, changedGeneration.sourceKey)
        assertNotEquals(afterTerminal.jobKey, changedGeneration.jobKey)

        val changedSource = keys.forAttempt("source-b", "generation-c")
        assertNotEquals(first.sourceKey, changedSource.sourceKey)
        assertNotEquals(changedGeneration.jobKey, changedSource.jobKey)
    }

    @Test
    fun retryKeysRestoreAcrossControllerRecreationAndIgnoreStaleTerminalResult() {
        var sequence = 0
        val store = MemoryRetryStore()
        val firstController = WorkbenchRetryKeys({ "key-${++sequence}" }, store)
        val first = firstController.forAttempt("source-digest", "generation-digest")

        val reloadedController = WorkbenchRetryKeys({ "key-${++sequence}" }, store)
        val restored =
            reloadedController.forAttempt("source-digest", "generation-digest")
        assertEquals(first, restored, "tab reload must recover both unknown-outcome keys")

        reloadedController.finishAttempt("generation-digest", "stale-job-key")
        assertEquals(
            first,
            reloadedController.forAttempt("source-digest", "generation-digest")
        )

        reloadedController.finishAttempt("generation-digest", first.jobKey)
        val afterTerminal =
            WorkbenchRetryKeys({ "key-${++sequence}" }, store)
                .forAttempt("source-digest", "generation-digest")
        assertEquals(first.sourceKey, afterTerminal.sourceKey)
        assertNotEquals(first.jobKey, afterTerminal.jobKey)
    }

    @Test
    fun retryKeysNeverEvictAnUnresolvedAttemptAtCapacity() {
        var sequence = 0
        val store = MemoryRetryStore()
        val keys = WorkbenchRetryKeys({ "key-${++sequence}" }, store, maxEntries = 2)
        val oldest = keys.forAttempt("source-a", "generation-a")
        keys.forAttempt("source-b", "generation-b")

        assertFailsWith<RetryStateCapacityException> {
            keys.forAttempt("source-c", "generation-c")
        }
        assertEquals(4, sequence, "capacity must be checked before allocating new keys")
        assertEquals(
            oldest,
            keys.forAttempt("source-a", "generation-a"),
            "the oldest unknown outcome must remain replayable"
        )
        assertEquals(2, store.state?.jobs?.size)
    }

    @Test
    fun retryKeysFailClosedWhenPersistenceCannotBeReadOrWritten() {
        var readSequence = 0
        val unreadable = MemoryRetryStore().also { it.failRead = true }
        val afterReadFailure =
            WorkbenchRetryKeys({ "read-key-${++readSequence}" }, unreadable)

        assertFailsWith<RetryStateUnavailableException> {
            afterReadFailure.forAttempt("source-a", "generation-a")
        }
        assertEquals(0, readSequence, "an unreadable store must block before key allocation")

        var writeSequence = 0
        val unwritable = MemoryRetryStore().also { it.failWrite = true }
        val afterWriteFailure =
            WorkbenchRetryKeys({ "write-key-${++writeSequence}" }, unwritable)

        assertFailsWith<RetryStateUnavailableException> {
            afterWriteFailure.forAttempt("source-b", "generation-b")
        }
        assertEquals(2, writeSequence)
        assertEquals(null, unwritable.state)
        unwritable.failWrite = false
        assertFailsWith<RetryStateUnavailableException> {
            afterWriteFailure.forAttempt("source-b", "generation-b")
        }
        assertEquals(2, writeSequence, "a failed page session stays closed until reload")
    }

    @Test
    fun retryKeyRetirementCommitsOnlyAfterDurableStorageSucceeds() {
        var sequence = 0
        val store = MemoryRetryStore()
        val keys = WorkbenchRetryKeys({ "key-${++sequence}" }, store)
        val first = keys.forAttempt("source-a", "generation-a")

        store.failWrite = true
        assertFailsWith<RetryStateUnavailableException> {
            keys.finishAttempt("generation-a", first.jobKey)
        }
        store.failWrite = false

        val afterReload = WorkbenchRetryKeys({ "key-${++sequence}" }, store)
        assertEquals(
            first,
            afterReload.forAttempt("source-a", "generation-a"),
            "a failed retirement write must leave the authoritative unknown key intact"
        )
    }

    @Test
    fun retryAttemptIsConsumedOnlyAfterUsableSuccessOrNonSuccessTerminalState() {
        assertFalse(
            WorkbenchResult.Failed("succeeded", "output is not visible yet")
                .consumesRetryAttempt()
        )
        assertFalse(
            WorkbenchResult.Failed("cancellation_unconfirmed")
                .consumesRetryAttempt()
        )
        assertTrue(WorkbenchResult.Failed("failed").consumesRetryAttempt())
        assertTrue(WorkbenchResult.Failed("cancelled").consumesRetryAttempt())

        val ready =
            WorkbenchResult.Ready(
                version =
                DiagramVersionResponse(
                    diagramId = "d",
                    versionId = "v",
                    status = "ready",
                    ir = buildJsonObject {}
                ),
                mermaid = null,
                source = WorkbenchSourceSummary("text", "source", "s", "sv")
            )
        assertTrue(ready.consumesRetryAttempt())
    }

    @Test
    fun workbenchUsesIndependentSourceAndJobIdempotencyKeys() {
        val transport = RecordingTransport()
        val controller =
            WorkbenchController(
                PotatyApiClient("https://potaty.test", transport, "test-token")
            )

        val result = runImmediate {
            controller.generateFromText(
                projectId = "project",
                text = "A calls B",
                diagramType = "architecture",
                sourceIdempotencyKey = "source-key",
                jobIdempotencyKey = "job-key"
            )
        }

        assertIs<WorkbenchResult.Failed>(result)
        assertEquals(2, transport.requests.size)
        assertEquals("source-key", transport.requests[0].headers["Idempotency-Key"])
        assertEquals("job-key", transport.requests[1].headers["Idempotency-Key"])
    }

    @Test
    fun succeededJobReplayRefreshesOutputInsteadOfRotatingIntoFailure() {
        val transport = RecordingTransport(createdJobStatus = "succeeded")
        val controller =
            WorkbenchController(
                PotatyApiClient("https://potaty.test", transport, "test-token")
            )

        val result = runImmediate {
            controller.generateFromText(
                projectId = "project",
                text = "A calls B",
                diagramType = "architecture",
                sourceIdempotencyKey = "source-key",
                jobIdempotencyKey = "job-key"
            )
        }

        assertIs<WorkbenchResult.Ready>(result)
        assertTrue(transport.requests.any { it.url.endsWith("/jobs/j") })
        assertTrue(transport.requests.any { it.url.endsWith("/diagrams/d/versions/v") })
    }

    @Test
    fun failedCancellationAcknowledgementRemainsRetryable() {
        val transport = RecordingTransport(createdJobStatus = "queued")
        val controller = WorkbenchController(PotatyApiClient("https://potaty.test", transport))
        var cancellationChecks = 0

        val result = runImmediate {
            controller.generateFromText(
                projectId = "project",
                text = "A calls B",
                diagramType = "architecture",
                sourceIdempotencyKey = "source-key",
                jobIdempotencyKey = "job-key",
                isCancelled = { ++cancellationChecks >= 2 }
            )
        }

        val failed = assertIs<WorkbenchResult.Failed>(result)
        assertEquals("cancellation_unconfirmed", failed.status)
        assertFalse(JobPoller.isTerminal(failed.status))
        assertTrue(transport.requests.any { it.url.endsWith("/jobs/j/cancel") })
    }

    @Test
    fun cancellationThatRacesWithSuccessPreservesJobForOutputRecovery() {
        val transport =
            RecordingTransport(
                createdJobStatus = "queued",
                cancelResponse =
                HttpResponse(
                    200,
                    """{"jobId":"j","status":"succeeded","cancelled":false}"""
                )
            )
        val controller = WorkbenchController(PotatyApiClient("https://potaty.test", transport))
        var cancellationChecks = 0

        val result = runImmediate {
            controller.generateFromText(
                projectId = "project",
                text = "A calls B",
                diagramType = "architecture",
                sourceIdempotencyKey = "source-key",
                jobIdempotencyKey = "job-key",
                isCancelled = { ++cancellationChecks >= 2 }
            )
        }

        val failed = assertIs<WorkbenchResult.Failed>(result)
        assertEquals("cancellation_result_pending", failed.status)
        assertFalse(JobPoller.isTerminal(failed.status))
        assertEquals("succeeded", failed.cancellation?.status)
    }

    @Test
    fun fetchTransportAbortSettlesTheSuspendedRequestAndAbortsItsSignal() {
        val global: dynamic = js("globalThis")
        val originalFetch = global.fetch
        var signal: dynamic = null
        global.fetch = { _: dynamic, init: dynamic ->
            signal = init.signal
            Promise<dynamic> { _, _ -> }
        }

        try {
            val transport = FetchTransport(requestTimeoutMs = 1_000)
            var completions = 0
            var failure: Throwable? = null
            val request: suspend () -> HttpResponse = {
                transport.send("GET", "https://potaty.test/stalled", emptyMap(), null)
            }
            request.startCoroutine(
                object : Continuation<HttpResponse> {
                    override val context = EmptyCoroutineContext

                    override fun resumeWith(result: Result<HttpResponse>) {
                        completions++
                        failure = result.exceptionOrNull()
                    }
                }
            )

            assertEquals(0, completions)
            transport.abortInFlight()
            assertEquals(1, completions)
            assertEquals("request aborted", failure?.message)
            assertTrue(signal.aborted as Boolean)
        } finally {
            global.fetch = originalFetch
        }
    }

    @Test
    fun fetchTransportDeadlineAbortsAndFailsWithAStableMessage(): Promise<Unit> {
        val global: dynamic = js("globalThis")
        val originalFetch = global.fetch
        var signal: dynamic = null
        global.fetch = { _: dynamic, init: dynamic ->
            signal = init.signal
            Promise<dynamic> { _, _ -> }
        }

        return Promise { resolve, reject ->
            val transport = FetchTransport(requestTimeoutMs = 10)
            val request: suspend () -> HttpResponse = {
                transport.send("GET", "https://potaty.test/stalled", emptyMap(), null)
            }
            request.startCoroutine(
                object : Continuation<HttpResponse> {
                    override val context = EmptyCoroutineContext

                    override fun resumeWith(result: Result<HttpResponse>) {
                        try {
                            assertEquals("request timed out", result.exceptionOrNull()?.message)
                            assertTrue(signal.aborted as Boolean)
                            resolve(Unit)
                        } catch (error: Throwable) {
                            reject(error)
                        } finally {
                            global.fetch = originalFetch
                        }
                    }
                }
            )
        }
    }

    @Test
    fun fetchTransportDeadlineCoversBodyReadAndIgnoresLateResolution(): Promise<Unit> {
        val global: dynamic = js("globalThis")
        val originalFetch = global.fetch
        var signal: dynamic = null
        var bodyResolve: ((String) -> Unit)? = null
        val response: dynamic = js("({status: 200})")
        response.text = {
            Promise<String> { resolve, _ -> bodyResolve = resolve }
        }
        global.fetch = { _: dynamic, init: dynamic ->
            signal = init.signal
            Promise<dynamic> { resolve, _ -> resolve(response) }
        }

        return Promise { resolve, reject ->
            val transport = FetchTransport(requestTimeoutMs = 10)
            var completions = 0
            val request: suspend () -> HttpResponse = {
                transport.send("GET", "https://potaty.test/stalled-body", emptyMap(), null)
            }
            request.startCoroutine(
                object : Continuation<HttpResponse> {
                    override val context = EmptyCoroutineContext

                    override fun resumeWith(result: Result<HttpResponse>) {
                        completions++
                        try {
                            assertEquals("request timed out", result.exceptionOrNull()?.message)
                            assertTrue(signal.aborted as Boolean)
                            val resolveBody = bodyResolve ?: error("response.text was not started")
                            resolveBody("late response body")
                            Promise.resolve(Unit).then {
                                try {
                                    assertEquals(1, completions)
                                    global.fetch = originalFetch
                                    resolve(Unit)
                                } catch (error: Throwable) {
                                    global.fetch = originalFetch
                                    reject(error)
                                }
                            }
                        } catch (error: Throwable) {
                            global.fetch = originalFetch
                            reject(error)
                        }
                    }
                }
            )
        }
    }

    @Test
    fun fetchTransportAbortDuringBodyReadIgnoresLateRejection(): Promise<Unit> {
        val global: dynamic = js("globalThis")
        val originalFetch = global.fetch
        var signal: dynamic = null
        var bodyReject: ((Throwable) -> Unit)? = null
        val response: dynamic = js("({status: 200})")
        response.text = {
            Promise<String> { _, reject -> bodyReject = reject }
        }
        global.fetch = { _: dynamic, init: dynamic ->
            signal = init.signal
            Promise<dynamic> { resolve, _ -> resolve(response) }
        }

        return Promise { resolve, reject ->
            val transport = FetchTransport(requestTimeoutMs = 1_000)
            var completions = 0
            var failure: Throwable? = null
            val request: suspend () -> HttpResponse = {
                transport.send("GET", "https://potaty.test/stalled-body", emptyMap(), null)
            }
            request.startCoroutine(
                object : Continuation<HttpResponse> {
                    override val context = EmptyCoroutineContext

                    override fun resumeWith(result: Result<HttpResponse>) {
                        completions++
                        failure = result.exceptionOrNull()
                    }
                }
            )

            Promise.resolve(Unit).then {
                try {
                    val rejectBody = bodyReject ?: error("response.text was not started")
                    transport.abortInFlight()
                    assertEquals("request aborted", failure?.message)
                    assertTrue(signal.aborted as Boolean)
                    rejectBody(RuntimeException("late response read failure"))
                    Promise.resolve(Unit).then {
                        try {
                            assertEquals(1, completions)
                            global.fetch = originalFetch
                            resolve(Unit)
                        } catch (error: Throwable) {
                            global.fetch = originalFetch
                            reject(error)
                        }
                    }
                } catch (error: Throwable) {
                    global.fetch = originalFetch
                    reject(error)
                }
            }
        }
    }

    private fun <T> runImmediate(block: suspend () -> T): T {
        var completed = false
        var value: T? = null
        var failure: Throwable? = null
        block.startCoroutine(
            object : Continuation<T> {
                override val context = EmptyCoroutineContext

                override fun resumeWith(result: Result<T>) {
                    result.onSuccess { value = it }
                    result.onFailure { failure = it }
                    completed = true
                }
            }
        )
        check(completed) { "test coroutine unexpectedly suspended" }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }
}
