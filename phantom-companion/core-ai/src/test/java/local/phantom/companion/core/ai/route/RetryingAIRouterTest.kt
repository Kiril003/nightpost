package local.phantom.companion.core.ai.route

import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.ai.AIChunk
import local.phantom.companion.core.ai.AIError
import local.phantom.companion.core.ai.AIIntent
import local.phantom.companion.core.ai.AIRouter
import local.phantom.companion.core.ai.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Одна запасна спроба легшою моделлю на 5xx чи мовчання; решта збоїв іде до людини одразу. */
class RetryingAIRouterTest {

    private class FakeRouter(private val attempts: List<List<AIChunk>>, private val silent: Boolean = false) : AIRouter {
        var attemptCount: Int = 0
        override fun complete(intent: AIIntent): Flow<AIChunk> {
            val chunks = attempts.getOrNull(attemptCount) ?: emptyList()
            attemptCount++
            return if (silent) flow { awaitCancellation() } else flowOf(*chunks.toTypedArray())
        }
        override suspend fun availableRoutes(): Set<Route> = setOf(Route.CLOUD_GEMINI)
    }

    private fun token(text: String) = AIChunk.Token(text, route = Route.CLOUD_GEMINI)
    private fun final(text: String) = AIChunk.Final(text, tokensUsed = 1, route = Route.CLOUD_GEMINI, durationMs = 1)
    private fun error(cause: AIError) = AIChunk.ErrorChunk(cause = cause, route = Route.CLOUD_GEMINI)
    private val answers = FakeRouter(listOf(listOf(token("hi"), final("hi"))))

    @Test
    fun `success on first attempt does not touch the fallback`() = runTest {
        val delegate = FakeRouter(listOf(listOf(token("hi"), final("hi"))))
        val out = RetryingAIRouter(delegate, fallback = answers).complete(AIIntent.Text("hi")).toList()
        assertEquals(1, delegate.attemptCount)
        assertEquals(0, answers.attemptCount)
        assertEquals(2, out.size)
    }

    @Test
    fun `5xx gives way to the fallback once`() = runTest {
        val delegate = FakeRouter(listOf(listOf(error(AIError.CloudRejected(503, "down")))))
        val out = RetryingAIRouter(delegate, fallback = answers).complete(AIIntent.Text("hi")).toList()
        assertEquals(1, delegate.attemptCount)
        assertEquals(1, answers.attemptCount)
        assertTrue(out.none { it is AIChunk.ErrorChunk })
    }

    @Test
    fun `5xx from both is two attempts and the fallback's error`() = runTest {
        val delegate = FakeRouter(List(3) { listOf(error(AIError.CloudRejected(503, "down"))) })
        val lite = FakeRouter(List(3) { listOf(error(AIError.CloudRejected(500, "lite down"))) })
        val out = RetryingAIRouter(delegate, fallback = lite).complete(AIIntent.Text("hi")).toList()
        assertEquals(1, delegate.attemptCount)
        assertEquals(1, lite.attemptCount)
        assertEquals(500, ((out.single() as AIChunk.ErrorChunk).cause as AIError.CloudRejected).httpCode)
    }

    @Test
    fun `429, 401, no network and cancel reach the person without a second attempt`() = runTest {
        for (cause in listOf(AIError.CloudRejected(429, "rate"), AIError.CloudRejected(401, "bad key"), AIError.NoNetwork(), AIError.Cancelled())) {
            val delegate = FakeRouter(listOf(listOf(error(cause))))
            val lite = FakeRouter(listOf(listOf(token("hi"), final("hi"))))
            val out = RetryingAIRouter(delegate, fallback = lite).complete(AIIntent.Text("hi")).toList()
            assertEquals("$cause", 1, delegate.attemptCount)
            assertEquals("$cause", 0, lite.attemptCount)
            assertEquals(cause, (out.single() as AIChunk.ErrorChunk).cause)
        }
    }

    @Test
    fun `mid-stream error after tokens passes through without retry`() = runTest {
        val delegate = FakeRouter(listOf(listOf(token("hi "), error(AIError.CloudRejected(503, "cut")))))
        val out = RetryingAIRouter(delegate, fallback = answers).complete(AIIntent.Text("hi")).toList()
        assertEquals(0, answers.attemptCount)
        assertTrue(out[0] is AIChunk.Token)
        assertTrue(out[1] is AIChunk.ErrorChunk)
        assertEquals(2, out.size)
    }

    @Test
    fun `silence past the first try asks the fallback once`() = runTest {
        val delegate = FakeRouter(emptyList(), silent = true)
        val out = RetryingAIRouter(delegate, fallback = answers, clock = EmptyCoroutineContext).complete(AIIntent.Text("hi")).toList()
        assertEquals(1, answers.attemptCount)
        assertEquals("hi", out.filterIsInstance<AIChunk.Final>().single().text)
        assertEquals(RetryingAIRouter.FIRST_TRY_MS, currentTime)
    }
}
