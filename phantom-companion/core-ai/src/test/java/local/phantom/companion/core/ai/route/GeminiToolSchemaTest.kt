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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import local.phantom.companion.core.ai.AIIntent
import local.phantom.companion.core.ai.ToolDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHANTOM speaks one tool protocol to Gemini: the JSON described in the
 * system prompt, exchanged as text. The native path was tried and cannot
 * work — a replayed `functionCall` needs a `thought_signature` that a
 * turn we synthesise will never have. These tests hold that line.
 */
class GeminiToolSchemaTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * Operator-visible symptom: every turn that called a tool ended in
     * «хмарний AI: невалідний запит або ключ (HTTP 400)», so no tool-backed
     * card could ever arrive. Two rejections, one after the other:
     *   • «Role 'function' is not supported…»
     *   • «Function call is missing a thought_signature in functionCall
     *     parts» — unfixable by construction, because the functionCall turn
     *     is synthesised by us and no signature was ever issued for it.
     * The tool exchange therefore replays as text, in the shape the system
     * prompt already documents.
     */
    @Test
    fun `a tool-result turn never goes out with the rejected function role`() = runTest {
        var captured: String? = null
        val engine = MockEngine { req: HttpRequestData ->
            captured = (req.body as io.ktor.http.content.TextContent).text
            respond(
                content = ByteReadChannel(
                    """data: {"candidates":[{"content":{"role":"model","parts":[{"text":"ok"}]}}],"usageMetadata":{"totalTokenCount":1}}""" + "\n\n",
                ),
                headers = headersOf(HttpHeaders.ContentType to listOf("text/event-stream")),
            )
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
            expectSuccess = false
        }
        val route = GeminiCloudRoute(apiKeyProvider = { "k" }, httpClient = client)

        route.complete(
            AIIntent.Text(
                prompt = "далі",
                history = listOf(
                    AIIntent.Text.Turn(
                        role = AIIntent.Text.Role.ASSISTANT,
                        content = "",
                        toolCalls = listOf(
                            AIIntent.Text.ToolCall(
                                id = "reminder.add",
                                name = "reminder.add",
                                arguments = """{"text":"полити квіти"}""",
                            ),
                        ),
                    ),
                    AIIntent.Text.Turn(
                        role = AIIntent.Text.Role.TOOL,
                        content = """{"ok":true,"output":"збережено"}""",
                        toolCallId = "reminder.add",
                    ),
                ),
            ),
        ).toList()

        val contents = json.parseToJsonElement(captured!!).jsonObject["contents"]!!.jsonArray
        val roles = contents.map { it.jsonObject["role"]?.jsonPrimitive?.content }
        assertFalse("роль function валить увесь зворот", roles.contains("function"))

        val parts = contents.flatMap { it.jsonObject["parts"]!!.jsonArray.map { p -> p.jsonObject } }
        // A synthesised functionCall can never carry a thought_signature, so
        // it must not be sent at all.
        assertFalse(
            "синтезований functionCall завжди отримає 400 без thought_signature",
            parts.any { it.containsKey("functionCall") || it.containsKey("functionResponse") },
        )
        // The pairing still has to survive, now as text the model can read.
        val texts = parts.mapNotNull { it["text"]?.jsonPrimitive?.content }
        assertTrue(
            "модель мусить бачити, що виклик відбувся",
            texts.any { it.contains("\"tool\":\"reminder.add\"") },
        )
        assertTrue(
            "і мусить бачити результат",
            texts.any { it.contains("[TOOL_RESULT id=reminder.add ok=true output=збережено]") },
        )
    }

    /**
     * Every geo.* result carries a whole `map` object, and the prompt tells the
     * model to hand that object straight back as a widget. Reading `output`
     * with `as? JsonPrimitive` failed on exactly those, and the fallback sent
     * the envelope back as its own output field.
     */
    @Test
    fun `a structured tool output is replayed as itself, not wrapped in its own envelope`() = runTest {
        var captured: String? = null
        val engine = MockEngine { req: HttpRequestData ->
            captured = (req.body as io.ktor.http.content.TextContent).text
            respond(
                content = ByteReadChannel(
                    """data: {"candidates":[{"content":{"role":"model","parts":[{"text":"ok"}]}}],"usageMetadata":{"totalTokenCount":1}}""" + "\n\n",
                ),
                headers = headersOf(HttpHeaders.ContentType to listOf("text/event-stream")),
            )
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
            expectSuccess = false
        }
        val route = GeminiCloudRoute(apiKeyProvider = { "k" }, httpClient = client)

        route.complete(
            AIIntent.Text(
                prompt = "покажи на мапі",
                history = listOf(
                    AIIntent.Text.Turn(
                        role = AIIntent.Text.Role.TOOL,
                        content = """{"ok":true,"output":{"map":{"zoom":15,"markers":[]}}}""",
                        toolCallId = "geo.nearby",
                    ),
                ),
            ),
        ).toList()

        val texts = json.parseToJsonElement(captured!!).jsonObject["contents"]!!.jsonArray
            .flatMap { it.jsonObject["parts"]!!.jsonArray.map { p -> p.jsonObject } }
            .mapNotNull { it["text"]?.jsonPrimitive?.content }
        val line = texts.single { it.startsWith("[TOOL_RESULT") }

        assertEquals("""[TOOL_RESULT id=geo.nearby ok=true output={"map":{"zoom":15,"markers":[]}}]""", line)
    }

    @Test
    fun `tools are never declared natively, only described in the prompt`() = runTest {
        var captured: String? = null
        val engine = MockEngine { req: HttpRequestData ->
            captured = (req.body as io.ktor.http.content.TextContent).text
            respond(
                content = ByteReadChannel(
                    """data: {"candidates":[{"content":{"role":"model","parts":[{"text":"ok"}]}}],"usageMetadata":{"totalTokenCount":1}}""" + "\n\n",
                ),
                headers = headersOf(HttpHeaders.ContentType to listOf("text/event-stream")),
            )
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
            expectSuccess = false
        }
        val route = GeminiCloudRoute(apiKeyProvider = { "k" }, httpClient = client)

        route.complete(
            AIIntent.Text(
                prompt = "постав нагадування",
                tools = listOf(
                    ToolDefinition(
                        name = "reminder.add",
                        description = "Поставити нагадування.",
                        parametersSchema = buildJsonObject { put("text", JsonPrimitive("string")) },
                    ),
                ),
            ),
        ).toList()

        val body = json.parseToJsonElement(captured!!).jsonObject
        val declared = body["tools"]?.jsonArray.orEmpty()
            .any { it.jsonObject.containsKey("functionDeclarations") }
        assertFalse(
            "нативні functionDeclarations — це запрошення відповісти формою, " +
                "яку ми не вміємо відтворити (400 без thought_signature), плюс ~16k зайвих символів",
            declared,
        )
    }
}
