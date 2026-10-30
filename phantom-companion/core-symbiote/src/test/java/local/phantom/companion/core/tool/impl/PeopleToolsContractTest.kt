package local.phantom.companion.core.tool.impl

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.runBlocking
import local.phantom.companion.core.tool.ToolCallParser
import local.phantom.companion.core.tool.ToolPromptInjector
import local.phantom.companion.core.tool.ToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Стик «модель → інструмент», без моделі.
 *
 * Живої моделі тут нема, тож не можна довести, що вона ЗАХОЧЕ покликати
 * `people.draft`. Але можна довести все інше на цьому шляху, і саме воно вже
 * ламалось: інструмент має бути видним у `[TOOLS: …]`, його виклик має
 * розбиратись парсером, а сам він — робити рівно те, що обіцяє.
 */
class PeopleToolsContractTest {

    private val roster = listOf(
        ReachablePerson("aaaa1111", "Марта", live = true, lastSeenMs = 10L, verified = true),
        ReachablePerson("bbbb2222", "Тарас", live = false, lastSeenMs = 5L, verified = false),
    )

    private fun listTool() = PeopleListTool(roster = { roster })

    private fun draftTool(sink: MutableList<Pair<String, String>>) = PeopleDraftTool(
        roster = { roster },
        prepareDraft = { id, text -> sink += id to text; true },
    )

    @Test
    fun `обидва інструменти видно моделі з їхніми аргументами`() {
        val block = ToolPromptInjector.render(listOf(listTool(), draftTool(mutableListOf())))

        assertTrue("people.list не потрапив у підказку: $block", "people.list(liveOnly)" in block)
        assertTrue("people.draft не потрапив: $block", "people.draft(who,text)" in block)
    }

    @Test
    fun `виклик people_draft від моделі розбирається`() {
        val raw = """
            {"type":"tool-call","tool":"people.draft",
             "args":{"who":"Марта","text":"виходь"}}
        """.trimIndent()

        val call = ToolCallParser.tryParse(Json.parseToJsonElement(raw) as JsonObject)

        assertEquals("people.draft", call?.toolId)
        assertEquals("Марта", call?.args?.get("who")?.toString()?.trim('"'))
    }

    @Test
    fun `чернетка готує текст і НЕ надсилає`() = runBlocking {
        val opened = mutableListOf<Pair<String, String>>()
        val args = buildJsonObject {
            put("who", "Марта")
            put("text", "виходь, я коло входу")
        }

        val result = draftTool(opened).execute(args, context = fakeContext()) {}

        assertTrue("мав бути Ok, а був $result", result is ToolResult.Ok)
        assertEquals(listOf("aaaa1111" to "виходь, я коло входу"), opened)
        // Найважливіше твердження цього файлу: лист не пішов.
        assertTrue("\"sent\":false" in (result as ToolResult.Ok).structured.toString())
    }

    /**
     * Слова інструмента — це те, що оператор ПОЧУЄ. До 06.09 він казав «відкрив
     * розмову, текст готовий» і після зникнення рідного екрана розмови це стало
     * неправдою: месенджер відчиняється на переліку, поля з текстом там немає.
     * Сторож тримає обіцянку рівно на тому, що робить [PeopleDraftTool].
     */
    @Test
    fun `інструмент не обіцяє відкриту розмову з набраним текстом`() = runBlocking {
        val args = buildJsonObject {
            put("who", "Марта")
            put("text", "виходь, я коло входу")
        }

        val said = (draftTool(mutableListOf()).execute(args, context = fakeContext()) {} as ToolResult.Ok).output

        assertTrue("оператор має знати, де взяти текст: $said", "буфер" in said)
        assertTrue("обіцянка відкритої розмови повернулась: $said", "Відкрив розмову" !in said)
        // Успіх міряний буфером, тож про відчинений екран говорити нічим.
        assertTrue("недоведена обіцянка про месенджер: $said", "месенджер відкрито" !in said)
    }

    @Test
    fun `невідому людину не підміняють першою-ліпшою`() = runBlocking {
        val opened = mutableListOf<Pair<String, String>>()
        val args = buildJsonObject {
            put("who", "Богдан")
            put("text", "привіт")
        }

        val result = draftTool(opened).execute(args, context = fakeContext()) {}

        assertTrue("мав бути Err, а був $result", result is ToolResult.Err)
        assertTrue("розмову відкривати не можна було", opened.isEmpty())
    }

    @Test
    fun `хто зараз поруч — окреме питання`() = runBlocking {
        val args = buildJsonObject { put("liveOnly", "true") }

        val result = listTool().execute(args, context = fakeContext()) {}

        val text = (result as ToolResult.Ok).output
        assertTrue("Марта поруч і має бути в списку: $text", "Марта" in text)
        assertTrue("Тараса не чути, його бути не мало: $text", "Тарас" !in text)
    }

    private fun fakeContext() = local.phantom.companion.core.tool.ToolContext(
        appContext = android.app.Application(),
        operatorId = "tester",
        requestId = "req-1",
        approvedPermissions = emptySet(),
    )
}
