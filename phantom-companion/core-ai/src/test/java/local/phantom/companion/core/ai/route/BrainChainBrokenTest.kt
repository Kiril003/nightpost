package local.phantom.companion.core.ai.route

import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.ai.AIChunk
import local.phantom.companion.core.ai.AIError
import local.phantom.companion.core.ai.AIIntent
import local.phantom.companion.core.ai.AIRouter
import local.phantom.companion.core.ai.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrainChainBrokenTest {

    private fun throwing(t: Throwable) = object : AIRouter {
        override suspend fun availableRoutes(): Set<Route> = setOf(Route.NIGHTPOST_SERVER)
        override fun complete(intent: AIIntent): Flow<AIChunk> = flow { throw t }
    }

    private suspend fun causeOf(t: Throwable): AIError =
        BrainChain(brains = listOf(throwing(t)))
            .complete(AIIntent.Text(prompt = "Why is the sky blue?")).toList()
            .filterIsInstance<AIChunk.ErrorChunk>().single().cause

    @Test
    fun `зламаний маршрут не видає себе за відсутню мережу`() = runTest {
        val cause = causeOf(IllegalStateException("signer has no key"))
        assertTrue("got ${cause::class.java.simpleName}", cause is AIError.Broken)
        assertEquals("IllegalStateException: signer has no key", cause.message)
    }

    @Test
    fun `обрив мережі лишається відсутньою мережею`() = runTest {
        assertTrue(causeOf(IOException("reset")) is AIError.NoNetwork)
    }
}
