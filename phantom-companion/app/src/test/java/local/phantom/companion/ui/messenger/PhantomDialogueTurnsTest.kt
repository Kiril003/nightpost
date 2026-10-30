package local.phantom.companion.ui.messenger

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import local.phantom.companion.core.ai.AIChunk
import local.phantom.companion.core.ai.AIError
import local.phantom.companion.core.ai.AIIntent
import local.phantom.companion.core.ai.AIIntent.Text.Role
import local.phantom.companion.core.ai.Route
import local.phantom.companion.core.ai.SystemPrompts
import local.phantom.companion.core.constitution.FeatureManifest
import local.phantom.companion.core.net.peer.PeerMessage
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
import kotlin.random.Random

/** Аудит розуму 14.10: №1 (виклики), №2 (питання двічі), №10 (поразки в історії моделі). */
class PhantomDialogueTurnsTest {

    private val me = "me"
    private val bot = AgencySessionManager.PHANTOM_AGENT_ID
    private fun line(from: String, body: String, at: Long) =
        PeerMessage(id = "$from@$at", fromId = from, toId = if (from == me) bot else me, body = body, sentAtMs = at)

    private val notices = AssistantNotices.of(
        listOf("відповіді не прийшло"),
        listOf("хмара відповіла помилкою %1\$d", "Колегія зупинилась на «%1\$s»: %2\$s"),
    )

    @Test
    fun `the question is asked once, not as history and prompt both`() {
        val log = listOf(line(me, "привіт", 1), line(bot, "Привіт!", 2), line(me, "  яка погода? ", 3))

        val turns = DialogueTurns.history(log, me, "яка погода?", notices) { it.body }

        assertEquals(listOf(Role.USER, Role.ASSISTANT), turns.map { it.role })
        assertFalse(turns.any { "погода" in it.content })
    }

    @Test
    fun `failure lines never reach the model as its own words`() {
        val log = listOf(
            line(me, "де укриття", 1).copy(state = PeerMessage.State.FAILED),
            line(bot, "відповіді не прийшло", 2),
            line(me, "а тепер?", 3).copy(state = PeerMessage.State.FAILED),
            line(bot, "хмара відповіла помилкою 400", 4),
            line(bot, "Колегія зупинилась на «Аналітик»: немає мережі", 5),
            line(me, "ну?", 6),
        )

        val turns = DialogueTurns.history(log, me, "ну?", notices) { it.body }

        assertTrue("рядок поразки або питання без відповіді дійшли до моделі: $turns", turns.isEmpty())
    }

    /**
     * Обхід A52 18.10: на «Hi» помічник розповів, чому море солоне. Попереднє
     * питання лишилось без відповіді, і модель відповіла на нього, а не на «Hi».
     */
    @Test
    fun `an unanswered earlier question does not hijack the next one`() {
        val failed = line(me, "Чому море солоне?", 1).copy(state = PeerMessage.State.FAILED)
        val stalled = line(me, "А озера?", 2)
        val later = 2 + DialogueTurns.UNANSWERED_FRESH_MS + 1
        val log = listOf(line(me, "привіт", 0), line(bot, "Привіт.", 0), failed, stalled, line(me, "Hi", later))

        val turns = DialogueTurns.history(log, me, "Hi", notices) { it.body }

        assertEquals(listOf("привіт", "Привіт."), turns.map { it.content })
    }

    @Test
    fun `a fresh line still waiting for its answer stays - it is part of this question`() {
        val log = listOf(line(me, "зустріч завтра", 1_000), line(me, "о 10", 3_000))

        val turns = DialogueTurns.history(log, me, "о 10", notices) { it.body }

        assertEquals(listOf("зустріч завтра"), turns.map { it.content })
    }

    @Test
    fun `two lines before one answer both stay - the answer covered them`() {
        val log = listOf(line(me, "зустріч завтра", 1), line(me, "о 10", 2), line(bot, "Записав на 10:00.", 3), line(me, "дякую", 4))

        val turns = DialogueTurns.history(log, me, "дякую", notices) { it.body }

        assertEquals(listOf("зустріч завтра", "о 10", "Записав на 10:00."), turns.map { it.content })
    }

    @Test
    fun `history starts with the person, not with the model`() {
        val log = listOf(line(bot, "Я Nightpost.", 1), line(me, "привіт", 2), line(bot, "Привіт!", 3), line(me, "ну?", 4))

        val turns = DialogueTurns.history(log, me, "ну?", notices) { it.body }

        assertEquals(Role.USER, turns.first().role)
    }

    @Test
    fun `the follow-up keeps the question before the calls and runs every call`() {
        val intent = AIIntent.Text(prompt = "погода і аптека", history = listOf(AIIntent.Text.Turn(Role.USER, "привіт")))
        val calls = listOf(call("weather.now", "c1"), call("map.nearby", "c2"))

        val next = DialogueTurns.followUp(intent, "дивлюсь", calls, listOf("+12", "Аптека 9"))

        assertEquals("", next.prompt)
        assertEquals(listOf(Role.USER, Role.USER, Role.ASSISTANT, Role.TOOL, Role.TOOL), next.history.map { it.role })
        assertEquals("погода і аптека", next.history[1].content)
        assertEquals(listOf("c1", "c2"), next.history[2].toolCalls!!.map { it.id })
        assertEquals("Аптека 9", next.history[4].content)
        assertEquals("c2", next.history[4].toolCallId)
    }

    @Test
    fun `the model is told which tools it has`() {
        val prompt = DialogueTurns.systemPrompt(
            "Ти Nightpost. Погода — weather.now.",
            listOf(tool("weather.now"), tool("apps.list"), tool("nav.start")),
            "Які додатки у мене встановлені?",
        )

        assertTrue(prompt.startsWith("Ти Nightpost."))
        assertTrue(prompt, "[TOOLS:" in prompt && "weather.now(city)" in prompt && "apps.list(city)" in prompt)
        assertFalse("інструмент, якого не вчить припис і не просить питання", "nav.start" in prompt)
    }

    @Test
    fun `the tools block is a bounded, steady subset, not the whole registry`() {
        val base = SystemPrompts.DEFAULT_PHANTOM
        val taught = Regex("""(?<![\w.])[a-z]+\.[a-z_]+(?![\w])""").findAll(base).map { it.value }.toSet()
        val registry = (taught + "shelter.nearest" + (1..90).map { "spare.tool$it" }).map { tool(it, "опис ".repeat(200)) }
        val ask = "Де найближче укриття?"

        val block = DialogueTurns.systemPrompt(base, registry, ask).removePrefix(base)

        assertTrue("${block.length} символів інструментів у кожному запиті", block.length <= DialogueTurns.TOOLS_BLOCK_MAX_CHARS)
        assertTrue("той, що просить питання, — першим", block.substringAfter("[TOOLS: ").startsWith("shelter.nearest("))
        assertTrue("weather.now(" in block && "reminder.add(" in block)
        assertFalse("spare.tool" in block)
        assertEquals(block, DialogueTurns.systemPrompt(base, registry.shuffled(Random(7)), ask).removePrefix(base))

        val shown = listOf(tool("day.today"), tool("reminder.set"))
        val all = shown + tool("reminder.add")
        val intent = DialogueTurns.messengerIntent(
            prompt = "remind me in 20 minutes to call Daniel",
            history = emptyList(),
            shown = shown,
            all = all,
            appLanguage = "en",
            languageRule = "Reply in English.",
            alias = { if (it == "reminder.add") "reminder.set" else null },
        )
        assertEquals(all.map { it.id }, intent.tools.map { it.name })
        assertTrue(intent.systemPrompt!!.substringAfterLast("[TOOLS: ").startsWith("reminder.set("))
    }

    @Test
    fun `the live row hides a call however the model spaces it`() {
        val fold = SpokenFold()
        fold.feed(AIChunk.Token("Дивлюсь\n{\n  \"type\": \"tool-call\",\n  \"tool\": \"weather.now\"", Route.CLOUD_GEMINI))
        assertEquals("Дивлюсь", fold.shown)

        val fenced = SpokenFold()
        fenced.feed(AIChunk.Token("Гляну.\n```\n{ \"type\": \"tool-call\"", Route.CLOUD_GEMINI))
        assertEquals("Гляну.", fenced.shown)
    }

    @Test
    fun `every call of a turn is kept, and its JSON never becomes the answer`() {
        val raw = """{"type":"tool-call","tool":"weather.now","args":{}}"""
        val fold = SpokenFold()
        listOf(
            AIChunk.Token("Дивлюсь ", Route.CLOUD_GEMINI),
            AIChunk.Token(raw, Route.CLOUD_GEMINI),
            call("weather.now", "c1"),
            call("map.nearby", "c2"),
            AIChunk.Final("", 1, Route.CLOUD_GEMINI, 1),
        ).forEach(fold::feed)

        assertEquals(listOf("c1", "c2"), fold.calls.map { it.callId })
        assertFalse(fold.text, "tool-call" in fold.text)
        assertEquals("Дивлюсь", fold.shown)
    }

    @Test
    fun `a broken stream keeps what was said and names the cause`() {
        val fold = SpokenFold()
        fold.feed(AIChunk.Token("Пів", Route.CLOUD_GEMINI))
        fold.feed(AIChunk.ErrorChunk(cause = AIError.NoNetwork(), route = Route.CLOUD_GEMINI))

        assertEquals("Пів", fold.text)
        assertTrue(fold.failure is AIError.NoNetwork)
    }

    @Test
    fun `formatted failure lines are notices too`() {
        assertTrue(notices("  відповіді   не прийшло "))
        assertTrue(notices("хмара відповіла помилкою 404"))
        assertFalse(notices("хмара відповіла"))
        assertFalse(notices("Привіт!"))
    }

    private fun call(name: String, id: String) = AIChunk.ToolCall(name = name, arguments = "{}", callId = id, route = Route.CLOUD_GEMINI)

    private fun tool(id: String, about: String = "desc"): Tool = object : Tool {
        override val id: String = id
        override val description: String = about
        override val argsSchema: JsonObject = JsonObject(mapOf("city" to JsonPrimitive("string")))
        override val permissions: Set<ToolPermission> = emptySet()
        override val risk: ToolRisk = ToolRisk.values().first()
        override val streaming: Boolean = false
        override val manifest: FeatureManifest = FeatureManifest(id = "tool.$id", tier = 1, displayName = id)
        override suspend fun execute(args: JsonObject, context: ToolContext, emit: suspend (ToolEvent) -> Unit): ToolResult =
            ToolResult.Ok("ok", null, 0L)
    }
}
