package local.phantom.companion.core.ai.route

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import local.phantom.companion.core.ai.AIChunk
import local.phantom.companion.core.ai.AIIntent
import local.phantom.companion.core.ai.ToolDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Збери Олену й Петра в Cafe X о 19:00» без мережі й без моделі: правила
 * самі впізнають прохання і кличуть `meetup.create` з тим, що сказано —
 * хто, де, коли. Звіряти імена з контактами вони не вміють (реєстр у
 * інструменті), але й не вигадують нічого.
 */
class OnDeviceToolRulesMeetupTest {

    private val rules = OnDeviceToolRules()

    private fun offered(vararg ids: String) = ids.map { ToolDefinition(it, "", JsonObject(emptyMap())) }

    private fun intent(prompt: String, vararg ids: String) =
        AIIntent.Text(prompt = prompt, tools = offered(*ids))

    private fun args(call: AIChunk.ToolCall) = Json.parseToJsonElement(call.arguments).jsonObject

    @Test
    fun `the meetup phrase becomes meetup create with people, place and time as said`() {
        val call = rules.toolCall(intent("збери Олену й Петра в Cafe X о 19:00", "meetup.create", "bill.split", "map.nearby"))
        assertNotNull("правила не впізнали зустріч", call)
        assertEquals("meetup.create", call!!.name)
        val a = args(call)
        assertEquals(listOf("Олену", "Петра"), a.getValue("people").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("Cafe X", a.getValue("place").jsonPrimitive.content)
        assertEquals("19:00", a.getValue("at").jsonPrimitive.content)
    }

    @Test
    fun `the arguments carry only what was said`() {
        val a = args(rules.toolCall(intent("організуй зустріч", "meetup.create"))!!)
        assertTrue("порожнє прохання не має нести вигаданих людей, місця чи часу: $a", a.isEmpty())
    }

    @Test
    fun `a meetup phrase with 'near' is a meetup, not a nearby search`() {
        val call = rules.toolCall(intent("збери Олену в кафе поруч з офісом о 19:00", "meetup.create", "map.nearby"))
        assertEquals("meetup.create", call?.name)
    }

    @Test
    fun `splitting the bill calls bill split with the mode`() {
        assertEquals("""{"mode":"claimed"}""", rules.toolCall(intent("розділи рахунок", "bill.split"))?.arguments)
        assertEquals("""{"mode":"equal"}""", rules.toolCall(intent("розділи рахунок порівну", "bill.split"))?.arguments)
    }

    @Test
    fun `a tool the phone did not offer is never called`() {
        assertNull(rules.toolCall(intent("збери Олену й Петра в Cafe X о 19:00", "map.nearby")))
        assertNull(rules.toolCall(intent("розділи рахунок", "map.nearby")))
    }

    @Test
    fun `ordinary collecting is left to the model and the other rules`() {
        assertNull(rules.toolCall(intent("збери звіт до 18:00", "meetup.create", "bill.split")))
        assertNull(rules.toolCall(intent("як розділити рахунок?", "meetup.create", "bill.split")))
        assertEquals("map.nearby", rules.toolCall(intent("де знайти аптеку поруч", "meetup.create", "map.nearby"))?.name)
    }

    @Test
    fun `the self leaves the meetup ask to the tools instead of saying no mind`() {
        assertTrue(rules.recognizes("збери Олену й Петра в Cafe X о 19:00"))
        assertTrue(rules.recognizes("розділи рахунок"))
        assertFalse(rules.recognizes("збери звіт до 18:00"))
    }

    @Test
    fun `both tools are in the list the registry guard checks`() {
        assertTrue("meetup.create" in OnDeviceToolRules.TOOL_IDS)
        assertTrue("bill.split" in OnDeviceToolRules.TOOL_IDS)
    }

    @Test
    fun `with no brain at all the chain still answers with the tool call`() = runBlocking {
        val chunks = BrainChain(emptyList()).complete(intent("збери Олену й Петра в Cafe X о 19:00", "meetup.create")).toList()
        val call = chunks.filterIsInstance<AIChunk.ToolCall>().singleOrNull()
        assertEquals("meetup.create", call?.name)
        assertEquals(JsonArray(listOf(JsonPrimitive("Олену"), JsonPrimitive("Петра"))), args(call!!).getValue("people"))
    }

    @Test
    fun `the tool's own words are what the person reads afterwards`() {
        val text = "Склав чернетку зустрічі: Олена, Петро · Cafe X · 19:00. Перевірте її й натисніть «Запросити»."
        val shown = rules.toolOutcome(
            AIIntent.Text(
                prompt = "збери Олену й Петра в Cafe X о 19:00",
                history = listOf(AIIntent.Text.Turn(role = AIIntent.Text.Role.TOOL, content = text, toolCallId = "c1")),
            ),
        )
        assertEquals(text, shown)
    }
}
