/*
 * Copyright (c) 2026, Potaty
 *
 * Session-local idempotency ownership for the browser generation flow. Unknown outcomes retain
 * both keys so a human retry cannot duplicate source ingestion or provider spend. A known terminal
 * outcome rotates only the job key: unchanged source material can still replay its durable source.
 */

package com.potaty.workbench

data class WorkbenchIdempotencyKeys(
    val sourceKey: String,
    val jobKey: String
)

data class WorkbenchRetrySourceKey(
    val fingerprint: String,
    val key: String
)

data class WorkbenchRetryJobKey(
    val fingerprint: String,
    val sourceFingerprint: String,
    val key: String
)

data class WorkbenchRetryState(
    val sources: List<WorkbenchRetrySourceKey>,
    val jobs: List<WorkbenchRetryJobKey>
)

interface WorkbenchRetryStateStore {
    fun read(): WorkbenchRetryState?

    fun write(state: WorkbenchRetryState)
}

class WorkbenchRetryKeys(
    private val nextKey: () -> String,
    private val store: WorkbenchRetryStateStore? = null,
    private val maxEntries: Int = 16
) {
    private val sourceKeys = linkedMapOf<String, String>()
    private val jobKeys = linkedMapOf<String, WorkbenchRetryJobKey>()
    private var persistenceUnavailable = false

    init {
        require(maxEntries in 1..64) { "maxEntries must be between 1 and 64" }
        try {
            store?.read()?.let(::restore)
        } catch (_: Throwable) {
            // Keep the application usable, but never issue a mutation from an empty state when
            // durable retry ownership could not be established. [forAttempt] fails closed.
            persistenceUnavailable = true
        }
    }

    fun forAttempt(
        sourceFingerprint: String,
        generationFingerprint: String
    ): WorkbenchIdempotencyKeys {
        require(sourceFingerprint.isNotEmpty()) { "source fingerprint must not be empty" }
        require(generationFingerprint.isNotEmpty()) { "generation fingerprint must not be empty" }
        ensurePersistenceAvailable()

        val storedJob = jobKeys[generationFingerprint]
        if (storedJob == null && jobKeys.size >= maxEntries) {
            throw RetryStateCapacityException(maxEntries)
        }
        if (storedJob != null && storedJob.sourceFingerprint != sourceFingerprint) {
            throw RetryStateUnavailableException()
        }

        val candidateSources = sourceKeys.mutableCopy()
        val candidateJobs = jobKeys.mutableCopy()
        val sourceKey = candidateSources.remove(sourceFingerprint) ?: newKey()
        candidateSources[sourceFingerprint] = sourceKey

        candidateJobs.remove(generationFingerprint)
        val jobKey = storedJob?.key ?: newKey()
        candidateJobs[generationFingerprint] =
            WorkbenchRetryJobKey(generationFingerprint, sourceFingerprint, jobKey)
        trimCompletedSources(candidateSources, candidateJobs)
        persist(candidateSources, candidateJobs)
        replaceState(candidateSources, candidateJobs)
        return WorkbenchIdempotencyKeys(sourceKey, jobKey)
    }

    /** A known terminal result permits a fresh job while retaining safe source replay. */
    fun finishAttempt(generationFingerprint: String, expectedJobKey: String) {
        ensurePersistenceAvailable()
        if (jobKeys[generationFingerprint]?.key != expectedJobKey) return

        val candidateSources = sourceKeys.mutableCopy()
        val candidateJobs = jobKeys.mutableCopy()
        candidateJobs.remove(generationFingerprint)
        persist(candidateSources, candidateJobs)
        replaceState(candidateSources, candidateJobs)
    }

    private fun restore(state: WorkbenchRetryState) {
        require(state.sources.size <= maxEntries && state.jobs.size <= maxEntries) {
            "retry state exceeds configured capacity"
        }
        state.sources.forEach { source ->
            require(source.fingerprint.isNotEmpty() && validKey(source.key)) {
                "retry source state is invalid"
            }
            require(sourceKeys.put(source.fingerprint, source.key) == null) {
                "retry source state contains duplicates"
            }
        }
        state.jobs.forEach { job ->
            require(
                job.fingerprint.isNotEmpty() &&
                    job.sourceFingerprint.isNotEmpty() &&
                    validKey(job.key) &&
                    sourceKeys.containsKey(job.sourceFingerprint)
            ) {
                "retry job state is invalid"
            }
            require(jobKeys.put(job.fingerprint, job) == null) {
                "retry job state contains duplicates"
            }
        }
    }

    private fun ensurePersistenceAvailable() {
        if (persistenceUnavailable) throw RetryStateUnavailableException()
    }

    private fun trimCompletedSources(
        sources: MutableMap<String, String>,
        jobs: Map<String, WorkbenchRetryJobKey>
    ) {
        while (sources.size > maxEntries) {
            val referenced = jobs.values.mapTo(mutableSetOf()) { it.sourceFingerprint }
            val removable = sources.keys.firstOrNull { it !in referenced }
                ?: throw RetryStateCapacityException(maxEntries)
            sources.remove(removable)
        }
    }

    private fun persist(
        sources: Map<String, String>,
        jobs: Map<String, WorkbenchRetryJobKey>
    ) {
        val state =
            WorkbenchRetryState(
                sources = sources.map { WorkbenchRetrySourceKey(it.key, it.value) },
                jobs = jobs.values.toList()
            )
        try {
            store?.write(state)
        } catch (_: Throwable) {
            persistenceUnavailable = true
            throw RetryStateUnavailableException()
        }
    }

    private fun replaceState(
        sources: Map<String, String>,
        jobs: Map<String, WorkbenchRetryJobKey>
    ) {
        sourceKeys.clear()
        sourceKeys.putAll(sources)
        jobKeys.clear()
        jobKeys.putAll(jobs)
    }

    private fun newKey(): String = nextKey().also { key ->
        require(validKey(key)) { "generated idempotency key is invalid" }
    }

    private fun validKey(key: String): Boolean = IDEMPOTENCY_KEY_PATTERN.matches(key)

    private companion object {
        val IDEMPOTENCY_KEY_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,255}$")
    }
}

class RetryStateUnavailableException :
    IllegalStateException("safe retry state is unavailable")

class RetryStateCapacityException(maxEntries: Int) :
    IllegalStateException(
        "safe retry capacity reached ($maxEntries unresolved attempts)"
    )

private fun <K, V> Map<K, V>.mutableCopy(): LinkedHashMap<K, V> =
    linkedMapOf<K, V>().also { it.putAll(this) }
