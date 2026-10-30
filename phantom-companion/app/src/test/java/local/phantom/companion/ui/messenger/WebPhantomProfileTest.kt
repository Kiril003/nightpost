package local.phantom.companion.ui.messenger

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import local.phantom.companion.data.BodyLadder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Профіль › PHANTOM на телефоні: стрічка ПК — як є, з перевіркою ланцюжка;
 * ПК мовчить — чесне `offline`; «Стоп усе» діє тут одразу, ПК лише чує.
 */
class WebPhantomProfileTest {

    private class Shelf : BodyLadder.Shelf {
        val kept = mutableMapOf<String, String>()
        override fun read(key: String): String? = kept[key]
        override fun write(key: String, value: String) { kept[key] = value }
    }

    private val ladder = BodyLadder(Shelf())
    private var pcReply: Pair<Int, String> = 0 to ""
    private val posts = mutableListOf<Pair<String, JsonObject>>()
    private val profile = WebPhantomProfile(
        ladder,
        get = { pcReply },
        post = { path, body -> posts += path to body; 200 to "{}" },
    )

    private fun call(method: String, last: String, body: String? = null): JsonObject? =
        runBlocking { profile.handle(method, listOf("messenger", "devices", last), body) }

    @Test
    fun `маршрути стрічки й вимикача — лише свої`() {
        assertTrue(profile.claims(listOf("messenger", "devices", "feed")))
        assertTrue(profile.claims(listOf("messenger", "devices", "stop")))
        assertFalse(profile.claims(listOf("messenger", "devices", "pc", "stop")))
        assertFalse(profile.claims(listOf("messenger", "devices")))
        assertNull(call("POST", "feed"))
    }

    @Test
    fun `стрічка ПК приходить як є, з ланцюжком`() {
        pcReply = 200 to """{"stopped":false,"chain":{"ok":false,"count":3,"broken_at":4,"why":"x"},
            "entries":[{"n":5,"who":"mind","what":"messenger.send","status":"done"}]}"""
        val feed = call("GET", "feed")!!
        assertFalse(feed["offline"]!!.jsonPrimitive.boolean)
        assertEquals(4, feed["chain"]!!.jsonObject["broken_at"]!!.jsonPrimitive.int)
        assertEquals("messenger.send", feed["entries"]!!.jsonArray[0].jsonObject["what"]!!.jsonPrimitive.content)
    }

    @Test
    fun `доручення ПК приходять як є, а без них їх і не вигадано`() {
        pcReply = 200 to """{"stopped":false,"chain":{"ok":true,"count":0,"broken_at":null,"why":""},
            "entries":[],"missions":[{"id":"m1","goal":"Напиши Олі","status":"asking","tasks":3,"done":1}]}"""
        val feed = call("GET", "feed")!!
        val mission = feed["missions"]!!.jsonArray[0].jsonObject
        assertEquals("asking", mission["status"]!!.jsonPrimitive.content)
        assertEquals(1, mission["done"]!!.jsonPrimitive.int)
        pcReply = 0 to ""
        assertNull(call("GET", "feed")!!["missions"])
    }

    @Test
    fun `ПК не на звязку — offline, а не порожня стрічка`() {
        pcReply = 0 to ""
        val feed = call("GET", "feed")!!
        assertTrue(feed["offline"]!!.jsonPrimitive.boolean)
        assertEquals(0, feed["entries"]!!.jsonArray.size)
    }

    @Test
    fun `Стоп усе діє тут одразу і каже ПК — знову дозволяє лише людина тут`() {
        val on = call("POST", "stop", """{"on":true}""")!!
        assertTrue(on["stopped"]!!.jsonPrimitive.boolean)
        assertTrue(ladder.stopped.value)
        assertEquals("/api/v1/symbiote/stop", posts.single().first)
        assertTrue(ladder.judge("phone.ring") is BodyLadder.Verdict.Refuse)

        val off = call("POST", "stop", """{"on":false}""")!!
        assertFalse(off["stopped"]!!.jsonPrimitive.boolean)
        assertEquals(1, posts.size) // відновлення ПК не знімає — там своя людина
    }
}
