package local.phantom.companion.core.ai.route

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import local.phantom.companion.core.ai.AIChunk
import local.phantom.companion.core.ai.AIIntent
import local.phantom.companion.core.ai.SystemPrompts
import local.phantom.companion.core.ai.ToolDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A52: англійське питання діставало українську відповідь, бо інструкції моделі —
 * українською. Правило мови стоїть ОСТАННІМ рядком системного припису, і цей тест
 * дивиться на запит, що справді виходить з телефона: після блоку памʼяті (його
 * дописує BrainChain), правил інструментів і контексту (їх дописує GeminiCloudRoute).
 * Тест на сам `replyLanguage()` цього не бачить.
 */
class LanguageRuleIsLastTest {

    private val memory = object : MemoryGround {
        override suspend fun dontKnow(query: String): String? = null
        override suspend fun block(query: String) = "${MemoryGround.HEADER_MARK} — записи]\n- [m:3fa2c1d0] У тебе є пес Бублик"
        override suspend fun cite(shortIds: List<String>) = emptyList<String>()
    }

    private fun tool() = ToolDefinition("draft_meetup", "d", buildJsonObject { })

    private suspend fun systemSentFor(intent: AIIntent.Text): String {
        var body = ""
        val http = HttpClient(MockEngine { req ->
            body = (req.body as TextContent).text
            respond(
                """data: {"candidates":[{"content":{"role":"model","parts":[{"text":"ok"}]}}]}""" + "\n\n",
                HttpStatusCode.OK,
                headersOf("Content-Type", "text/event-stream"),
            )
        }) { expectSuccess = false }
        val chain = BrainChain(listOf(GeminiCloudRoute(apiKeyProvider = { "fake-own-key" }, httpClient = http)), memory = memory)
        val out = chain.complete(intent).toList()
        assertTrue(out.toString(), out.any { it is AIChunk.Final })
        val doc: JsonObject = Json.parseToJsonElement(body).jsonObject
        return doc["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
    }

    @Test
    fun `правило мови — останнє в запиті, після памʼяті, правил інструментів і контексту`() = runTest {
        val rule = SystemPrompts.languageRule("what can you do", appLanguage = "uk")
        val sys = systemSentFor(
            AIIntent.Text(
                prompt = "what can you do",
                systemPrompt = SystemPrompts.DEFAULT_PHANTOM + "\n" + "Ти відповідаєш у месенджері.",
                tools = listOf(tool()),
                context = "[LOCATION:49.8,24.0]",
                grounded = true,
                languageRule = rule,
            ),
        )

        assertTrue(sys.takeLast(300), sys.trimEnd().endsWith(rule))
        assertEquals("правило мови сказано двічі", 1, Regex(Regex.escape(rule)).findAll(sys).count())
        val at = sys.lastIndexOf(rule)
        listOf(MemoryGround.HEADER_MARK, "ПОТОЧНИЙ КОНТЕКСТ", "[LOCATION:49.8,24.0]").forEach {
            assertTrue("«$it» стоїть після правила мови", sys.indexOf(it) in 0 until at)
        }
    }

    @Test
    fun `без правила запит такий, як був - байт у байт`() = runTest {
        val sys = systemSentFor(AIIntent.Text(prompt = "x", systemPrompt = "Ти PHANTOM.", grounded = true))
        assertTrue(sys, sys.startsWith("Ти PHANTOM.") && !sys.contains("LANGUAGE:") && !sys.contains("МОВА:"))
    }
}
