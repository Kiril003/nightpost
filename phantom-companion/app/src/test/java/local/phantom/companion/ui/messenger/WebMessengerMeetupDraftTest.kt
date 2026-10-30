package local.phantom.companion.ui.messenger

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Чернетка зустрічі від помічника — форма JSON, яку читає веб
 * (`nativeMeetupDraft.ts`), і черга, з якої її забирає хост.
 */
class WebMessengerMeetupDraftTest {

    @After
    fun clear() {
        WebMessengerMeetupDraft.pending.value = null
    }

    @Test
    fun `зустріч — лише відомі поля, імена без повторів і порожнього`() {
        val json = WebMessengerMeetupDraft.meetup(listOf(" Олена ", "Петро", "Олена", "  "), "  Cafe   X ", "19:00")
        val o = Json.parseToJsonElement(json).jsonObject
        assertEquals(setOf("people", "place", "at"), o.keys)
        assertEquals(listOf("Олена", "Петро"), o["people"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("Cafe X", o["place"]!!.jsonPrimitive.content)
        assertEquals("19:00", o["at"]!!.jsonPrimitive.content)
    }

    @Test
    fun `мітка з мапи — точка й підпис, а поза світом нічого`() {
        assertEquals(
            """{"pin":{"lat":49.83512,"lon":18.28301,"label":"Stodolní 1"}}""",
            WebMessengerMeetupDraft.pin(49.835123, 18.283011, "  Stodolní   1 "),
        )
        assertEquals("""{"pin":{"lat":-33.5,"lon":151.0}}""", WebMessengerMeetupDraft.pin(-33.5, 151.0, " "))
        assertEquals(null, WebMessengerMeetupDraft.pin(91.0, 0.0, null))
        assertEquals(null, WebMessengerMeetupDraft.pin(0.0, Double.NaN, null))
    }

    @Test
    fun `«Зустрітися тут» з мапи ставить чернетку з міткою в чергу`() {
        WebMessengerMeetupDraft.pending.value = null
        assertEquals(false, WebMessengerMeetupDraft.meetHere(95.0, 0.0, "x", nowMs = 0))
        assertEquals(null, WebMessengerMeetupDraft.pending.value)
        assertEquals(true, WebMessengerMeetupDraft.meetHere(49.8, 18.2, "Kavárna", nowMs = 0))
        assertEquals(
            """{"pin":{"lat":49.8,"lon":18.2,"label":"Kavárna"}}""",
            WebMessengerMeetupDraft.pending.value?.json,
        )
        WebMessengerMeetupDraft.pending.value = null
    }

    @Test
    fun `Meet here from a Nightpost venue carries the venue id with the point`() {
        assertEquals(
            """{"pin":{"lat":49.8,"lon":18.2,"label":"Kavárna","venue":"0123456789abcdef"}}""",
            WebMessengerMeetupDraft.pin(49.8, 18.2, "Kavárna", venueId = "0123456789abcdef"),
        )
        WebMessengerMeetupDraft.pending.value = null
        assertEquals(true, WebMessengerMeetupDraft.meetHere(49.8, 18.2, "Kavárna", nowMs = 0, venueId = "0123456789abcdef"))
        val pin = Json.parseToJsonElement(WebMessengerMeetupDraft.pending.value!!.json).jsonObject["pin"]!!.jsonObject
        assertEquals("0123456789abcdef", pin["venue"]?.jsonPrimitive?.content)
        assertEquals(49.8, pin["lat"]!!.jsonPrimitive.content.toDouble(), 1e-9)
        assertEquals(18.2, pin["lon"]!!.jsonPrimitive.content.toDouble(), 1e-9)
        WebMessengerMeetupDraft.pending.value = null
    }

    @Test
    fun `a venue id that is not 16 hex never reaches the page, the point stays`() {
        for (bad in listOf("xyz", "0123456789abcde", "0123456789ABCDEF", "0123456789abcdef0", "")) {
            assertEquals(
                bad,
                """{"pin":{"lat":49.8,"lon":18.2,"label":"Kavárna"}}""",
                WebMessengerMeetupDraft.pin(49.8, 18.2, "Kavárna", venueId = bad),
            )
        }
    }

    @Test
    fun `порожня чернетка — порожній об'єкт, а не вигадані слова`() {
        assertEquals("{}", WebMessengerMeetupDraft.meetup(emptyList(), null, ""))
    }

    @Test
    fun `довгі слова обрізаються, людей не більше дванадцяти`() {
        val o = Json.parseToJsonElement(
            WebMessengerMeetupDraft.meetup((1..30).map { "Людина$it" }, "x".repeat(500), null),
        ).jsonObject
        assertEquals(12, o["people"]!!.jsonArray.size)
        assertEquals(120, o["place"]!!.jsonPrimitive.content.length)
    }

    @Test
    fun `розділити рахунок — лише claimed чи equal`() {
        assertEquals("""{"split":"equal"}""", WebMessengerMeetupDraft.split(" Equal "))
        assertEquals("""{"split":"claimed"}""", WebMessengerMeetupDraft.split("claimed"))
        assertNull(WebMessengerMeetupDraft.split("everything"))
        assertNull(WebMessengerMeetupDraft.split(""))
    }

    @Test
    fun `сценарій для WebView — рядок у лапках, слово людини не стає кодом`() {
        val json = WebMessengerMeetupDraft.meetup(listOf("Олена\"); alert(1); (\""), null, null)
        val js = WebMessengerMeetupDraft.script(json)
        assertTrue(js, js.contains("window.__phantomOpenMeetupDraft"))
        assertTrue(js, js.contains("f(\"{"))
        assertTrue("лапки з імені мусять бути екрановані: $js", !js.contains("alert(1); (\"\""))
    }

    @Test
    fun `відповідь веба — три різні слова, а не лише true чи false`() {
        assertEquals(WebMessengerMeetupDraft.Outcome.OPENED, WebMessengerMeetupDraft.outcome("\"opened\""))
        assertEquals(WebMessengerMeetupDraft.Outcome.REFUSED, WebMessengerMeetupDraft.outcome("\"refused\""))
        assertEquals(WebMessengerMeetupDraft.Outcome.MISSING, WebMessengerMeetupDraft.outcome("\"missing\""))
        assertEquals(WebMessengerMeetupDraft.Outcome.MISSING, WebMessengerMeetupDraft.outcome("null"))
        assertEquals(WebMessengerMeetupDraft.Outcome.MISSING, WebMessengerMeetupDraft.outcome(null))
    }

    @Test
    fun `сторінка ще не підняла двері — пробуємо знову, доки не відкриє`() = runTest {
        val json = """{"people":["A"]}"""
        WebMessengerMeetupDraft.open(json)
        val a = WebMessengerMeetupDraft.pending.value!!
        var calls = 0
        val out = WebMessengerMeetupDraft.deliver(a, run = { calls++; if (calls < 4) "\"missing\"" else "\"opened\"" }, pause = {})
        assertEquals(WebMessengerMeetupDraft.Outcome.OPENED, out)
        assertEquals(4, calls)
        assertNull("відкрите знімається з черги", WebMessengerMeetupDraft.pending.value)
    }

    @Test
    fun `відмова веба — один виклик і прохання знято, а не двадцять сповіщень`() = runTest {
        WebMessengerMeetupDraft.open("""{"split":"equal"}""")
        val a = WebMessengerMeetupDraft.pending.value!!
        var calls = 0
        val out = WebMessengerMeetupDraft.deliver(a, run = { calls++; "\"refused\"" }, pause = {})
        assertEquals(WebMessengerMeetupDraft.Outcome.REFUSED, out)
        assertEquals("веб відмовив, а хост питав ще", 1, calls)
        assertNull("відмова не воскресає з новим WebView", WebMessengerMeetupDraft.pending.value)
    }

    @Test
    fun `дверей так і нема — рівно TRIES спроб і прохання лишається для наступної сторінки`() = runTest {
        WebMessengerMeetupDraft.open("""{"people":["A"]}""")
        val a = WebMessengerMeetupDraft.pending.value!!
        var calls = 0
        val out = WebMessengerMeetupDraft.deliver(a, run = { calls++; "\"missing\"" }, pause = {})
        assertEquals(WebMessengerMeetupDraft.Outcome.MISSING, out)
        assertEquals(WebMessengerMeetupDraft.TRIES, calls)
        assertEquals(a, WebMessengerMeetupDraft.pending.value)
    }

    @Test
    fun `нове прохання не знімається чужою відповіддю`() = runTest {
        WebMessengerMeetupDraft.open("""{"people":["A"]}""")
        val first = WebMessengerMeetupDraft.pending.value!!
        WebMessengerMeetupDraft.deliver(first, run = {
            WebMessengerMeetupDraft.open("""{"split":"equal"}""")
            "\"opened\""
        }, pause = {})
        assertEquals("""{"split":"equal"}""", WebMessengerMeetupDraft.pending.value!!.json)
    }

    @Test
    fun `нове прохання заступає старе, черга одна`() {
        WebMessengerMeetupDraft.open("""{"people":["A"]}""")
        WebMessengerMeetupDraft.open("""{"split":"equal"}""")
        assertEquals("""{"split":"equal"}""", WebMessengerMeetupDraft.pending.value!!.json)
    }

    // ── строк: чернетка не відкривається через години ────────────────────────

    @Test
    fun `прохання живе TTL_MS від миті, коли його поклали`() {
        WebMessengerMeetupDraft.open("""{"people":["A"]}""", nowMs = 1_000L)
        assertEquals(1_000L + WebMessengerMeetupDraft.TTL_MS, WebMessengerMeetupDraft.pending.value!!.expiresAtMs)
        assertTrue("строк має бути коротким — хвилини, не години", WebMessengerMeetupDraft.TTL_MS <= 10 * 60_000L)
    }

    @Test
    fun `месенджер відкрили через години — прострочена чернетка не відкривається, а знімається`() = runTest {
        WebMessengerMeetupDraft.open("""{"people":["Олена"]}""", nowMs = 0L)
        val stale = WebMessengerMeetupDraft.pending.value!!
        var asked = 0
        val out = WebMessengerMeetupDraft.deliver(
            stale,
            run = { asked++; "\"opened\"" },
            pause = {},
            now = { 3 * 3_600_000L },
        )
        assertEquals(WebMessengerMeetupDraft.Outcome.EXPIRED, out)
        assertEquals("прострочену чернетку віддали вебу", 0, asked)
        assertNull("прострочене лишилось у черзі й чекає наступної сторінки", WebMessengerMeetupDraft.pending.value)
    }

    @Test
    fun `строк спливає посеред чекання дверей — спроби припиняються`() = runTest {
        WebMessengerMeetupDraft.open("""{"people":["A"]}""", nowMs = 0L)
        val a = WebMessengerMeetupDraft.pending.value!!
        var clock = a.expiresAtMs - 1_000L
        var calls = 0
        val out = WebMessengerMeetupDraft.deliver(
            a,
            run = { calls++; "\"missing\"" },
            pause = { clock += 600L },
            now = { clock },
        )
        assertEquals(WebMessengerMeetupDraft.Outcome.EXPIRED, out)
        assertTrue("спроб було $calls, а мало обірватись на строку", calls < WebMessengerMeetupDraft.TRIES)
        assertNull(WebMessengerMeetupDraft.pending.value)
    }

    @Test
    fun `свіже прохання, поки строк не вийшов, відкривається`() = runTest {
        WebMessengerMeetupDraft.open("""{"people":["A"]}""", nowMs = 0L)
        val a = WebMessengerMeetupDraft.pending.value!!
        val out = WebMessengerMeetupDraft.deliver(a, run = { "\"opened\"" }, pause = {}, now = { 60_000L })
        assertEquals(WebMessengerMeetupDraft.Outcome.OPENED, out)
    }
}
