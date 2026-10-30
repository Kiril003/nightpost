package local.phantom.companion.core.resilience

/**
 * Pure descriptor for exponential-backoff retry behaviour used by
 * [StreamResumer].
 *
 * Delay formula: `baseDelayMs * 2^attempt`, clamped at [MAX_DELAY_MS].
 *
 * | attempt | delay (default base=200ms) |
 * |---------|---------------------------|
 * |    0    | 200 ms                    |
 * |    1    | 400 ms                    |
 * |    2    | 800 ms                    |
 * |    3    | 1 600 ms                  |
 * |    4    | 3 200 ms                  |
 * |    5+   | 5 000 ms (clamped)        |
 */
data class RetryPolicy(
    /** Total number of retry attempts allowed after the initial failure. */
    val maxAttempts: Int = 3,
    /** Base backoff delay in milliseconds (doubles each attempt). */
    val baseDelayMs: Long = 200L,
) {
    /**
     * Returns the delay in milliseconds before the given retry [attempt].
     *
     * [attempt] 0 is the first retry (after the initial failure), so
     * `delayFor(0) == baseDelayMs`.  Result is clamped at [MAX_DELAY_MS].
     */
    fun delayFor(attempt: Int): Long {
        val mult = (1L shl attempt.coerceAtLeast(0))
        return (baseDelayMs * mult).coerceAtMost(MAX_DELAY_MS)
    }

    /**
     * Returns `true` if the caller should attempt another retry after
     * `[attempt]` consecutive failures.
     */
    fun shouldRetry(attempt: Int): Boolean = attempt < maxAttempts

    companion object {
        /** Hard upper bound on a single backoff sleep to keep UX responsive. */
        const val MAX_DELAY_MS = 5_000L

        /** Sensible default used throughout the app unless overridden. */
        val Default = RetryPolicy()
    }
}
