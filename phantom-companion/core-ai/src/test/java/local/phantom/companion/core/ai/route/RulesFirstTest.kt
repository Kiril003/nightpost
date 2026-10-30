package local.phantom.companion.core.ai.route

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import local.phantom.companion.core.ai.AIChunk
import local.phantom.companion.core.ai.AIIntent
import local.phantom.companion.core.ai.AIIntent.Text.Role
import local.phantom.companion.core.ai.AIIntent.Text.Turn
import local.phantom.companion.core.ai.AIRouter
import local.phantom.companion.core.ai.Route
import local.phantom.companion.core.ai.ToolDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Card A4: a recognised, high-confidence ask is answered by the rules at zero tokens, in every app language. */
class RulesFirstTest {

    private class Model : AIRouter {
        var calls = 0
        override fun complete(intent: AIIntent): Flow<AIChunk> {
            calls++
            return flowOf(
                AIChunk.Token("model said", Route.CLOUD_GEMINI),
                AIChunk.Final("model said", tokensUsed = 1, route = Route.CLOUD_GEMINI, durationMs = 1),
            )
        }
        override suspend fun availableRoutes(): Set<Route> = setOf(Route.CLOUD_GEMINI)
    }

    private fun tools(vararg ids: String) = ids.map { ToolDefinition(it, "", JsonObject(emptyMap())) }

    private val everything = tools("reminder.add", "memory.save", "meetup.create", "bill.split", "pay.draft", "map.nearby", "weather.now")

    private fun run(prompt: String, model: Model, offered: List<ToolDefinition> = everything, history: List<Turn> = emptyList()): List<AIChunk> =
        runBlocking { BrainChain(listOf(model)).complete(AIIntent.Text(prompt = prompt, history = history, tools = offered)).toList() }

    private fun callOf(prompt: String, model: Model = Model()): AIChunk.ToolCall {
        val call = run(prompt, model).filterIsInstance<AIChunk.ToolCall>().singleOrNull()
        assertNotNull("rules did not answer: $prompt", call)
        assertEquals(prompt, Route.ON_DEVICE_RULES, call!!.route)
        assertTrue(prompt, call.callId.startsWith(OnDeviceToolRules.RULE_CALL_PREFIX))
        assertEquals("the model was asked for: $prompt", 0, model.calls)
        return call
    }

    private fun args(call: AIChunk.ToolCall) = Json.parseToJsonElement(call.arguments).jsonObject

    @Test
    fun `a relative reminder is a rules call in English, Ukrainian and Czech`() {
        mapOf(
            "remind me in 20 min to call Daniel" to "call Daniel",
            "нагадай через 20 хв подзвонити Даніелю" to "подзвонити Даніелю",
            "připomeň mi za 20 minut zavolat Danielovi" to "zavolat Danielovi",
        ).forEach { (prompt, text) ->
            val call = callOf(prompt)
            assertEquals(prompt, "reminder.add", call.name)
            assertEquals(prompt, text, args(call).getValue("text").jsonPrimitive.content)
            assertEquals(prompt, 20, args(call).getValue("in_minutes").jsonPrimitive.int)
        }
    }

    @Test
    fun `a reminder at a clock time carries local_time`() {
        mapOf(
            "remind me at 18:30 to take the pills" to "take the pills",
            "нагадай о 18:30 випити таблетки" to "випити таблетки",
            "připomeň mi v 18:30 vzít léky" to "vzít léky",
        ).forEach { (prompt, text) ->
            val call = callOf(prompt)
            assertEquals(prompt, "reminder.add", call.name)
            assertEquals(prompt, text, args(call).getValue("text").jsonPrimitive.content)
            assertEquals(prompt, "18:30", args(call).getValue("local_time").jsonPrimitive.content)
        }
    }

    @Test
    fun `remember is memory save in all three languages`() {
        mapOf(
            "remember that Daniel likes pho" to "Daniel likes pho",
            "запамʼятай, що Даніель любить фо" to "Даніель любить фо",
            "zapamatuj si, že Daniel má rád pho" to "Daniel má rád pho",
        ).forEach { (prompt, text) ->
            val call = callOf(prompt)
            assertEquals(prompt, "memory.save", call.name)
            assertEquals(prompt, text, args(call).getValue("text").jsonPrimitive.content)
        }
    }

    @Test
    fun `a meetup phrase is meetup create in all three languages`() {
        val rows = listOf(
            Triple("plan pizza with Olena and Daniel Friday 19:00 at Pizzeria X", listOf("Olena", "Daniel"), "Pizzeria X"),
            Triple("збери Олену й Даніеля в пʼятницю о 19:00 в Pizzeria X", listOf("Олену", "Даніеля"), "Pizzeria X"),
            Triple("naplánuj pizzu s Olenou a Danielem v pátek v 19:00 v Pizzerii X", listOf("Olenou", "Danielem"), "Pizzerii X"),
        )
        for ((prompt, people, place) in rows) {
            val call = callOf(prompt)
            assertEquals(prompt, "meetup.create", call.name)
            val a = args(call)
            assertEquals(prompt, people, a.getValue("people").jsonArray.map { it.jsonPrimitive.content })
            assertEquals(prompt, place, a.getValue("place").jsonPrimitive.content)
            assertTrue(prompt, "19:00" in a.getValue("at").jsonPrimitive.content)
        }
    }

    @Test
    fun `a payment is a pay draft with the note, never an execution`() {
        mapOf(
            "send Daniel 5 USDC for pho" to ("daniel" to "pho"),
            "скинь Даніелю 5 USDC за фо" to ("даніелю" to "фо"),
            "pošli Danielovi 5 USDC za pho" to ("danielovi" to "pho"),
        ).forEach { (prompt, who) ->
            val call = callOf(prompt)
            assertEquals(prompt, "pay.draft", call.name)
            val a = args(call)
            assertEquals(prompt, listOf(who.first), a.getValue("who").jsonArray.map { it.jsonPrimitive.content })
            assertEquals(prompt, "5", a.getValue("amount").jsonPrimitive.content)
            assertEquals(prompt, "usdc", a.getValue("coin").jsonPrimitive.content)
            assertEquals(prompt, who.second, a.getValue("note").jsonPrimitive.content)
        }
    }

    @Test
    fun `everything else goes to the model, the weak rules included`() {
        listOf(
            "what is the capital of France?",
            "what's the weather like in Prague?",
            "відкрий камеру",
            "remind me about it",
            "do you remember what I said about Daniel?",
            "send me the report",
            "rozbal mi ten balík",
        ).forEach { prompt ->
            val model = Model()
            val out = run(prompt, model)
            assertEquals("the model must answer: $prompt", 1, model.calls)
            assertTrue(prompt, out.none { it is AIChunk.ToolCall })
        }
    }

    @Test
    fun `questions, day-bound reminders and loose talk are left to the model`() {
        listOf(
            "Did you send Daniel 5 USDC?",
            "remind me tomorrow at 18:30 to call Daniel",
            "нагадай завтра о 18:30 подзвонити Даніелю",
            "připomeň mi zítra v 18:30 zavolat Danielovi",
            "plan dinner",
            "naplánuj večeři",
            "schedule a call about the contract with Olena at 15:00",
        ).forEach { prompt ->
            val model = Model()
            val out = run(prompt, model)
            assertEquals("the model must answer: $prompt", 1, model.calls)
            assertTrue(prompt, out.none { it is AIChunk.ToolCall })
        }
    }

    @Test
    fun `a meeting word in work talk and a date the rules cannot read go to the model`() {
        listOf(
            "plan the meeting agenda",
            "organise my meeting notes",
            "schedule a meeting with the team tomorrow at 10:00",
            "připomeň mi 20. října v 9:00 zaplatit nájem",
        ).forEach { prompt ->
            val model = Model()
            val out = run(prompt, model)
            assertEquals("the model must answer: $prompt", 1, model.calls)
            assertTrue(prompt, out.none { it is AIChunk.ToolCall })
        }
    }

    @Test
    fun `a Czech reminder is recognised before the chain, so the offline pre-check lets it through`() {
        val rules = OnDeviceToolRules()
        assertTrue(rules.recognizes("připomeň mi za 20 minut zavolat Danielovi"))
        assertTrue(rules.recognizes("remember that Daniel likes green tea"))
    }

    @Test
    fun `the bill split, an address payment and the toolset's own names are rules calls`() {
        val split = callOf("split the bill equally")
        assertEquals("bill.split", split.name)
        assertEquals("equal", args(split).getValue("mode").jsonPrimitive.content)

        val addr = "B1rE3irXU6uJ1F2tF7iGmCRssRLUcG9GgmU7ULGtcxyE"
        val pay = callOf("send 1 usdc to $addr")
        assertEquals("pay.draft", pay.name)
        assertEquals(addr, args(pay).getValue("address").jsonPrimitive.content)
        assertEquals(0, args(pay).getValue("who").jsonArray.size)

        val model = Model()
        val toolset = tools("reminder.set", "memory.remember")
        val remind = run("remind me in 20 min to call Daniel", model, offered = toolset).filterIsInstance<AIChunk.ToolCall>().single()
        assertEquals("reminder.set", remind.name)
        val remember = run("remember that Daniel likes pho", model, offered = toolset).filterIsInstance<AIChunk.ToolCall>().single()
        assertEquals("memory.remember", remember.name)
        assertEquals(0, model.calls)
    }

    @Test
    fun `a tool the phone did not offer is not called, the model answers`() {
        val model = Model()
        val out = run("remind me in 20 min to call Daniel", model, offered = tools("map.nearby"))
        assertEquals(1, model.calls)
        assertTrue(out.none { it is AIChunk.ToolCall })
    }

    @Test
    fun `after a rules call the tool's own words are the answer and the model stays out`() {
        val model = Model()
        val result = """{"ok":true,"output":"Нагадаю «call Daniel» сьогодні о 18:00. Це через 20 хв."}"""
        val out = run(
            prompt = "",
            model = model,
            history = listOf(
                Turn(Role.USER, "remind me in 20 min to call Daniel"),
                Turn(Role.ASSISTANT, "", toolCalls = listOf(AIIntent.Text.ToolCall("rule_1a2b3c4d", "reminder.add", "{}"))),
                Turn(Role.TOOL, result, toolCallId = "rule_1a2b3c4d"),
            ),
        )
        val final = out.filterIsInstance<AIChunk.Final>().single()
        assertTrue(final.text, "call Daniel" in final.text)
        assertEquals(Route.ON_DEVICE_RULES, final.route)
        assertEquals(0, model.calls)
    }

    @Test
    fun `after a call the model made itself the model still writes the answer`() {
        val model = Model()
        val out = run(
            prompt = "",
            model = model,
            history = listOf(
                Turn(Role.USER, "що в мене сьогодні"),
                Turn(Role.ASSISTANT, "", toolCalls = listOf(AIIntent.Text.ToolCall("call_9f8e7d6c", "calendar.list", "{}"))),
                Turn(Role.TOOL, """{"ok":true,"output":"нічого"}""", toolCallId = "call_9f8e7d6c"),
            ),
        )
        assertEquals(1, model.calls)
        assertTrue(out.filterIsInstance<AIChunk.Final>().single().text == "model said")
    }
}
