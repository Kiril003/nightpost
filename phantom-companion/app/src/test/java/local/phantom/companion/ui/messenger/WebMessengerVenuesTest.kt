package local.phantom.companion.ui.messenger

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import local.phantom.companion.core.tool.impl.LocationSnapshot
import local.phantom.companion.core.truth.H3
import local.phantom.companion.data.VenueApi
import local.phantom.companion.data.VenueClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `/messenger/venues/…` — двері веба до закладів.
 *
 * Веб у телефоні сервера не бачить (CSP `connect-src 'self'`), тож усе тут іде
 * мостом. Доводиться три речі: веб НЕ дає координат (клітинку з місця рахує
 * телефон), кривий вхід не доходить до сервера, і жодна відмова не стає
 * «успіхом» — у неї є код і речення.
 */
class WebMessengerVenuesTest {

    private class FakeApi : VenueApi {
        val asked = mutableListOf<String>()
        var areas: List<String> = emptyList()
        var answer: VenueClient.Answer = VenueClient.Answer.Ok("""{"venues":[{"id":"0123456789abcdef","name":"Cafe X"}]}""")
        override suspend fun near(areas: List<String>): VenueClient.Answer {
            asked += "near"; this.areas = areas; return answer
        }
        override suspend fun venue(id: String): VenueClient.Answer {
            asked += "venue:$id"; return answer
        }
        override suspend fun rate(id: String, stars: Int): VenueClient.Answer {
            asked += "rate:$id:$stars"; return answer
        }
        override suspend fun requests(ack: List<String>): VenueClient.Answer {
            asked += "requests:" + ack.joinToString(","); return answer
        }
    }

    private val kyiv = WebMessengerVenues.Here.Point(50.4501, 30.5234)

    private fun route(
        api: FakeApi,
        method: String,
        path: String,
        body: String? = null,
        here: WebMessengerVenues.Here = kyiv,
    ): JsonObject {
        val venues = WebMessengerVenues(api, here = { here }, words = { it.name }, english = { false })
        val seg = path.trim('/').split('/')
        assertTrue("міст не впізнав свій шлях $path", WebMessengerVenues.claims(seg))
        return Json.parseToJsonElement(kotlinx.coroutines.runBlocking { venues.handle(method, seg, body) }).jsonObject
    }

    private fun JsonObject.status() = this["status"]!!.jsonPrimitive.int
    private fun JsonObject.code() = this["code"]?.jsonPrimitive?.content

    @Test
    fun `шлях закладів свій, чужих не бере`() {
        assertTrue(WebMessengerVenues.claims(listOf("messenger", "venues", "near")))
        assertFalse(WebMessengerVenues.claims(listOf("messenger", "venues")))
        assertFalse(WebMessengerVenues.claims(listOf("messenger", "conversations", "venues")))
    }

    @Test
    fun `поруч — область мого місця і сусідні, не більше семи`() = runTest {
        val api = FakeApi()
        val r = route(api, "GET", "/messenger/venues/near")
        assertEquals(200, r.status())
        assertEquals("Cafe X", r["data"]!!.jsonObject["venues"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content)
        val own = H3.latLngToCell(kyiv.lat, kyiv.lon, H3.AREA_RES)
        assertTrue("своєї області немає серед запитаних: ${api.areas}", own in api.areas)
        assertTrue("забагато областей: ${api.areas}", api.areas.size in 2..7)
        assertEquals("області повторюються", api.areas.size, api.areas.toSet().size)
        api.areas.forEach { assertEquals("не область r6: $it", it, H3.parent(it, H3.AREA_RES)) }
    }

    @Test
    fun `без місця — чесне «не знаю, де ви», а не порожній список`() = runTest {
        val api = FakeApi()
        val r = route(api, "GET", "/messenger/venues/near", here = WebMessengerVenues.Here.None)
        assertEquals(409, r.status())
        assertEquals("no_fix", r.code())
        assertTrue("сервер питали без місця", api.asked.isEmpty())
        val refused = route(api, "GET", "/messenger/venues/near", here = WebMessengerVenues.Here.Refused("місце підмінено", "spoofed"))
        assertEquals(409, refused.status())
        assertEquals("місце підмінено", refused["detail"]!!.jsonPrimitive.content)
    }

    @Test
    fun `сторінка закладу — те, що дав сервер`() = runTest {
        val api = FakeApi().apply { answer = VenueClient.Answer.Ok("""{"id":"0123456789abcdef","name":"Cafe X","page":{"v":1,"blocks":[]}}""") }
        val r = route(api, "GET", "/messenger/venues/0123456789abcdef")
        assertEquals(200, r.status())
        assertEquals(listOf("venue:0123456789abcdef"), api.asked)
        assertEquals(1, r["data"]!!.jsonObject["page"]!!.jsonObject["v"]!!.jsonPrimitive.int)
    }

    @Test
    fun `оцінка — зірки цілим числом 1-5`() = runTest {
        val api = FakeApi().apply { answer = VenueClient.Answer.Ok("""{"venue_id":"0123456789abcdef","rating":{"mean":4.0,"count":1}}""") }
        for (bad in listOf("""{"stars":0}""", """{"stars":6}""", """{"stars":"5"}""", """{"stars":4.5}""", "{}", "nope")) {
            val r = route(api, "POST", "/messenger/venues/0123456789abcdef/rate", bad)
            assertEquals("тіло $bad", 422, r.status())
            assertEquals("bad_stars", r.code())
        }
        assertTrue("кривим зіркам постукали на сервер: ${api.asked}", api.asked.isEmpty())
        val ok = route(api, "POST", "/messenger/venues/0123456789abcdef/rate", """{"stars":4}""")
        assertEquals(200, ok.status())
        assertEquals(listOf("rate:0123456789abcdef:4"), api.asked)
        assertEquals(1, ok["data"]!!.jsonObject["rating"]!!.jsonObject["count"]!!.jsonPrimitive.int)
    }

    @Test
    fun `відмови мають код і речення`() = runTest {
        val api = FakeApi()
        api.answer = VenueClient.Answer.BadInput
        assertEquals("no_venue", route(api, "GET", "/messenger/venues/0123456789abcdef").code())
        api.answer = VenueClient.Answer.Missing
        route(api, "GET", "/messenger/venues/0123456789abcdef").let {
            assertEquals(404, it.status()); assertEquals("no_venue", it.code())
        }
        api.answer = VenueClient.Answer.NoKey
        route(api, "POST", "/messenger/venues/0123456789abcdef/rate", """{"stars":5}""").let {
            assertEquals(409, it.status()); assertEquals("no_key", it.code())
        }
        api.answer = VenueClient.Answer.NotEnabled
        route(api, "GET", "/messenger/venues/near").let {
            assertEquals(503, it.status()); assertEquals("not_enabled", it.code())
        }
        api.answer = VenueClient.Answer.Unreachable
        route(api, "GET", "/messenger/venues/near").let {
            assertEquals(502, it.status()); assertEquals("unreachable", it.code())
        }
        api.answer = VenueClient.Answer.Refused(422, "Текст задовгий", "Text too long")
        route(api, "POST", "/messenger/venues/0123456789abcdef/rate", """{"stars":5}""").let {
            assertEquals(422, it.status())
            assertEquals("refused", it.code())
            assertEquals("Текст задовгий", it["detail"]!!.jsonPrimitive.content)
        }
        api.answer = VenueClient.Answer.Refused(429, null, null)
        route(api, "POST", "/messenger/venues/0123456789abcdef/rate", """{"stars":5}""").let {
            assertEquals(429, it.status()); assertEquals("limited", it.code())
        }
        api.answer = VenueClient.Answer.Ok("not json")
        route(api, "GET", "/messenger/venues/near").let {
            assertEquals(502, it.status()); assertFalse(it["ok"]!!.jsonPrimitive.boolean)
        }
    }

    @Test
    fun `невідомий шлях під venues — 404, а не сервер`() = runTest {
        val api = FakeApi()
        val r = route(api, "DELETE", "/messenger/venues/0123456789abcdef")
        assertEquals(404, r.status())
        assertTrue(api.asked.isEmpty())
    }

    // ── «поруч» не видає, де в області я стою ────────────────────────────────

    /** Дві точки в ОДНІЙ області r6, ось найдальші одна від одної на лінії схід-захід. */
    private fun twoCornersOfOneArea(lat: Double, lon: Double): Pair<Double, Double> {
        val own = H3.latLngToCell(lat, lon, H3.AREA_RES)
        var west = lon
        var east = lon
        var d = lon
        while (H3.latLngToCell(lat, d - 0.002, H3.AREA_RES) == own) d -= 0.002
        west = d
        d = lon
        while (H3.latLngToCell(lat, d + 0.002, H3.AREA_RES) == own) d += 0.002
        east = d
        return west to east
    }

    @Test
    fun `набір областей той самий з будь-якого місця всередині моєї області`() {
        val (west, east) = twoCornersOfOneArea(50.4501, 30.5234)
        assertTrue("точки мають бути далеко: $west $east", east - west > 0.03)
        val a = WebMessengerVenues.areasAround(50.4501, west)
        val b = WebMessengerVenues.areasAround(50.4501, east)
        val c = WebMessengerVenues.areasAround(50.4501, 30.5234)
        assertEquals("набір залежить від того, де в області я стою", a, b)
        assertEquals(a, c)
        assertEquals("кільце k=1: своя область і шість сусідів", 7, a.size)
    }

    @Test
    fun `області відсортовані, а власна не йде першою`() {
        val own = H3.latLngToCell(50.4501, 30.5234, H3.AREA_RES)
        val areas = WebMessengerVenues.areasAround(50.4501, 30.5234)
        assertEquals(areas.sorted(), areas)
        assertTrue(own in areas)
        // Власна область стоїть у відсортованому порядку, а не першою за звичкою:
        // перший у запиті виказував би її. Перевіряємо на місцях, де вона не мінімальна.
        val probes = listOf(50.4501 to 30.5234, 49.8209 to 18.2625, 48.8566 to 2.3522, 52.52 to 13.405)
        val ownFirst = probes.count { (la, lo) ->
            WebMessengerVenues.areasAround(la, lo).first() == H3.latLngToCell(la, lo, H3.AREA_RES)
        }
        assertTrue("власна область першою на всіх проб: $ownFirst", ownFirst < probes.size)
    }

    // ── «Я на місці» бере лише точний і свіжий фікс ──────────────────────────

    @Test
    fun `для відмітки годиться лише злитий, свіжий фікс із малою похибкою`() {
        assertTrue(WebMessengerVenues.preciseEnough("gate/fused", 12f, 3_000L))
        assertTrue(WebMessengerVenues.preciseEnough("gate/fused", 100f, 60_000L))
    }

    @Test
    fun `мережевий, застарілий і без похибки фікс для відмітки не годиться`() {
        assertFalse("останній відомий за мережею", WebMessengerVenues.preciseEnough("last-known/network", 20f, 1_000L))
        assertFalse("за IP", WebMessengerVenues.preciseEnough("ip-geo", 20f, 1_000L))
        assertFalse("утримана позиція", WebMessengerVenues.preciseEnough("gate/held", 20f, 1_000L))
        assertFalse("після підміни — мережевий якір", WebMessengerVenues.preciseEnough("gate/network_hold", 20f, 1_000L))
        assertFalse("кілометрова похибка ділить фікс між клітинками r8", WebMessengerVenues.preciseEnough("gate/fused", 1_000f, 1_000L))
        assertFalse("старіший за хвилину", WebMessengerVenues.preciseEnough("gate/fused", 12f, 60_001L))
        assertFalse("вік невідомий", WebMessengerVenues.preciseEnough("gate/fused", 12f, null))
        assertFalse("похибка невідома", WebMessengerVenues.preciseEnough("gate/fused", null, 1_000L))
    }

    // ── «поруч» не приймає здогаду про місце ─────────────────────────────────

    private fun fix(
        source: String,
        ageMs: Long? = null,
        lat: Double? = 50.4501,
        lon: Double? = 30.5234,
        refusedUa: String? = null,
    ) = LocationSnapshot(lat = lat, lon = lon, accuracyM = 800f, ageMs = ageMs, source = source, refusedUa = refusedUa)

    private fun hereNear(snap: LocationSnapshot?) =
        WebMessengerVenues.hereOf(snap, WebMessengerVenues.Use.NEAR) { "рядок#$it" }

    @Test
    fun `для поруч годиться місце воріт і свіже останнє відоме`() {
        assertTrue(WebMessengerVenues.nearEnough("gate/fused", null))
        assertTrue(WebMessengerVenues.nearEnough("gate/network", null))
        assertTrue(WebMessengerVenues.nearEnough("last-known/network", 9 * 60_000L))
        assertTrue(WebMessengerVenues.nearEnough("last-known/network", WebMessengerVenues.NEAR_MAX_AGE_MS))
    }

    @Test
    fun `для поруч не годиться позиція за IP, старе й безвікове останнє відоме`() {
        assertFalse("за IP", WebMessengerVenues.nearEnough("ip-geo", null))
        assertFalse("днями давності", WebMessengerVenues.nearEnough("last-known/network", 3 * 24 * 3_600_000L))
        assertFalse("вік невідомий", WebMessengerVenues.nearEnough("last-known/network", null))
        assertFalse("старіше за межу", WebMessengerVenues.nearEnough("last-known/network", WebMessengerVenues.NEAR_MAX_AGE_MS + 1))
        assertFalse("невідоме джерело", WebMessengerVenues.nearEnough("something", 1_000L))
    }

    @Test
    fun `поруч за IP чи за давнім фіксом — відмова «місце застаріле», а не чужі заклади`() {
        val ip = hereNear(fix("ip-geo"))
        assertTrue(ip is WebMessengerVenues.Here.Refused)
        assertEquals("рядок#${WebMessengerVenues.Use.NEAR.weak}", (ip as WebMessengerVenues.Here.Refused).uk)
        val old = hereNear(fix("last-known/network", ageMs = 5 * 24 * 3_600_000L))
        assertTrue("днями старий фікс прийнято за місце", old is WebMessengerVenues.Here.Refused)
        val api = FakeApi()
        val r = route(api, "GET", "/messenger/venues/near", here = old)
        assertEquals(409, r.status())
        assertEquals("no_fix", r.code())
        assertTrue("сервер питали за здогадом про місце", api.asked.isEmpty())
    }

    @Test
    fun `поруч із воріт місця чи зі свіжого останнього відомого — точка`() {
        assertEquals(WebMessengerVenues.Here.Point(50.4501, 30.5234), hereNear(fix("gate/fused")))
        assertEquals(WebMessengerVenues.Here.Point(50.4501, 30.5234), hereNear(fix("last-known/network", ageMs = 60_000L)))
    }

    @Test
    fun `воріт місця немає взагалі — None, а відмова воріт — слово воріт`() {
        assertEquals(WebMessengerVenues.Here.None, hereNear(null))
        val spoof = hereNear(fix("gate/spoofed", lat = null, lon = null, refusedUa = "місце підмінено")) as WebMessengerVenues.Here.Refused
        assertEquals("місце підмінено", spoof.uk)
        val unknown = hereNear(fix("gate/none", lat = null, lon = null)) as WebMessengerVenues.Here.Refused
        assertEquals("рядок#${WebMessengerVenues.Use.NEAR.noFix}", unknown.uk)
    }

    @Test
    fun `«Я на місці» без місця говорить про відмітку, а не про заклади поруч`() {
        assertTrue(
            "одне речення на «поруч» і «Я на місці» — те, що вже показували не там",
            WebMessengerVenues.Use.NEAR.noFix != WebMessengerVenues.Use.CHECK_IN.noFix,
        )
        assertTrue(WebMessengerVenues.Word.NO_FIX.res != WebMessengerPoints.Word.NO_FIX.res)
        assertEquals(WebMessengerVenues.Use.CHECK_IN.noFix, WebMessengerPoints.Word.NO_FIX.res)
        val none = WebMessengerVenues.hereOf(
            LocationSnapshot(lat = null, lon = null, source = "gate/none"),
            WebMessengerVenues.Use.CHECK_IN,
        ) { "рядок#$it" } as WebMessengerVenues.Here.Refused
        assertEquals("рядок#${WebMessengerVenues.Use.CHECK_IN.noFix}", none.uk)
    }

    @Test
    fun `«Я на місці» бере тільки точний фікс — мережевий за нього не годиться`() {
        val net = WebMessengerVenues.hereOf(fix("last-known/network", ageMs = 1_000L), WebMessengerVenues.Use.CHECK_IN) { "рядок#$it" }
        assertTrue(net is WebMessengerVenues.Here.Refused)
        val precise = LocationSnapshot(lat = 50.0, lon = 30.0, accuracyM = 12f, ageMs = 2_000L, source = "gate/fused")
        assertEquals(
            WebMessengerVenues.Here.Point(50.0, 30.0),
            WebMessengerVenues.hereOf(precise, WebMessengerVenues.Use.CHECK_IN) { "рядок#$it" },
        )
    }

    // ── причина відмови сервера доходить до веба ─────────────────────────────

    @Test
    fun `відмова сервера несе свою причину кодом, а не плоске refused`() = runTest {
        val api = FakeApi()
        api.answer = VenueClient.Answer.Refused(422, "Оцінку вже поставлено", "Already rated", reason = "already_rated")
        val r = route(api, "POST", "/messenger/venues/0123456789abcdef/rate", """{"stars":5}""")
        assertEquals(422, r.status())
        assertEquals("already_rated", r.code())
        assertEquals("Оцінку вже поставлено", r["detail"]!!.jsonPrimitive.content)
        api.answer = VenueClient.Answer.Refused(422, null, null)
        assertEquals("refused", route(api, "POST", "/messenger/venues/0123456789abcdef/rate", """{"stars":5}""").code())
    }

    @Test
    fun `запити зі сторінок — читання й позначка показаних, крива позначка до сервера не йде`() {
        val api = FakeApi().apply { answer = VenueClient.Answer.Ok("""{"venues":[],"requests":[]}""") }
        assertEquals(200, route(api, "GET", "/messenger/venues/requests").status())
        assertEquals(200, route(api, "POST", "/messenger/venues/requests", """{"ack":["0123456789abcdef"]}""").status())
        val bad = route(api, "POST", "/messenger/venues/requests", """{"ack":"0123456789abcdef"}""")
        assertEquals(422 to "bad_ack", bad.status() to bad.code())
        assertEquals(listOf("requests:", "requests:0123456789abcdef"), api.asked)
    }
}
