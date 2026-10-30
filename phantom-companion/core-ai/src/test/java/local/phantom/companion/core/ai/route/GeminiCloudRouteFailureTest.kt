package local.phantom.companion.core.ai.route

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.ai.AIChunk
import local.phantom.companion.core.ai.AIError
import local.phantom.companion.core.ai.AIIntent
import org.junit.Assert.assertTrue
import org.junit.Test

/** Аудит №9: внутрішній збій — не «хмара відповіла помилкою 0», а скасування — не збій. */
class GeminiCloudRouteFailureTest {

    private fun route(fail: Throwable) = GeminiCloudRoute(
        apiKeyProvider = { "k" },
        httpClient = HttpClient(MockEngine { respond("") }) { expectSuccess = false },
        requestHeaders = { _, _, _ -> throw fail },
    )

    @Test
    fun `an internal failure is Broken, not a cloud code`() = runTest {
        val out = route(IllegalStateException("signer")).streamOnce(AIIntent.Text("p")).toList()

        val cause = (out.single() as AIChunk.ErrorChunk).cause
        assertTrue("$cause", cause is AIError.Broken)
    }

    @Test
    fun `cancellation is rethrown, not turned into an error chunk`() = runTest {
        val thrown = runCatching {
            route(CancellationException("gone")).streamOnce(AIIntent.Text("p")).toList()
        }.exceptionOrNull()

        assertTrue("$thrown", thrown is CancellationException)
    }
}
