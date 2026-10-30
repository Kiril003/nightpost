package local.phantom.companion.ui.messenger

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.io.File
import java.util.Locale
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import local.phantom.companion.core.ai.AIIntent
import local.phantom.companion.core.ai.AIIntent.Text.Role
import local.phantom.companion.core.ai.AIIntent.Text.Turn
import local.phantom.companion.core.ai.SystemPrompts
import local.phantom.companion.core.ai.ToolDefinition
import local.phantom.companion.core.ai.prompt.NightpostPrompt
import local.phantom.companion.core.ai.route.GeminiCloudRoute
import local.phantom.companion.core.constitution.FeatureManifest
import local.phantom.companion.core.data.locale.AppLanguage
import local.phantom.companion.core.tool.Tool
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolEvent
import local.phantom.companion.core.tool.ToolPermission
import local.phantom.companion.core.tool.ToolResult
import local.phantom.companion.core.tool.ToolRisk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Card A3: what one hop of the Nightpost chat really sends. The body is the one
 * GeminiCloudRoute builds, captured by a fake engine; the registry is the real
 * id/description text read from the sources (the registry itself needs an Android container).
 */
class MessengerPromptHopTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: error("repo root not found")
    }

    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8).size

    private val registry: List<Tool> by lazy {
        val sources = listOf("core-symbiote/src/main", "app/src/main").flatMap { dir ->
            File(repoRoot(), dir).walkTopDown().filter { it.isFile && it.extension == "kt" }.map { it.readText() }.toList()
        }
        val id = Regex("""override val id\s*(?::\s*String\s*)?=\s*"([^"]+)"""")
        val description = Regex("""override val description\s*(?::\s*String\s*)?=\s*(.*?)\n\s*override val""", RegexOption.DOT_MATCHES_ALL)
        val literal = Regex("\"\"\"(.*?)\"\"\"|\"((?:[^\"\\\\]|\\\\.)*)\"", RegexOption.DOT_MATCHES_ALL)
        val found = linkedMapOf<String, String>()
        for (text in sources) for (m in id.findAll(text)) {
            val tail = text.substring(m.range.last + 1, minOf(text.length, m.range.last + 6000))
            val expr = description.find(tail)?.groupValues?.get(1) ?: continue
            found[m.groupValues[1]] = literal.findAll(expr).joinToString("") { it.groupValues[1].ifEmpty { it.groupValues[2] } }
        }
        assertTrue("registry read from sources: ${found.size} tools", found.size > 60)
        found.map { (tid, d) -> tool(tid, d) }
    }

    private val history = listOf(
        Turn(Role.USER, "remind me to call Daniel"),
        Turn(Role.ASSISTANT, "Reminder set for 18:00."),
        Turn(Role.USER, "and Olena"),
        Turn(Role.ASSISTANT, "Added."),
    )
    private val ask = "what's on today?"
    private val rule = SystemPrompts.languageRule(ask, "en", listOf("remind me to call Daniel", "and Olena"))

    private fun hopBody(intent: AIIntent.Text): String {
        var body = ""
        val client = HttpClient(
            MockEngine { request ->
                body = (request.body as TextContent).text
                respond(
                    """data: {"candidates":[{"content":{"role":"model","parts":[{"text":"ok"}]}}]}""" + "\n\n",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "text/event-stream"),
                )
            },
        )
        runBlocking { GeminiCloudRoute(apiKeyProvider = { "test-key" }, httpClient = client).complete(intent).toList() }
        return body
    }

    private fun legacyIntent(): AIIntent.Text = AIIntent.Text(
        prompt = ask,
        systemPrompt = DialogueTurns.systemPrompt(
            SystemPrompts.DEFAULT_PHANTOM + "\nТи відповідаєш у месенджері Nightpost як цифровий симбіонт. Відповідай чітко, корисно, технічно грамотно.",
            registry,
            ask,
        ),
        history = history,
        tools = registry.map { ToolDefinition(it.id, it.description, it.argsSchema) },
        maxTokens = 1024,
        temperature = 0.7f,
        grounded = true,
        languageRule = rule,
    )

    private fun product(tools: List<Tool>) = local.phantom.companion.assistant.ProductToolset(tools)

    private fun messenger(tools: List<Tool> = registry, text: String = ask, lang: String = "en") =
        DialogueTurns.messengerIntent(
            prompt = text,
            history = history,
            shown = product(tools).offered,
            all = tools,
            appLanguage = lang,
            languageRule = rule,
            alias = { id -> product(tools).resolve(id)?.id },
        )

    @Test
    fun `the messenger turn carries NightpostPrompt in the app language, not the old prompt`() {
        AppLanguage.offered.forEach { lang ->
            val system = messenger(lang = lang.tag).systemPrompt!!
            assertTrue("${lang.tag}: starts with NightpostPrompt", system.startsWith("You are the Nightpost assistant"))
            assertTrue(system.startsWith(NightpostPrompt.forLanguage(lang.tag)))
            assertTrue("${lang.tag}: names its language", lang.locale.getDisplayLanguage(Locale.ENGLISH) in system)
            assertFalse("the old prompt leaked", "Ти — Nightpost, цифровий симбіонт" in system)
        }
    }

    @Test
    fun `the tools text is a bounded product subset and the registry stays whole`() {
        val intent = messenger(text = "remind me in 20 minutes to call Daniel")
        assertTrue("[TOOLS: " in intent.systemPrompt!!)
        val block = "[TOOLS: " + intent.systemPrompt!!.substringAfterLast("[TOOLS: ")
        assertTrue("${bytes(block)} bytes of tools", bytes(block) <= DialogueTurns.MESSENGER_TOOLS_MAX_BYTES)
        assertTrue("reminder.set(" in block)
        val offeredIds = product(registry).offered.map { it.id }.toSet()
        val blockIds = Regex("""([a-z][a-z0-9_.]*)\(""").findAll(block).map { it.groupValues[1] }.toList()
        assertEquals("unoffered ids in block", emptyList<String>(), blockIds.filter { it !in offeredIds })
        val shelter = messenger(text = "Де найближче укриття?").systemPrompt!!.substringAfterLast("[TOOLS: ")
        assertTrue("the tool the rules ask for goes first: ${shelter.take(40)}", shelter.startsWith("shelter.nearest("))
        listOf("accessibility(", "screen.read(", "canvas.build(", "shell(", "world.aircraft(").forEach {
            assertFalse("«$it» in the chat tools", it in block)
        }
        assertEquals("the rules still see every tool", registry.map { it.id }, intent.tools.map { it.name })

        val hostile = registry.map { tool(it.id, "я".repeat(2_000)) }
        val capped = "[TOOLS: " + messenger(tools = hostile).systemPrompt!!.substringAfterLast("[TOOLS: ")
        assertTrue("${bytes(capped)} bytes with long descriptions", bytes(capped) <= DialogueTurns.MESSENGER_TOOLS_MAX_BYTES)
    }

    @Test
    fun `the rules tool goes first by its product id`() {
        val body = messenger(text = "remind me in 20 minutes to call Daniel")
            .systemPrompt!!
            .substringAfterLast("[TOOLS: ")
        assertTrue("expected reminder.set( first, got: ${body.take(40)}", body.startsWith("reminder.set("))
    }

    @Test
    fun `one messenger hop stays under 8 KB, the old one was an order larger`() {
        val after = bytes(hopBody(messenger()))
        val before = bytes(hopBody(legacyIntent()))
        println("HOP BYTES before=$before after=$after")
        // 3 KB prompt + ~1 KB parallel-tools clause + ~0.3 KB language rule + 3 KB tools + turns and framing.
        assertTrue("$after bytes per hop", after <= 8 * 1024)
        assertTrue("before=$before after=$after", before >= 6 * after)
    }

    @Test
    fun `the chat turn is built by messengerIntent`() {
        val src = File(repoRoot(), "app/src/main/java/local/phantom/companion/ui/messenger/AgencySessionManager.kt").readText()
        val dialogue = src.substringAfter("fun dispatchPhantomDialogue(").substringBefore("private suspend fun stream(")
        assertTrue("dispatchPhantomDialogue does not use messengerIntent", "DialogueTurns.messengerIntent(" in dialogue)
        assertFalse("dispatchPhantomDialogue still builds the old prompt", "DEFAULT_PHANTOM" in dialogue)
    }

    private fun tool(id: String, about: String): Tool = object : Tool {
        override val id: String = id
        override val description: String = about
        override val argsSchema: JsonObject = JsonObject(mapOf("query" to JsonPrimitive("string"), "limit" to JsonPrimitive("number")))
        override val permissions: Set<ToolPermission> = emptySet()
        override val risk: ToolRisk = ToolRisk.values().first()
        override val streaming: Boolean = false
        override val manifest: FeatureManifest = FeatureManifest(id = "tool.$id", tier = 1, displayName = id)
        override suspend fun execute(args: JsonObject, context: ToolContext, emit: suspend (ToolEvent) -> Unit): ToolResult =
            ToolResult.Ok("ok", null, 0L)
    }
}
