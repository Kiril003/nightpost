package local.phantom.companion.ui.messenger

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import local.phantom.companion.data.ChatHush
import local.phantom.companion.data.HushRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebMessengerHushTest {

    private var state = ChatHush()
    private var rules: Map<String, HushRule>? = null
    private val route = WebMessengerHush(
        object : WebMessengerHush.Port {
            override fun live(nowMs: Long) = state.live(nowMs)
            override fun set(chatId: String, untilMs: Long?, title: String?) { state = state.with(chatId, untilMs, title) }
            override fun clear(chatId: String) { state = state.without(chatId) }
            override fun setRules(rules: Map<String, HushRule>) { this@WebMessengerHushTest.rules = rules }
        },
        now = { 1_000L },
    )

    private fun call(method: String, path: String, body: String? = null) =
        Json.parseToJsonElement(route.handle(method, path.trim('/').split('/'), body)).jsonObject

    @Test
    fun `the web mutes and unmutes a chat in the app's store`() {
        assertTrue(route.claims(listOf("messenger", "hush")))
        assertTrue(route.claims(listOf("messenger", "hush", "marta")))
        assertFalse(route.claims(listOf("messenger", "hushes")))

        call("PUT", "/messenger/hush/marta", """{"until":null,"title":"  Марта  "}""")
        call("PUT", "/messenger/hush/dim", """{"until":5000}""")
        assertTrue(state.hushes("marta", 9_999_999L))
        assertTrue(state.hushes("dim", 4_999L))
        assertEquals(mapOf("marta" to "Марта"), state.titles)

        val rows = call("GET", "/messenger/hush")["data"]!!.jsonObject["chats"]!!.jsonObject
        assertEquals(JsonNull, rows["marta"])
        assertEquals(5_000L, rows["dim"]!!.jsonPrimitive.long)

        call("DELETE", "/messenger/hush/marta")
        assertFalse(state.hushes("marta", 2_000L))
        assertTrue(state.titles.isEmpty())
    }

    @Test
    fun `a bad or past end is refused, nothing is written`() {
        assertEquals(400, call("PUT", "/messenger/hush/marta", """{"until":"soon"}""")["status"]!!.jsonPrimitive.int)
        assertEquals(422, call("PUT", "/messenger/hush/marta", """{"until":500}""")["status"]!!.jsonPrimitive.int)
        assertFalse(call("PUT", "/messenger/hush/marta", "nope")["ok"]!!.jsonPrimitive.boolean)
        assertTrue(state.until.isEmpty())
    }

    @Test
    fun `Q1 - the web's hush rules land in the app, bad rows are skipped and a chat named rules is never muted`() {
        assertTrue(route.claims(listOf("messenger", "hush-rules")))
        val res = call(
            "PUT",
            "/messenger/hush-rules",
            """{"chats":{"marta":{},"dim":{"from":"22:00","to":"07:00","ask":true},"bad":{"from":"22:00"},"same":{"from":"9:00","to":"9:00"},"nope":3}}""",
        )
        assertTrue(res["ok"]!!.jsonPrimitive.boolean)
        assertEquals(mapOf("marta" to HushRule(), "dim" to HushRule(22 * 60, 7 * 60, ask = true)), rules)
        assertTrue(state.until.isEmpty())

        assertEquals(400, call("PUT", "/messenger/hush-rules", "[]")["status"]!!.jsonPrimitive.int)
        assertEquals(404, call("GET", "/messenger/hush-rules")["status"]!!.jsonPrimitive.int)
        call("PUT", "/messenger/hush-rules", """{"chats":{}}""")
        assertEquals(emptyMap<String, HushRule>(), rules)
    }
}
