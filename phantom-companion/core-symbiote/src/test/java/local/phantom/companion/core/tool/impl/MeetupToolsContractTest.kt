package local.phantom.companion.core.tool.impl

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import local.phantom.companion.core.tool.Tool
import local.phantom.companion.core.tool.ToolCallParser
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolPromptInjector
import local.phantom.companion.core.tool.ToolResult
import local.phantom.companion.core.tool.ToolRisk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Стик «модель/правила → інструмент → чернетка у месенджері», без моделі.
 *
 * Найважливіше твердження цього файлу, як і в `people.draft`: інструмент
 * готує чернетку, а НЕ запрошує й нічого не надсилає. «Запросити» натискає
 * людина — так само, як «Оплатити» (помічник чернетки не підписує).
 *
 * Слова інструмента — мовою месенджера, і мову він бере не з себе: слова йдуть
 * з [MeetupWords], який збирає застосунок за `ReplyLanguage`. Тут слова
 * англійські навмисно — щоб було видно, що інструмент не підставляє свої.
 */
class MeetupToolsContractTest {

    private val roster = listOf(
        ReachablePerson("olena01", "Олена", live = true, lastSeenMs = 10L, verified = true),
        ReachablePerson("petro02", "Петро", live = false, lastSeenMs = 5L, verified = true),
        ReachablePerson("marta03", "Марта", live = true, lastSeenMs = 9L, verified = false),
        ReachablePerson("oleh04", "Олег Коваль", live = true, lastSeenMs = 8L, verified = true),
        ReachablePerson("oleh05", "Олег Мельник", live = true, lastSeenMs = 7L, verified = true),
        ReachablePerson("olya06", "Оля", live = true, lastSeenMs = 6L, verified = true),
    )

    /** Слова англійською: що б інструмент не сказав, мусить прийти звідси. */
    private class EnglishWords : MeetupWords {
        override fun drafted(people: List<String>, place: String?, at: String?) =
            "EN drafted [" + people.joinToString("+") + "|" + place + "|" + at + "]"
        override fun emptyDraft() = "EN empty draft"
        override fun notFound(name: String) = "EN not found <$name>"
        override fun which(name: String, candidates: List<String>) = "EN which <$name>: " + candidates.joinToString("/")
        override fun splitOpened(mode: String) = "EN split $mode"
        override fun rosterUnreadable() = "EN roster unreadable"
        override fun draftFailed() = "EN draft failed"
        override fun badMode(mode: String) = "EN bad mode <$mode>"
    }

    private val opened = mutableListOf<MeetupDraftAsk>()

    private fun create(
        people: List<ReachablePerson> = roster,
        open: suspend (MeetupDraftAsk) -> Unit = { opened += it },
    ) = MeetupCreateTool(roster = { people }, open = open, words = { EnglishWords() })

    private fun split(open: suspend (MeetupDraftAsk) -> Unit = { opened += it }) =
        BillSplitTool(open = open, words = { EnglishWords() })

    private fun run(tool: Tool, args: JsonObject): ToolResult = runBlocking { tool.execute(args, fakeContext()) {} }

    private fun meetupArgs(people: List<String>, place: String? = null, at: String? = null) = buildJsonObject {
        put("people", JsonArray(people.map(::JsonPrimitive)))
        place?.let { put("place", it) }
        at?.let { put("at", it) }
    }

    private fun ok(r: ToolResult): ToolResult.Ok {
        assertTrue("мав бути Ok, а був $r", r is ToolResult.Ok)
        return r as ToolResult.Ok
    }

    // ── видимість для моделі ─────────────────────────────────────────────

    @Test
    fun `обидва інструменти видно моделі з їхніми аргументами`() {
        val block = ToolPromptInjector.render(listOf(create(), split()))
        assertTrue("meetup.create не потрапив у підказку: $block", "meetup.create(people,place,at)" in block)
        assertTrue("bill.split не потрапив: $block", "bill.split(mode)" in block)
    }

    @Test
    fun `виклик meetup_create від моделі розбирається`() {
        val raw = """{"type":"tool-call","tool":"meetup.create","args":{"people":["Олену","Петра"],"place":"Cafe X","at":"19:00"}}"""
        val call = ToolCallParser.tryParse(Json.parseToJsonElement(raw) as JsonObject)
        assertEquals("meetup.create", call?.toolId)
    }

    // ── чернетка, а не запрошення ────────────────────────────────────────

    @Test
    fun `чернетка зустрічі відкривається з людьми місцем і часом і НІЧОГО не надсилає`() {
        val result = ok(run(create(), meetupArgs(listOf("Олену", "Петра"), "Cafe X", "19:00")))

        assertEquals(
            "імена мають лягти в чернетку так, як вони в реєстрі, а не як їх сказано",
            listOf(MeetupDraftAsk.Meetup(listOf("Олена", "Петро"), "Cafe X", "19:00")),
            opened,
        )
        val s = result.structured!!
        assertFalse("інструмент не має права казати, що надіслав", s.getValue("sent").jsonPrimitive.boolean)
        assertTrue(s.getValue("draft").jsonPrimitive.boolean)
        assertEquals("EN drafted [Олена+Петро|Cafe X|19:00]", result.output.lines().first())
    }

    @Test
    fun `обидва інструменти читають і не діють поза застосунком`() {
        assertEquals(ToolRisk.READ, create().risk)
        assertEquals(ToolRisk.READ, split().risk)
        assertTrue(create().permissions.isEmpty() && split().permissions.isEmpty())
    }

    @Test
    fun `у файлі інструментів немає жодного шляху до надсилання`() {
        val src = File("src/main/java/local/phantom/companion/core/tool/impl/MeetupTools.kt").readText()
        // Лише код: коментарі кажуть «не надсилає» і мають право згадувати слово.
        val code = src.lines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") || it.trimStart().startsWith("/*") }.joinToString("\n")
        listOf("PeerMessage", "PeerCard", "mesh.", "sendMessage", "ingestDirect", "SolanaLayer", "WalletActivity").forEach {
            assertFalse("MeetupTools не повинні знати про «$it»: чернетка не має дверей до надсилання", it in code)
        }
    }

    // ── імена: відмінки, незнайомі, двоє з одним ──────────────────────────

    @Test
    fun `відмінок не заважає знайти людину`() {
        ok(run(create(), meetupArgs(listOf("Олену", "Петра", "Оленою", "Олі", "Марті"))))
        val asked = opened.single() as MeetupDraftAsk.Meetup
        // «Оленою» — та сама Олена, вдруге її в чернетку не кладуть.
        assertEquals(listOf("Олена", "Петро", "Оля", "Марта"), asked.people)
    }

    @Test
    fun `невідому людину не підміняють першою-ліпшою, а кажуть що не знайшли`() {
        val result = ok(run(create(), meetupArgs(listOf("Олену", "Богдана"), "Cafe X", "19:00")))

        assertTrue("слова про «не знайшов» мають іти з MeetupWords: ${result.output}", "EN not found <Богдана>" in result.output)
        val asked = opened.single() as MeetupDraftAsk.Meetup
        assertTrue("Олена знайдена і лишається в чернетці", "Олена" in asked.people)
        // Незнайоме слово їде далі як сказано: веб покаже його серед «не знайдених» і дасть обрати руками.
        assertTrue("Богдана" in asked.people)
        assertFalse("жодної виписаної особи замість Богдана", asked.people.any { it in setOf("Марта", "Петро", "Оля") })
        assertTrue("у короткому зведенні лише знайдені", "EN drafted [Олена|" in result.output)
        assertEquals(listOf("Богдана"), (result.structured!!.getValue("missing") as JsonArray).map { it.jsonPrimitive.content })
    }

    @Test
    fun `двох Олегів не вгадують`() {
        val result = ok(run(create(), meetupArgs(listOf("Олега"), "Cafe X", "19:00")))

        assertTrue(result.output, "EN which <Олега>: Олег Коваль/Олег Мельник" in result.output)
        val asked = opened.single() as MeetupDraftAsk.Meetup
        assertEquals("без вгадування: у чернетці лишається сказане слово", listOf("Олега"), asked.people)
    }

    @Test
    fun `точне повне імʼя виграє серед схожих`() {
        ok(run(create(), meetupArgs(listOf("Олег Мельник"))))
        assertEquals(listOf("Олег Мельник"), (opened.single() as MeetupDraftAsk.Meetup).people)
    }

    @Test
    fun `люди одним рядком теж читаються`() {
        ok(run(create(), buildJsonObject { put("people", "Олену, Петра і Марту") }))
        assertEquals(listOf("Олена", "Петро", "Марта"), (opened.single() as MeetupDraftAsk.Meetup).people)
    }

    // ── порожнє й поламане ───────────────────────────────────────────────

    @Test
    fun `порожнє прохання відкриває порожню чернетку і не вигадує людей`() {
        val result = ok(run(create(), JsonObject(emptyMap())))
        assertEquals(listOf<MeetupDraftAsk>(MeetupDraftAsk.Meetup(emptyList(), null, null)), opened)
        assertEquals("EN empty draft", result.output.lines().first())
        assertFalse(result.structured!!.getValue("sent").jsonPrimitive.boolean)
    }

    @Test
    fun `порожні рядки місця й часу — це не місце й не час`() {
        ok(run(create(), meetupArgs(listOf("Олену"), place = "  ", at = "")))
        assertEquals(MeetupDraftAsk.Meetup(listOf("Олена"), null, null), opened.single())
    }

    @Test
    fun `реєстр не прочитався — чернетка не відкривається`() {
        val result = run(MeetupCreateTool(roster = { error("диск") }, open = { opened += it }, words = { EnglishWords() }), meetupArgs(listOf("Олену")))
        assertTrue("мав бути Err, а був $result", result is ToolResult.Err)
        assertEquals("EN roster unreadable", (result as ToolResult.Err).message)
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `месенджер не взяв чернетку — інструмент не каже що все готово`() {
        val result = run(create(open = { error("двері не відповіли") }), meetupArgs(listOf("Олену"), "Cafe X", "19:00"))
        assertTrue("мав бути Err, а був $result", result is ToolResult.Err)
        assertEquals("EN draft failed", (result as ToolResult.Err).message)
    }

    // ── bill.split ───────────────────────────────────────────────────────

    @Test
    fun `розділити рахунок відкриває розподіл і нічого не платить`() {
        val result = ok(run(split(), buildJsonObject { put("mode", "claimed") }))
        assertEquals(listOf<MeetupDraftAsk>(MeetupDraftAsk.Split("claimed")), opened)
        assertEquals("EN split claimed", result.output.lines().first())
        val s = result.structured!!
        assertFalse(s.getValue("paid").jsonPrimitive.boolean)
        assertFalse(s.getValue("sent").jsonPrimitive.boolean)
    }

    @Test
    fun `без режиму рахунок ділиться за відмітками, а порівну — лише коли так сказано`() {
        ok(run(split(), JsonObject(emptyMap())))
        ok(run(split(), buildJsonObject { put("mode", "порівну") }))
        ok(run(split(), buildJsonObject { put("mode", "Equal") }))
        assertEquals(
            listOf<MeetupDraftAsk>(MeetupDraftAsk.Split("claimed"), MeetupDraftAsk.Split("equal"), MeetupDraftAsk.Split("equal")),
            opened,
        )
    }

    @Test
    fun `невідомий режим не вгадується`() {
        val result = run(split(), buildJsonObject { put("mode", "половина кожному") })
        assertTrue("мав бути Err, а був $result", result is ToolResult.Err)
        assertEquals("EN bad mode <половина кожному>", (result as ToolResult.Err).message)
        assertTrue(opened.isEmpty())
    }

    private fun fakeContext() = ToolContext(
        appContext = android.app.Application(),
        operatorId = "tester",
        requestId = "req-1",
        approvedPermissions = emptySet(),
    )
}
