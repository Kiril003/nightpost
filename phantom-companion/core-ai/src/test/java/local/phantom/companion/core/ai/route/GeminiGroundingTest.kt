package local.phantom.companion.core.ai.route

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import local.phantom.companion.core.ai.AIChunk
import local.phantom.companion.core.ai.AIIntent
import local.phantom.companion.core.ai.ToolDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R1.3 — pins Google-Search grounding: the `google_search` tool entry
 * in the request (mixed with function declarations) and the
 * `groundingMetadata` → [AIChunk.Grounding] emission on the response.
 */
class GeminiGroundingTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun mockedRoute(
        sseBody: String,
        capture: (String) -> Unit = {},
    ): GeminiCloudRoute {
        val engine = MockEngine { req: HttpRequestData ->
            capture((req.body as io.ktor.http.content.TextContent).text)
            respond(
                content = ByteReadChannel(sseBody),
                headers = headersOf(HttpHeaders.ContentType to listOf("text/event-stream")),
            )
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
            expectSuccess = false
        }
        return GeminiCloudRoute(apiKeyProvider = { "k" }, httpClient = client)
    }

    private val plainOkSse =
        """data: {"candidates":[{"content":{"role":"model","parts":[{"text":"ok"}]}}],"usageMetadata":{"totalTokenCount":1}}""" + "\n\n"

    @Test
    fun `google_search is the only tools entry, even when tools are passed`() = runTest {
        var captured: String? = null
        val route = mockedRoute(plainOkSse) { captured = it }
        route.complete(
            AIIntent.Text(
                prompt = "новини?",
                // webSearch defaults to false now (grounding is opt-in to
                // protect the separate free-tier grounding quota); this
                // test specifically pins the on-path, so enable it.
                webSearch = true,
                tools = listOf(
                    ToolDefinition(
                        name = "http",
                        description = "fetch",
                        parametersSchema = buildJsonObject { put("url", JsonPrimitive("string")) },
                    ),
                ),
            ),
        ).toList()

        val tools = json.parseToJsonElement(captured!!).jsonObject["tools"]!!.jsonArray
        // PHANTOM's own tools live in the prompt, never in `tools` — passing
        // them must not add an entry. Grounding is the only thing declared.
        assertEquals(1, tools.size)
        val searchEntry = tools[0].jsonObject
        assertTrue(searchEntry.containsKey("google_search"))
        assertFalse(searchEntry.containsKey("functionDeclarations"))
    }

    @Test
    fun `webSearch=false omits google_search and empty tools stays null`() = runTest {
        var captured: String? = null
        val route = mockedRoute(plainOkSse) { captured = it }
        route.complete(AIIntent.Text(prompt = "привіт", webSearch = false)).toList()
        assertNull(json.parseToJsonElement(captured!!).jsonObject["tools"])
    }

    @Test
    fun `groundingMetadata emits one deduped Grounding before Final`() = runTest {
        val sse = buildString {
            append("""data: {"candidates":[{"content":{"role":"model","parts":[{"text":"відповідь"}]},"groundingMetadata":{"groundingChunks":[{"web":{"uri":"https://a.example/1","title":"Джерело А"}},{"web":{"uri":"https://b.example/2","title":"Джерело Б"}}]}}]}""")
            append("\n\n")
            // Same URL again in a later chunk — must dedupe.
            append("""data: {"candidates":[{"content":{"role":"model","parts":[{"text":"!"}]},"groundingMetadata":{"groundingChunks":[{"web":{"uri":"https://a.example/1","title":"Джерело А (дубль)"}}]}}],"usageMetadata":{"totalTokenCount":2}}""")
            append("\n\n")
        }
        val route = mockedRoute(sse)
        val chunks = route.complete(AIIntent.Text(prompt = "новини?")).toList()

        val groundings = chunks.filterIsInstance<AIChunk.Grounding>()
        assertEquals(1, groundings.size)
        val citations = groundings.single().citations
        assertEquals(2, citations.size)
        assertEquals("Джерело А", citations[0].title)
        assertEquals("https://a.example/1", citations[0].url)
        assertEquals("Джерело Б", citations[1].title)
        // Grounding arrives before Final.
        val order = chunks.map { it::class.simpleName }
        assertTrue(order.indexOf("Grounding") < order.indexOf("Final"))
    }

    @Test
    fun `no groundingMetadata means no Grounding chunk`() = runTest {
        val route = mockedRoute(plainOkSse)
        val chunks = route.complete(AIIntent.Text(prompt = "привіт")).toList()
        assertTrue(chunks.filterIsInstance<AIChunk.Grounding>().isEmpty())
    }
}
