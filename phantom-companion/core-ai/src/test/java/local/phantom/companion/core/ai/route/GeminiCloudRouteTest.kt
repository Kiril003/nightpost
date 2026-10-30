package local.phantom.companion.core.ai.route

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import local.phantom.companion.core.ai.AIChunk
import local.phantom.companion.core.ai.AIError
import local.phantom.companion.core.ai.AIIntent
import local.phantom.companion.core.ai.BrainProfile
import local.phantom.companion.core.ai.BrainProfiles
import local.phantom.companion.core.ai.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

private fun sseHeaders(): Headers = headersOf(
    HttpHeaders.ContentType to listOf("text/event-stream"),
)

private fun jsonHeaders(): Headers = headersOf(
    HttpHeaders.ContentType to listOf("application/json"),
)

/** Builds a minimal Gemini SSE response body with [parts] as text tokens. */
private fun buildSseBody(parts: List<String>): String = buildString {
    for (part in parts) {
        val escaped = part.replace("\"", "\\\"")
        appendLine(
            """data: {"candidates":[{"content":{"role":"model","parts":[{"text":"$escaped"}]}}],"usageMetadata":{"totalTokenCount":1}}"""
        )
        appendLine()
    }
}

private fun buildMockClient(
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
): HttpClient {
    val engine = MockEngine { request -> handler(request) }
    return HttpClient(engine) {
        install(ContentNegotiation) { json(json) }
        expectSuccess = false
    }
}

private val testIntent = AIIntent.Text(prompt = "test prompt")

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

class GeminiCloudRouteTest {

    @Test
    fun `profiles map to current models and never name a shut-down one`() {
        assertEquals(BrainProfiles.DEFAULT.getValue(BrainProfile.ACT), BrainProfiles.modelFor(BrainProfile.ACT))
        val shutDown = setOf("gemini-2.0-flash", "gemini-2.0-flash-lite", "gemini-1.5-flash", "gemini-3.1-flash-lite-preview")
        assertTrue(BrainProfiles.DEFAULT.values.none { it in shutDown })
    }

    @Test
    fun `404 asks only the single profile model and does not walk to another model`() = runTest {
        val asked = mutableListOf<String>()
        val route = GeminiCloudRoute(
            apiKeyProvider = { "k" },
            httpClient = buildMockClient { request ->
                val model = request.url.encodedPath.substringAfter("models/").substringBefore(":")
                asked += model
                respond("{}", HttpStatusCode.NotFound, jsonHeaders())
            },
            modelFor = { "single-model" },
        )
        val out = route.complete(testIntent).toList()
        assertEquals(listOf("single-model"), asked)
        val error = out.single() as AIChunk.ErrorChunk
        assertEquals(404, (error.cause as AIError.CloudRejected).httpCode)
    }

    @Test
    fun `empty API key emits ErrorChunk CloudRejected 401`() = runTest {
        val route = GeminiCloudRoute(
            apiKeyProvider = { null },
            httpClient = buildMockClient { respond("", HttpStatusCode.OK, sseHeaders()) },
        )

        val chunks = route.complete(testIntent).toList()

        assertEquals(1, chunks.size)
        val error = chunks[0] as AIChunk.ErrorChunk
        assertEquals(Route.CLOUD_GEMINI, error.route)
        val cause = error.cause as AIError.CloudRejected
        assertEquals(401, cause.httpCode)
    }

    @Test
    fun `200 SSE with 3 text parts emits 3 Token and 1 Final`() = runTest {
        val parts = listOf("Hello", " world", "!")
        val route = GeminiCloudRoute(
            apiKeyProvider = { "test-api-key" },
            httpClient = buildMockClient {
                respond(buildSseBody(parts), HttpStatusCode.OK, sseHeaders())
            },
        )

        val chunks = route.complete(testIntent).toList()

        val tokens = chunks.filterIsInstance<AIChunk.Token>()
        val finals = chunks.filterIsInstance<AIChunk.Final>()
        val errors = chunks.filterIsInstance<AIChunk.ErrorChunk>()

        assertTrue("expected no errors, got: $errors", errors.isEmpty())
        assertEquals(3, tokens.size)
        assertEquals("Hello", tokens[0].text)
        assertEquals(" world", tokens[1].text)
        assertEquals("!", tokens[2].text)
        assertEquals(1, finals.size)
        assertEquals(Route.CLOUD_GEMINI, finals[0].route)
        assertEquals("Hello world!", finals[0].text)
    }

    @Test
    fun `request budgets enough output tokens`() = runTest {
        // Since we use gemini-3.1-flash-lite, thinkingConfig is omitted
        // and maxOutputTokens is set to GEMINI_OUTPUT_FLOOR (2048) or higher.
        var capturedBody = ""
        val route = GeminiCloudRoute(
            apiKeyProvider = { "test-api-key" },
            httpClient = buildMockClient { request ->
                capturedBody = (request.body as io.ktor.http.content.TextContent).text
                respond(buildSseBody(listOf("ok")), HttpStatusCode.OK, sseHeaders())
            },
        )

        route.complete(AIIntent.Text(prompt = "підсумуй день")).toList()

        val cfg = json.parseToJsonElement(capturedBody).jsonObject["generationConfig"]!!.jsonObject
        val maxOut = cfg["maxOutputTokens"]!!.jsonPrimitive.int
        assertTrue("maxOutputTokens must leave room for answer, was $maxOut", maxOut >= 2048)
        assertFalse("thinkingConfig must be omitted in 1.5 requests", cfg.containsKey("thinkingConfig"))
    }

    @Test
    fun `blank history turns never produce empty-parts contents`() = runTest {
        // Cascade bug: once one reply is blank, an empty assistant turn lands
        // in history. Mapped naively it becomes a GeminiContent with EMPTY
        // parts, which Gemini rejects — so EVERY later request fails the same
        // way («після одного порожнього — всі порожні»). The request builder
        // must drop any turn whose parts would be empty.
        var capturedBody = ""
        val route = GeminiCloudRoute(
            apiKeyProvider = { "test-api-key" },
            httpClient = buildMockClient { request ->
                capturedBody = (request.body as io.ktor.http.content.TextContent).text
                respond(buildSseBody(listOf("ok")), HttpStatusCode.OK, sseHeaders())
            },
        )
        val intent = AIIntent.Text(
            prompt = "далі",
            history = listOf(
                AIIntent.Text.Turn(AIIntent.Text.Role.USER, "привіт"),
                AIIntent.Text.Turn(AIIntent.Text.Role.ASSISTANT, ""), // the poison turn
            ),
        )

        route.complete(intent).toList()

        val contents = json.parseToJsonElement(capturedBody).jsonObject["contents"]!!.jsonArray
        contents.forEach { c ->
            val parts = c.jsonObject["parts"]!!.jsonArray
            assertTrue("a content had empty parts → Gemini rejects → empty-reply cascade", parts.isNotEmpty())
        }
    }

    @Test
    fun `HTTP 429 emits ErrorChunk CloudRejected 429`() = runTest {
        val route = GeminiCloudRoute(
            apiKeyProvider = { "test-api-key" },
            httpClient = buildMockClient {
                respond(
                    """{"error":{"code":429,"message":"quota exceeded"}}""",
                    HttpStatusCode.TooManyRequests,
                    jsonHeaders(),
                )
            },
        )

        val chunks = route.complete(testIntent).toList()

        assertEquals(1, chunks.size)
        val error = chunks[0] as AIChunk.ErrorChunk
        assertEquals(Route.CLOUD_GEMINI, error.route)
        val cause = error.cause as AIError.CloudRejected
        assertEquals(429, cause.httpCode)
    }

    @Test
    fun `network IOException emits ErrorChunk CloudRejected 0`() = runTest {
        val engine = MockEngine {
            throw java.io.IOException("connection refused")
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
            expectSuccess = false
        }
        val route = GeminiCloudRoute(
            apiKeyProvider = { "test-api-key" },
            httpClient = client,
        )

        val chunks = route.complete(testIntent).toList()

        assertEquals(1, chunks.size)
        val error = chunks[0] as AIChunk.ErrorChunk
        assertEquals(Route.CLOUD_GEMINI, error.route)
        val cause = error.cause as AIError.CloudRejected
        assertEquals(0, cause.httpCode)
    }

    @Test
    fun `merges consecutive same-role history turns`() = runTest {
        var capturedBody = ""
        val route = GeminiCloudRoute(
            apiKeyProvider = { "test-api-key" },
            httpClient = buildMockClient { request ->
                capturedBody = (request.body as io.ktor.http.content.TextContent).text
                respond(buildSseBody(listOf("ok")), HttpStatusCode.OK, sseHeaders())
            },
        )

        // Create history turns with consecutive roles:
        // USER -> ASSISTANT -> ASSISTANT (widget/text) -> SYSTEM (becomes USER) -> USER (prompt)
        val history = listOf(
            AIIntent.Text.Turn(role = AIIntent.Text.Role.USER, content = "привіт"),
            AIIntent.Text.Turn(role = AIIntent.Text.Role.ASSISTANT, content = "привіт! ось тобі текст"),
            AIIntent.Text.Turn(role = AIIntent.Text.Role.ASSISTANT, content = "[віджет: таймер — 5хв]"),
            AIIntent.Text.Turn(role = AIIntent.Text.Role.SYSTEM, content = "системне інфо"),
        )
        val intent = AIIntent.Text(
            prompt = "як справи?",
            history = history,
        )

        route.complete(intent).toList()

        // Parse captured body and check contents array
        val req = json.parseToJsonElement(capturedBody).jsonObject
        val contents = req["contents"]!!.jsonArray

        // We expect the resulting contents array to have strictly alternating roles:
        // Turn 1 (user): "привіт"
        // Turn 2 (model): merged parts of "привіт! ось тобі текст" + "[віджет: таймер — 5хв]"
        // Turn 3 (user): merged parts of "системне інфо" (SYSTEM -> user) + "як справи?" (user)
        assertEquals("expected exactly 3 turns after merging", 3, contents.size)

        val turn1 = contents[0].jsonObject
        assertEquals("user", turn1["role"]!!.jsonPrimitive.content)
        assertEquals("привіт", turn1["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)

        val turn2 = contents[1].jsonObject
        assertEquals("model", turn2["role"]!!.jsonPrimitive.content)
        assertEquals(2, turn2["parts"]!!.jsonArray.size)
        assertEquals("привіт! ось тобі текст", turn2["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("[віджет: таймер — 5хв]", turn2["parts"]!!.jsonArray[1].jsonObject["text"]!!.jsonPrimitive.content)

        val turn3 = contents[2].jsonObject
        assertEquals("user", turn3["role"]!!.jsonPrimitive.content)
        assertEquals(2, turn3["parts"]!!.jsonArray.size)
        assertEquals("системне інфо", turn3["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("як справи?", turn3["parts"]!!.jsonArray[1].jsonObject["text"]!!.jsonPrimitive.content)
    }
}

class GeminiCloudRouteQueueTest {

    private val intent = AIIntent.Text(prompt = "що там по погоді")

    @Test
    fun `a queued turn never answers with the exception that caused it`() = runTest {
        var queued: AIIntent.Text? = null
        val route = GeminiCloudRoute(
            apiKeyProvider = { "test-api-key" },
            httpClient = HttpClient(
                MockEngine { throw java.net.UnknownHostException(
                    "Unable to resolve host \"generativelanguage.googleapis.com\"",
                ) },
            ),
            offlineQueueSink = { queued = it },
        )

        val chunks = route.complete(intent).toList()
        val finals = chunks.filterIsInstance<AIChunk.Final>()

        assertEquals(intent, queued)
        assertEquals(1, finals.size)
        assertEquals("", finals[0].text)
        assertFalse(
            "the answer must not carry a hostname or a stack message",
            chunks.any {
                val t = when (it) {
                    is AIChunk.Final -> it.text
                    is AIChunk.Notice -> it.text
                    else -> ""
                }
                t.contains("googleapis") || t.contains("QUEUED") || t.contains("resolve host")
            },
        )
    }

    @Test
    fun `the queue says so in the operator's language`() = runTest {
        val route = GeminiCloudRoute(
            apiKeyProvider = { "test-api-key" },
            httpClient = HttpClient(MockEngine { throw java.io.IOException("boom") }),
            offlineQueueSink = { },
        )

        val notice = route.complete(intent).toList()
            .filterIsInstance<AIChunk.Notice>()
            .single()
        assertEquals(GeminiCloudRoute.QUEUED_NOTICE, notice.text)
    }
}
