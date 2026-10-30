package local.phantom.companion.core.ai.route

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import local.phantom.companion.core.ai.AIChunk
import local.phantom.companion.core.ai.AIError
import local.phantom.companion.core.ai.AIIntent
import local.phantom.companion.core.ai.AIRouter
import local.phantom.companion.core.ai.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrainChainTimeoutTest {

    /** Аудит №8: хвилинне 429 доходить до людини як «зачекай», а не як 504 від сторожа, і без повтору. */
    @Test
    fun `a per-minute 429 reaches the person as 429, not as the watchdog's 504`() = runTest {
        val body = """{"error":{"code":429,"message":"Please retry in 41.2s.",""" +
            """"details":[{"retryDelay":"41s"}]}}"""
        val limited = object : AIRouter {
            override suspend fun availableRoutes(): Set<Route> = setOf(Route.CLOUD_GEMINI)
            override fun complete(intent: AIIntent): Flow<AIChunk> = flow {
                emit(AIChunk.ErrorChunk(cause = AIError.CloudRejected(429, body), route = Route.CLOUD_GEMINI))
            }
        }
        val retrying = RetryingAIRouter(limited)
        val chain = BrainChain(brains = listOf(retrying), firstChunkTimeoutMs = { BrainChain.CLOUD_FIRST_CHUNK_TIMEOUT_MS / 100 })

        val err = chain.complete(AIIntent.Text(prompt = "hi")).toList().single() as AIChunk.ErrorChunk

        assertEquals(429, (err.cause as AIError.CloudRejected).httpCode)
    }

    @Test
    fun `перший шматок відповіді приходить, поки мозок ще говорить`() = runTest {
        val talking = object : AIRouter {
            override suspend fun availableRoutes(): Set<Route> = setOf(Route.NIGHTPOST_SERVER)
            override fun complete(intent: AIIntent): Flow<AIChunk> = flow {
                emit(AIChunk.Token(text = "Небо", route = Route.NIGHTPOST_SERVER))
                awaitCancellation()
            }
        }
        val chain = BrainChain(brains = listOf(talking))
        val first = withContext(Dispatchers.Default) {
            withTimeout(1_000L) { chain.complete(AIIntent.Text(prompt = "Why is the sky blue?")).first() }
        }
        assertEquals("Небо", (first as AIChunk.Token).text)
    }

    @Test
    fun `hanging PC route times out on first chunk and falls through to server route`() = runTest {
        val hangingPc = PcBrainRoute(
            reachable = { true },
            ask = { _, _ -> awaitCancellation() },
        )
        val server = object : AIRouter, LastResort {
            override suspend fun availableRoutes(): Set<Route> = setOf(Route.NIGHTPOST_SERVER)
            override fun complete(intent: AIIntent): Flow<AIChunk> = flow {
                emit(AIChunk.Token(text = "Релеєвське розсіювання.", route = Route.NIGHTPOST_SERVER, isFinal = true))
                emit(AIChunk.Final(text = "Релеєвське розсіювання.", tokensUsed = 3, route = Route.NIGHTPOST_SERVER, durationMs = 10L))
            }
        }
        val chain = BrainChain(
            brains = listOf(hangingPc, server),
            firstChunkTimeoutMs = { route -> if (route == Route.PC_NODE) 300L else 300L },
        )

        val chunks = chain.complete(AIIntent.Text(prompt = "Why is the sky blue?")).toList()

        val final = chunks.filterIsInstance<AIChunk.Final>().single()
        assertEquals("Релеєвське розсіювання.", final.text)
        assertEquals(Route.NIGHTPOST_SERVER, final.route)
    }

    @Test
    fun `hanging memory ground does not block brain chain from answering`() = runTest {
        val hangingMemory = object : MemoryGround {
            override suspend fun dontKnow(query: String): String? = awaitCancellation()
            override suspend fun block(query: String): String? = awaitCancellation()
            override suspend fun cite(shortIds: List<String>): List<String> = emptyList()
        }
        val server = object : AIRouter, LastResort {
            override suspend fun availableRoutes(): Set<Route> = setOf(Route.NIGHTPOST_SERVER)
            override fun complete(intent: AIIntent): Flow<AIChunk> = flow {
                emit(AIChunk.Token(text = "Sky is blue.", route = Route.NIGHTPOST_SERVER, isFinal = true))
                emit(AIChunk.Final(text = "Sky is blue.", tokensUsed = 3, route = Route.NIGHTPOST_SERVER, durationMs = 5L))
            }
        }
        val chain = BrainChain(
            brains = listOf(server),
            memory = hangingMemory,
            memoryTimeoutMs = 200L,
        )

        val chunks = chain.complete(AIIntent.Text(prompt = "Why is the sky blue?", grounded = true)).toList()

        val final = chunks.filterIsInstance<AIChunk.Final>().single()
        assertEquals("Sky is blue.", final.text)
    }

    @Test
    fun `when all brains hang or are empty chain emits single terminal ErrorChunk`() = runTest {
        val hangingPc = object : AIRouter {
            override suspend fun availableRoutes(): Set<Route> = setOf(Route.PC_NODE)
            override fun complete(intent: AIIntent): Flow<AIChunk> = flow {
                awaitCancellation()
            }
        }
        val chain = BrainChain(
            brains = listOf(hangingPc),
            firstChunkTimeoutMs = { 300L },
        )

        val chunks = chain.complete(AIIntent.Text(prompt = "Why is the sky blue?")).toList()

        val err = chunks.filterIsInstance<AIChunk.ErrorChunk>().single()
        assertTrue(err.cause is AIError.PcUnavailable)
    }
}
