package local.phantom.companion.core.resilience

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import local.phantom.companion.core.ai.AIChunk
import java.io.IOException

/**
 * Wraps any `Flow<AIChunk>` with retry-with-accumulated-prefix semantics.
 *
 * When the underlying flow throws an [IOException] the resumer:
 * 1. Captures the accumulated text from [AIChunk.Token] chunks seen so far.
 * 2. Sleeps via [delayProvider] using [RetryPolicy.delayFor].
 * 3. Re-invokes [resumeFactory] with the accumulated prefix — the real Gemini
 *    route can use this to prepend a fake assistant turn so the model
 *    "continues" rather than restarting from scratch.
 * 4. Emits new chunks from the resumed source until it completes or another
 *    [IOException] is caught.
 *
 * Tokens from failed attempts are **not** re-emitted — the UI sees each token
 * exactly once.  The final [AIChunk.Final.text] from the last successful
 * attempt wins.
 *
 * Non-[IOException] throwables are propagated immediately without retry.
 *
 * @param policy        Backoff/attempt-limit configuration.
 * @param delayProvider Injectable sleep so unit tests run instantly.
 */
class StreamResumer(
    private val policy: RetryPolicy = RetryPolicy.Default,
    private val delayProvider: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) {

    /**
     * Returns a [Flow] that emits chunks from [firstSource], retrying via
     * [resumeFactory] on transient [IOException]s according to [policy].
     *
     * @param firstSource    The initial source flow to collect.
     * @param resumeFactory  Called with the accumulated token prefix on each
     *                       retry so the next source can continue the response.
     */
    fun resumable(
        firstSource: Flow<AIChunk>,
        resumeFactory: (accumulatedPrefix: String) -> Flow<AIChunk>,
    ): Flow<AIChunk> = flow {
        val accumulated = StringBuilder()
        var attempt = 0
        var source = firstSource

        while (true) {
            var completed = false
            try {
                source.collect { chunk ->
                    when (chunk) {
                        is AIChunk.Token -> {
                            accumulated.append(chunk.text)
                            emit(chunk)
                        }
                        is AIChunk.Final -> {
                            emit(chunk)
                            completed = true
                        }
                        is AIChunk.ErrorChunk -> {
                            emit(chunk)
                            completed = true
                        }
                        is AIChunk.Notice -> emit(chunk)
                        is AIChunk.Grounding -> emit(chunk)
                        is AIChunk.ToolCall -> {
                            emit(chunk)
                            // A tool call usually stops text generation for this turn.
                            // We mark it as "completed" for this source collection.
                            completed = true
                        }
                    }
                }
                // Flow completed without exception — we are done.
                break
            } catch (e: IOException) {
                if (!policy.shouldRetry(attempt)) {
                    throw e
                }
                delayProvider(policy.delayFor(attempt))
                attempt++
                source = resumeFactory(accumulated.toString())
                // Loop continues with the new source.
            }
            // If the flow completed via Final/ErrorChunk (no exception), exit.
            if (completed) break
        }
    }
}
