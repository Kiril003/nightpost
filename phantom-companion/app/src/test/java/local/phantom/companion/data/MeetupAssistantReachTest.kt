package local.phantom.companion.data

import java.io.File
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import local.phantom.companion.R
import local.phantom.companion.core.ai.AIChunk
import local.phantom.companion.core.ai.AIIntent
import local.phantom.companion.core.ai.ToolDefinition
import local.phantom.companion.core.ai.route.BrainChain
import local.phantom.companion.core.tool.Tool
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolResult
import local.phantom.companion.core.tool.impl.BillSplitTool
import local.phantom.companion.core.tool.impl.MeetupCreateTool
import local.phantom.companion.core.tool.impl.ReachablePerson
import local.phantom.companion.ui.messenger.WebMessengerMeetupDraft
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * «Збери Олену й Петра в Cafe X о 19:00» і «розділи рахунок» — від слів людини
 * до чернетки в черзі месенджера, без моделі й без мережі.
 *
 * Дорога: правила (`BrainChain` без розумів) → виклик інструмента → справжній
 * [MeetupCreateTool]/[BillSplitTool] зі справжніми словами з файлів ресурсів →
 * `WebMessengerMeetupDraft.asked` → черга, яку забирає хост і штовхає у веб
 * (`SceneBridgeReachTest`). Фабрика реєстру збирає інструменти з тих самих
 * частин — це тримає сторож по джерелу нижче.
 */
class MeetupAssistantReachTest {

    private val roster = listOf(
        ReachablePerson("olena01", "Олена", live = true, lastSeenMs = 10L, verified = true),
        ReachablePerson("petro02", "Петро", live = false, lastSeenMs = 5L, verified = true),
        ReachablePerson("marta03", "Марта", live = true, lastSeenMs = 9L, verified = false),
    )

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "app/src/main/java").isDirectory }

    private fun res(path: String) = File(root, "app/src/main/res/$path")

    @Before
    @After
    fun clearQueue() {
        WebMessengerMeetupDraft.pending.value = null
    }

    // ── слова з файлів ресурсів, такі, якими їх побачить людина ─────────────

    private fun strings(dir: String): Map<String, String> {
        val rx = Regex("""<string name="([^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        return listOf("strings_meetup_assistant.xml", "strings_money.xml").flatMap { file ->
            rx.findAll(res("$dir/$file").readText()).map { m ->
                m.groupValues[1] to m.groupValues[2]
                    .replace("\\'", "'").replace("\\\"", "\"").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            }.toList()
        }.toMap()
    }

    private val names: Map<Int, String> by lazy {
        R.string::class.java.fields.filter { it.type == Int::class.javaPrimitiveType }.associate { it.getInt(null) to it.name }
    }

    private fun words(dir: String): MeetupAssistantWords {
        val texts = strings(dir)
        return MeetupAssistantWords { id, args -> String.format(texts.getValue(names.getValue(id)), *args) }
    }

    // ── дорога ───────────────────────────────────────────────────────────

    private fun offered(vararg ids: String) = ids.map { ToolDefinition(it, "", JsonObject(emptyMap())) }

    /** Що правила кличуть, коли розумів нема взагалі — як офлайн на телефоні. */
    private fun heard(prompt: String): AIChunk.ToolCall = runBlocking {
        BrainChain(emptyList()).complete(AIIntent.Text(prompt, tools = offered("meetup.create", "bill.split", "map.nearby")))
            .toList().filterIsInstance<AIChunk.ToolCall>().singleOrNull()
    } ?: error("правила не покликали жодного інструмента на «$prompt»")

    private fun run(tool: Tool, call: AIChunk.ToolCall): ToolResult = runBlocking {
        tool.execute(
            Json.parseToJsonElement(call.arguments).jsonObject,
            ToolContext(android.app.Application(), "tester", "req-1", approvedPermissions = emptySet()),
        ) {}
    }

    private fun meetupTool(dir: String) = MeetupCreateTool(
        roster = { roster },
        open = { WebMessengerMeetupDraft.asked(it) },
        words = { words(dir) },
    )

    private fun splitTool(dir: String) = BillSplitTool(open = { WebMessengerMeetupDraft.asked(it) }, words = { words(dir) })

    @Test
    fun `офлайн фраза про зустріч доходить до чернетки в черзі месенджера`() {
        val call = heard("збери Олену й Петра в Cafe X о 19:00")
        assertEquals("meetup.create", call.name)

        val result = run(meetupTool("values-uk"), call)

        assertTrue("мав бути Ok, а був $result", result is ToolResult.Ok)
        assertEquals(
            "двері до веба: люди з реєстру, заклад і час як сказано",
            """{"people":["Олена","Петро"],"place":"Cafe X","at":"19:00"}""",
            WebMessengerMeetupDraft.pending.value?.json,
        )
        val said = (result as ToolResult.Ok).output
        assertTrue(said, said.startsWith("Чернетка зустрічі: Олена, Петро · Cafe X · 19:00."))
        assertTrue("відповідь має відправити людину в месенджер, а не запрошувати самій: $said", "Перевірте в месенджері" in said)
    }

    @Test
    fun `невідомого не знайшов — мовою месенджера, а відомі лишаються в чернетці`() {
        val call = heard("збери Олену й Богдана в Cafe X о 19:00")
        val uk = (run(meetupTool("values-uk"), call) as ToolResult.Ok).output
        assertTrue(uk, "Не знайшов «Богдана» серед людей у ваших розмовах." in uk)
        assertTrue(WebMessengerMeetupDraft.pending.value!!.json.contains("\"Олена\""))

        val en = (run(meetupTool("values"), call) as ToolResult.Ok).output
        assertTrue(en, "I couldn't find “Богдана” among the people in your chats." in en)
        assertFalse("англійська відповідь не мусить мати українського «не знайшов»: $en", "Не знайшов" in en)
    }

    @Test
    fun `чого не сказали, те лишається порожнім і названо порожнім`() {
        val uk = (run(meetupTool("values-uk"), heard("збери Олену й Петра о 19:00")) as ToolResult.Ok).output
        assertTrue(uk, "Олена, Петро · 19:00" in uk && "Ще порожньо: місце." in uk)
        assertEquals("""{"people":["Олена","Петро"],"at":"19:00"}""", WebMessengerMeetupDraft.pending.value?.json)

        val en = (run(meetupTool("values"), heard("збери Олену й Петра о 19:00")) as ToolResult.Ok).output
        assertTrue(en, "Still empty: place." in en)
    }

    @Test
    fun `розділи рахунок відкриває розподіл і нічого не платить`() {
        val call = heard("розділи рахунок")
        assertEquals("bill.split", call.name)
        val result = run(splitTool("values-uk"), call) as ToolResult.Ok
        assertEquals("""{"split":"claimed"}""", WebMessengerMeetupDraft.pending.value?.json)
        assertTrue(result.output, "оплачує своє" in result.output)
        assertTrue(result.structured.toString(), "\"paid\":false" in result.structured.toString())
    }

    @Test
    fun `порівну чесно каже, що окремого режиму ще немає`() {
        val call = heard("розділи рахунок порівну")
        val uk = (run(splitTool("values-uk"), call) as ToolResult.Ok).output
        assertEquals("""{"split":"equal"}""", WebMessengerMeetupDraft.pending.value?.json)
        assertTrue(uk, "відмітьте всі страви" in uk)
        val en = (run(splitTool("values"), call) as ToolResult.Ok).output
        assertTrue(en, "tick all items to split equally" in en)
    }

    // ── реєстр, ключі, слова ──────────────────────────────────────────────

    @Test
    fun `реєстр віддає обидва інструменти справжніми дверима й словами мовою месенджера`() {
        val factory = File(root, "app/src/main/java/local/phantom/companion/data/ToolRegistryFactory.kt").readText()
        val create = factory.substringAfter("core.tool.impl.MeetupCreateTool(").substringBefore("core.tool.impl.BillSplitTool(")
        val bill = factory.substringAfter("core.tool.impl.BillSplitTool(").substringBefore("core.tool.impl.PhoneAlarmTool(")
        assertTrue("meetup.create не в реєстрі", factory.contains("core.tool.impl.MeetupCreateTool("))
        assertTrue("реєстр не бачить людей для meetup.create", "roster = ::reachablePeople" in create)
        assertTrue("meetup.create не віддає чернетку у двері месенджера", "WebMessengerMeetupDraft.asked(ask)" in create)
        assertTrue("bill.split не віддає чернетку у двері месенджера", "WebMessengerMeetupDraft.asked(ask)" in bill)
        assertTrue("слова — не мовою відповіді помічника", "MeetupAssistantWords.replyLanguage { applicationContext }" in factory)
        val words = File(root, "app/src/main/java/local/phantom/companion/data/MeetupAssistantWords.kt").readText()
        assertTrue("слова беруть мову системи, а не ReplyLanguage", "ReplyLanguage.context(app())" in words)
    }

    @Test
    fun `кожне слово є в обох мовах і з тими самими місцями для підстановок`() {
        val uk = strings("values-uk").filterKeys { it.startsWith("meetup_ask_") }
        val en = strings("values").filterKeys { it.startsWith("meetup_ask_") }
        assertTrue("слів помічника про зустріч замало: $uk", uk.size >= 10)
        assertEquals(uk.keys, en.keys)
        val slots = Regex("%\\d\\\$s")
        uk.forEach { (k, v) ->
            assertEquals("місця підстановок у «$k» різні в мовах", slots.findAll(v).map { it.value }.sorted().toList(), slots.findAll(en.getValue(k)).map { it.value }.sorted().toList())
        }
    }

    @Test
    fun `у словах помічника немає заборонених слів`() {
        val banned = Regex("light|blackout|darkness|bluetooth|mesh|світл|темряв|блекаут", RegexOption.IGNORE_CASE)
        listOf("values-uk", "values").forEach { dir ->
            strings(dir).filterKeys { it.startsWith("meetup_ask_") }.forEach { (k, v) ->
                assertNull("«$k» у $dir: ${banned.find(v)?.value}", banned.find(v))
            }
        }
    }
}
