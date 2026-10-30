package local.phantom.companion.core.resilience

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.ai.AIChunk
import local.phantom.companion.core.ai.AIError
import local.phantom.companion.core.ai.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/** Instant delay provider — eliminates all sleep in retry tests. */
private val noDelay: suspend (Long) -> Unit = {}

class StreamResumerTest {

    // ── helpers ───────────────────────────────────────────────────────────

    private fun token(text: String) = AIChunk.Token(text, Route.CLOUD_GEMINI)
    private fun final(text: String) = AIChunk.Final(text, 0, Route.CLOUD_GEMINI, 0L)
    private fun errorChunk(msg: String) =
        AIChunk.ErrorChunk(AIError.NoNetwork(), msg, Route.CLOUD_GEMINI)

    // ── happy path ────────────────────────────────────────────────────────

    @Test
    fun `happy path emits all chunks without retry`() = runTest {
        val resumer = StreamResumer(RetryPolicy(maxAttempts = 3), noDelay)

        val source = flow {
            emit(token("Hello"))
            emit(token(" world"))
            emit(final("Hello world"))
        }

        val result = resumer.resumable(source) { error("should not be called") }.toList()

        assertEquals(3, result.size)
        assertEquals(token("Hello"), result[0])
        assertEquals(token(" world"), result[1])
        assertEquals(final("Hello world"), result[2])
    }

    // ── one IOException then success ──────────────────────────────────────

    @Test
    fun `one IOException then success — upstream sees tokens from both attempts`() = runTest {
        val resumer = StreamResumer(RetryPolicy(maxAttempts = 3), noDelay)

        // First source: emits "Hi" then throws IOException
        val firstSource = flow {
            emit(token("Hi"))
            throw IOException("network blip")
        }

        var capturedPrefix: String? = null

        // Resume factory: records accumulated prefix, emits continuation
        val resumeFactory: (String) -> kotlinx.coroutines.flow.Flow<AIChunk> = { prefix ->
            capturedPrefix = prefix
            flow {
                emit(token(" there"))
                emit(final("Hi there"))
            }
        }

        val result = resumer.resumable(firstSource, resumeFactory).toList()

        // Prefix captured must equal what was emitted before the failure
        assertEquals("Hi", capturedPrefix)

        // Upstream sees: Token("Hi") from first attempt, Token(" there") + Final from second
        assertEquals(3, result.size)
        assertEquals(token("Hi"), result[0])
        assertEquals(token(" there"), result[1])
        assertEquals(final("Hi there"), result[2])
    }

    // ── exhaust retries ───────────────────────────────────────────────────

    @Test
    fun `exhausting retries propagates IOException`() = runTest {
        val policy = RetryPolicy(maxAttempts = 2)
        val resumer = StreamResumer(policy, noDelay)

        var callCount = 0

        val alwaysFails = flow<AIChunk> {
            callCount++
            throw IOException("always fails")
        }

        try {
            resumer.resumable(alwaysFails) { alwaysFails }.toList()
            fail("Expected IOException to be propagated")
        } catch (e: IOException) {
            assertEquals("always fails", e.message)
        }

        // initial attempt + maxAttempts retries = 3 total
        assertEquals(3, callCount)
    }

    // ── non-IO exception propagates without retry ─────────────────────────

    @Test
    fun `non-IOException propagates immediately without retry`() = runTest {
        val resumer = StreamResumer(RetryPolicy(maxAttempts = 5), noDelay)
        var factoryCalled = false

        val source = flow<AIChunk> {
            throw IllegalStateException("not a network error")
        }

        try {
            resumer.resumable(source) {
                factoryCalled = true
                flow { emit(final("should not reach")) }
            }.toList()
            fail("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("not a network error", e.message)
        }

        // resumeFactory must never be called for non-IO failures
        assertTrue("resumeFactory should not be called for non-IOException", !factoryCalled)
    }

    // ── ErrorChunk terminates without exception ───────────────────────────

    @Test
    fun `ErrorChunk emitted by source terminates stream without retry`() = runTest {
        val resumer = StreamResumer(RetryPolicy(maxAttempts = 3), noDelay)
        var factoryCalled = false

        val source = flow {
            emit(token("partial"))
            emit(errorChunk("upstream error"))
        }

        val result = resumer.resumable(source) {
            factoryCalled = true
            flow { emit(final("resume")) }
        }.toList()

        assertEquals(2, result.size)
        assertTrue(result[1] is AIChunk.ErrorChunk)
        assertTrue("resumeFactory should not be called for ErrorChunk", !factoryCalled)
    }

    // ── delay amounts are passed through ─────────────────────────────────

    @Test
    fun `delay amounts respect RetryPolicy delayFor`() = runTest {
        val policy = RetryPolicy(maxAttempts = 3, baseDelayMs = 100L)
        val delays = mutableListOf<Long>()
        val capturingDelay: suspend (Long) -> Unit = { delays.add(it) }

        val resumer = StreamResumer(policy, capturingDelay)

        var attempt = 0
        val source = flow<AIChunk> {
            if (attempt++ < 2) throw IOException("retry $attempt")
            emit(final("done"))
        }

        resumer.resumable(source) { source }.toList()

        // attempt 0 → 100ms, attempt 1 → 200ms
        assertEquals(listOf(100L, 200L), delays)
    }

    // ── accumulated prefix grows across multiple retries ──────────────────

    @Test
    fun `accumulated prefix grows correctly across multiple retries`() = runTest {
        val policy = RetryPolicy(maxAttempts = 3, baseDelayMs = 0L)
        val resumer = StreamResumer(policy, noDelay)

        val prefixes = mutableListOf<String>()

        // Attempt 0: emits "A" then throws
        // Attempt 1 (resume with "A"): emits "B" then throws
        // Attempt 2 (resume with "AB"): emits Final
        var resumeCount = 0

        val firstSource = flow {
            emit(token("A"))
            throw IOException("first fail")
        }

        val resumeFactory: (String) -> kotlinx.coroutines.flow.Flow<AIChunk> = { prefix ->
            prefixes.add(prefix)
            resumeCount++
            when (resumeCount) {
                1 -> flow {
                    emit(token("B"))
                    throw IOException("second fail")
                }
                else -> flow {
                    emit(final("AB"))
                }
            }
        }

        val result = resumer.resumable(firstSource, resumeFactory).toList()

        assertEquals("A", prefixes[0])
        assertEquals("AB", prefixes[1])
        // Upstream sees A, B, Final
        assertEquals(3, result.size)
        assertNotNull(result.filterIsInstance<AIChunk.Final>().firstOrNull())
    }
}
