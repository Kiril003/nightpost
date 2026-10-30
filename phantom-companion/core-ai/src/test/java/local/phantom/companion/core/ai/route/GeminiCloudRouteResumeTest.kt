package local.phantom.companion.core.ai.route

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import local.phantom.companion.core.ai.AIChunk
import local.phantom.companion.core.ai.AIIntent
import local.phantom.companion.core.ai.Route
import local.phantom.companion.core.ai.withResumePrefix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3.C — verifies [GeminiCloudRoute] offline-queue integration and the
 * [streamOnce] / resume wiring used by AppContainer's StreamResumer wrapper.
 */
class GeminiCloudRouteResumeTest {

    private val testJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun sseHeaders() = headersOf(
        HttpHeaders.ContentType to listOf("text/event-stream"),
    )

    private fun buildSseBody(parts: List<String>): String = buildString {
        for (part in parts) {
            val escaped = part.replace("\"", "\\\"")
            appendLine(
                """data: {"candidates":[{"content":{"role":"model","parts":[{"text":"$escaped"}]}}],"usageMetadata":{"totalTokenCount":1}}"""
            )
            appendLine()
        }
    }

    private fun clientFor(
        handler: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(
            io.ktor.client.request.HttpRequestData
        ) -> io.ktor.client.request.HttpResponseData,
    ): HttpClient {
        return HttpClient(MockEngine { request -> handler(request) }) {
            install(ContentNegotiation) { json(testJson) }
            expectSuccess = false
        }
    }

    private val testIntent = AIIntent.Text(prompt = "resume test")

    // -----------------------------------------------------------------------
    // Happy path: MockEngine returns full SSE → complete() emits tokens + Final
    // -----------------------------------------------------------------------

    @Test
    fun `happy path - full SSE response - tokens and final emitted`() = runTest {
        val parts = listOf("Hello", " world")
        val route = GeminiCloudRoute(
            apiKeyProvider = { "key" },
            httpClient = clientFor {
                respond(buildSseBody(parts), HttpStatusCode.OK, sseHeaders())
            },
        )

        val chunks = route.complete(testIntent).toList()

        val tokens = chunks.filterIsInstance<AIChunk.Token>()
        val finals = chunks.filterIsInstance<AIChunk.Final>()
        val errors = chunks.filterIsInstance<AIChunk.ErrorChunk>()

        assertTrue("no errors expected, got: $errors", errors.isEmpty())
        assertEquals(2, tokens.size)
        assertEquals("Hello", tokens[0].text)
        assertEquals(" world", tokens[1].text)
        assertEquals(1, finals.size)
        assertEquals(Route.CLOUD_GEMINI, finals[0].route)
    }

    // -----------------------------------------------------------------------
    // streamOnce IOException propagates so StreamResumer can retry it
    // -----------------------------------------------------------------------

    @Test
    fun `streamOnce rethrows IOException for StreamResumer retry`() = runTest {
        val route = GeminiCloudRoute(
            apiKeyProvider = { "key" },
            httpClient = clientFor {
                throw java.io.IOException("network error")
            },
        )

        var caughtIo: java.io.IOException? = null
        route.streamOnce(testIntent)
            .catch { t -> if (t is java.io.IOException) caughtIo = t else throw t }
            .toList()

        assertNotNull("streamOnce should rethrow IOException", caughtIo)
    }

    // -----------------------------------------------------------------------
    // Resume prefix: withResumePrefix appends ASSISTANT turn
    // -----------------------------------------------------------------------

    @Test
    fun `withResumePrefix appends assistant turn to history`() {
        val intent = AIIntent.Text(prompt = "hello", history = emptyList())
        val resumed = intent.withResumePrefix("partial answer")

        assertEquals(1, resumed.history.size)
        assertEquals(AIIntent.Text.Role.ASSISTANT, resumed.history[0].role)
        assertTrue(resumed.history[0].content.contains("partial answer"))
    }

    // -----------------------------------------------------------------------
    // offlineQueueSink invoked + Notice + Final[QUEUED] on IOException
    // -----------------------------------------------------------------------

    @Test
    fun `io exception with sink - the turn is queued and the answer stays empty`() = runTest {
        var sinkInvokedWith: AIIntent.Text? = null

        val route = GeminiCloudRoute(
            apiKeyProvider = { "key" },
            httpClient = clientFor {
                throw java.io.IOException("persistent failure")
            },
            offlineQueueSink = { intent -> sinkInvokedWith = intent },
        )

        val chunks = route.complete(testIntent).toList()

        // Sink should have been called with the original intent.
        assertNotNull("offline queue sink should have been invoked", sinkInvokedWith)
        assertEquals(testIntent.prompt, sinkInvokedWith!!.prompt)

        // A Notice followed by a Final[QUEUED] should appear in the stream.
        val notices = chunks.filterIsInstance<AIChunk.Notice>()
        val finals = chunks.filterIsInstance<AIChunk.Final>()

        assertEquals("exactly one Notice expected", 1, notices.size)
        assertEquals(Route.CLOUD_GEMINI, notices[0].route)

        assertEquals("exactly one Final expected", 1, finals.size)
        assertEquals(
            "a queued turn has no answer yet; it used to carry the IOException",
            "",
            finals[0].text,
        )
        assertEquals(Route.CLOUD_GEMINI, finals[0].route)
    }

    // -----------------------------------------------------------------------
    // No sink: IOException surfaces as ErrorChunk (backward-compat)
    // -----------------------------------------------------------------------

    @Test
    fun `io exception without sink - error chunk emitted`() = runTest {
        val route = GeminiCloudRoute(
            apiKeyProvider = { "key" },
            httpClient = clientFor {
                throw java.io.IOException("no sink configured")
            },
        )

        val chunks = route.complete(testIntent).toList()

        val errors = chunks.filterIsInstance<AIChunk.ErrorChunk>()
        assertEquals(1, errors.size)
        assertEquals(Route.CLOUD_GEMINI, errors[0].route)
    }
}
