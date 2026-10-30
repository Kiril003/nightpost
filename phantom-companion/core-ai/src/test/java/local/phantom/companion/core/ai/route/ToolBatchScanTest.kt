package local.phantom.companion.core.ai.route

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import local.phantom.companion.core.ai.AIChunk
import local.phantom.companion.core.ai.AIIntent
import local.phantom.companion.core.ai.ToolDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One turn, several tools. The provider cannot express this natively here (see
 * [GeminiToolSchemaTest] for why `functionDeclarations` stay off), so the only
 * carrier is the text protocol — and the reader on the other side takes the
 * first object only, which is how calls 2..n used to disappear.
 */
class ToolBatchScanTest {

    private val tool = ToolDefinition("weather.now", "погода", buildJsonObject { })

    @Test
    fun `two calls in one answer are both found and the prose keeps the rest`() {
        val scan = ToolBatchScan.of(
            "дивлюсь\n" +
                """{"type":"tool-call","tool":"weather.now","args":{"city":"Київ"}}""" +
                """{"type":"tool-call","tool":"geo.nearby","args":{"category":"pharmacy"}}""",
        )
        assertEquals(listOf("weather.now", "geo.nearby"), scan.calls.map { it.name })
        assertEquals("""{"city":"Київ"}""", scan.calls[0].args)
        assertEquals("дивлюсь", scan.prose)
    }

    @Test
    fun `a widget object next to a call is left alone`() {
        val scan = ToolBatchScan.of(
            """{"type":"tool-call","tool":"a","args":{}}{"type":"map","payload":{"zoom":12}}""" +
                """{"type":"tool-call","tool":"b","args":{}}""",
        )
        assertEquals(listOf("a", "b"), scan.calls.map { it.name })
        assertTrue(scan.prose.contains("\"type\":\"map\""))
    }

    /** Same line as LocalToolCall holds: firing with {} would run the tool on
     *  arguments the model never gave, and nothing validates them downstream. */
    @Test
    fun `a call whose args are not an object is not a call`() {
        val scan = ToolBatchScan.of(
            """{"type":"tool-call","tool":"a","args":"x"}{"type":"tool-call","tool":"b","args":{}}""",
        )
        assertEquals(listOf("b"), scan.calls.map { it.name })
    }

    @Test
    fun `the route emits one ToolCall per call and strips them from the answer`() = runTest {
        val body = """{"type":"tool-call","tool":"weather.now","args":{}}""" +
            """{"type":"tool-call","tool":"location.current","args":{}}"""
        val chunks = routeChunks(body, tools = listOf(tool))

        val calls = chunks.filterIsInstance<AIChunk.ToolCall>()
        assertEquals(listOf("weather.now", "location.current"), calls.map { it.name })
        assertEquals(2, calls.map { it.callId }.toSet().size)
        // Left in the final text, the consumer would mint the first call a
        // SECOND time from the same answer.
        val final = chunks.filterIsInstance<AIChunk.Final>().single()
        assertFalse(final.text.contains("tool-call"))
    }

    /** Аудит №1: тексту, що виконав би одиночний виклик, у месенджері немає — JSON лягав у чат. */
    @Test
    fun `a single call becomes a ToolCall and leaves the answer`() = runTest {
        val body = "дивлюсь\n" + """{"type":"tool-call","tool":"weather.now","args":{"city":"Київ"}}"""
        val chunks = routeChunks(body, tools = listOf(tool))

        val call = chunks.filterIsInstance<AIChunk.ToolCall>().single()
        assertEquals("weather.now", call.name)
        assertEquals("""{"city":"Київ"}""", call.arguments)
        val final = chunks.filterIsInstance<AIChunk.Final>().single()
        assertFalse(final.text.contains("tool-call"))
        assertEquals("дивлюсь", final.text)
    }

    @Test
    fun `a turn with no tools is never re-read as a batch`() = runTest {
        val body = """{"type":"tool-call","tool":"a","args":{}}{"type":"tool-call","tool":"b","args":{}}"""
        val chunks = routeChunks(body, tools = emptyList())

        assertTrue(chunks.filterIsInstance<AIChunk.ToolCall>().isEmpty())
    }

    /** The model can only batch what it was told it may batch — and a turn
     *  without tools must not pay for a protocol it cannot use. */
    @Test
    fun `the batch protocol is sent with tools and withheld without them`() = runTest {
        val sent = mutableListOf<String>()
        routeChunks("ок", tools = listOf(tool), captureBody = sent::add)
        routeChunks("ок", tools = emptyList(), captureBody = sent::add)

        assertTrue(sent[0].contains("ПАРАЛЕЛЬНІ ІНСТРУМЕНТИ"))
        assertFalse(sent[1].contains("ПАРАЛЕЛЬНІ ІНСТРУМЕНТИ"))
    }

    private suspend fun routeChunks(
        answer: String,
        tools: List<ToolDefinition>,
        captureBody: (String) -> Unit = {},
    ): List<AIChunk> {
        val text = kotlinx.serialization.json.JsonPrimitive(answer).toString()
        val sse = """data: {"candidates":[{"content":{"role":"model","parts":[{"text":$text}]}}],""" +
            """"usageMetadata":{"totalTokenCount":1}}""" + "\n\n"
        val client = HttpClient(MockEngine { request ->
            captureBody((request.body as io.ktor.http.content.TextContent).text)
            respond(
                content = ByteReadChannel(sse),
                headers = headersOf(HttpHeaders.ContentType to listOf("text/event-stream")),
            )
        }) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            expectSuccess = false
        }
        val route = GeminiCloudRoute(apiKeyProvider = { "k" }, httpClient = client)
        return route.complete(AIIntent.Text(prompt = "p", tools = tools)).toList()
    }
}
