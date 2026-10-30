package local.phantom.companion.ui.messenger

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import local.phantom.companion.core.truth.H3
import local.phantom.companion.data.PointsApi
import local.phantom.companion.data.PointsClaims
import local.phantom.companion.data.VenueClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `/messenger/points/…` — двері веба до балів.
 *
 * Доводиться: веб НЕ дає ні координат, ні адреси гаманця (клітинку рахує
 * телефон зі свого місця, адресу бере зі свого гаманця); без чесного місця чи
 * гаманця заяви немає; сервер не питають кривим тілом; а шляхів «купити /
 * переказати / вивести бали» немає зовсім.
 *
 * Оплата йде інакше, ніж відмітка: сервер доводить її ще й підписом самого
 * гаманця, а це відбиток. Тому міст оплату лише ПРИЙМАЄ в [PointsClaims]
 * (веб отримує «ще не зараховано»), нічого не підписує і сервера не питає —
 * відбиток просить Профіль, коли людина натисне «Зарахувати».
 */
class WebMessengerPointsTest {

    private class FakeApi : PointsApi {
        val asked = mutableListOf<String>()
        var answer: VenueClient.Answer = VenueClient.Answer.Ok("""{"checked_in":true,"awarded":[],"waiting_for":1}""")
        override suspend fun checkin(meetupId: String, venueId: String, cell: String, meetupAtMs: Long): VenueClient.Answer {
            asked += "checkin:$meetupId:$venueId:$cell:$meetupAtMs"; return answer
        }
        override suspend fun settled(
            cluster: String, tx: String, address: String, ref: String, walletSign: (ByteArray) -> ByteArray,
        ): VenueClient.Answer {
            asked += "settled:$cluster:$tx:$address:$ref"; return answer
        }
        override suspend fun me(): VenueClient.Answer {
            asked += "me"; return answer
        }
    }

    private val kyiv = WebMessengerVenues.Here.Point(50.4501, 30.5234)
    private val meetup = "0f0f0f0f-1111-4222-8333-444444444444"
    private val tx = "5".repeat(88)
    private val addr = "1".repeat(32)
    private val bill = "7a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"

    private fun route(
        api: FakeApi,
        method: String,
        path: String,
        body: String? = null,
        here: WebMessengerVenues.Here = kyiv,
        wallet: WebMessengerPoints.Wallet? = WebMessengerPoints.Wallet(addr, "devnet"),
        claims: PointsClaims = PointsClaims(api),
    ): JsonObject {
        val points = WebMessengerPoints(api, claims, here = { here }, wallet = { wallet }, words = { it.name }, english = { false })
        val seg = path.trim('/').split('/')
        assertTrue("міст не впізнав свій шлях $path", WebMessengerPoints.claims(seg))
        return Json.parseToJsonElement(runBlocking { points.handle(method, seg, body) }).jsonObject
    }

    private fun JsonObject.status() = this["status"]!!.jsonPrimitive.int
    private fun JsonObject.code() = this["code"]?.jsonPrimitive?.content

    @Test
    fun `шлях балів свій, чужих не бере`() {
        assertTrue(WebMessengerPoints.claims(listOf("messenger", "points", "me")))
        assertFalse(WebMessengerPoints.claims(listOf("messenger", "points")))
        assertFalse(WebMessengerPoints.claims(listOf("messenger", "venues", "points")))
    }

    @Test
    fun `відмітка — клітинка r8 з місця телефона, не з веба`() {
        val api = FakeApi()
        val r = route(api, "POST", "/messenger/points/checkin", """{"meetup_id":"$meetup","venue_id":"0123456789abcdef","at_ms":1759860000000,"lat":1.0,"lon":2.0}""")
        assertEquals(200, r.status())
        assertEquals(1, r["data"]!!.jsonObject["waiting_for"]!!.jsonPrimitive.int)
        val cell = H3.latLngToCell(kyiv.lat, kyiv.lon, H3.CELL_RES)
        assertEquals(listOf("checkin:$meetup:0123456789abcdef:$cell:1759860000000"), api.asked)
    }

    @Test
    fun `без чесного місця відмітки немає — і сервер не питають`() {
        val api = FakeApi()
        val body = """{"meetup_id":"$meetup","venue_id":"0123456789abcdef","at_ms":1}"""
        route(api, "POST", "/messenger/points/checkin", body, here = WebMessengerVenues.Here.None).let {
            assertEquals(409, it.status()); assertEquals("no_fix", it.code())
        }
        route(api, "POST", "/messenger/points/checkin", body, here = WebMessengerVenues.Here.Refused("місце підмінено", "spoofed")).let {
            assertEquals(409, it.status()); assertEquals("місце підмінено", it["detail"]!!.jsonPrimitive.content)
        }
        assertTrue(api.asked.isEmpty())
    }

    @Test
    fun `криве тіло відмітки не доходить до сервера`() {
        val api = FakeApi()
        for (bad in listOf(null, "", "nope", "{}", """{"meetup_id":"$meetup","venue_id":"v"}""",
            """{"meetup_id":"$meetup","venue_id":"v","at_ms":"1"}""", """{"meetup_id":"$meetup","venue_id":"v","at_ms":1.5}""",
            """{"meetup_id":"$meetup","venue_id":"v","at_ms":-1}""", """{"meetup_id":7,"venue_id":"v","at_ms":1}""")) {
            val r = route(api, "POST", "/messenger/points/checkin", bad)
            assertEquals("тіло $bad", 422, r.status())
            assertEquals("bad_body", r.code())
        }
        assertTrue(api.asked.isEmpty())
    }

    @Test
    fun `оплата — міст приймає заявку, нічого не підписує й сервера не питає`() {
        val api = FakeApi()
        val claims = PointsClaims(api)
        val r = route(api, "POST", "/messenger/points/payment", """{"signature":"$tx","ref":"$bill","address":"EVIL"}""", claims = claims)
        assertEquals(200, r.status())
        // Веб бачить «ще не зараховано» і повторить пізніше, а не «зроблено».
        assertEquals("pending", r["data"]!!.jsonObject["status"]!!.jsonPrimitive.content)
        assertEquals("confirm", r["data"]!!.jsonObject["awaiting"]!!.jsonPrimitive.content)
        assertTrue("міст пішов на сервер сам: ${api.asked}", api.asked.isEmpty())
        // Адреса й мережа — з гаманця телефона, а не з тіла від веба.
        assertEquals(PointsClaims.Claim("devnet", tx, addr, bill), claims.pending.value)
    }

    @Test
    fun `оплата, що вже зарахована, відповідає веб «підтверджено» без нових балів`() {
        val api = FakeApi()
        val counted = setOf(tx)
        val claims = PointsClaims(api, object : PointsClaims.Memory {
            override fun counted() = counted
            override fun remember(tx: String) = Unit
        })
        val r = route(api, "POST", "/messenger/points/payment", """{"signature":"$tx","ref":"$bill"}""", claims = claims)
        assertEquals(200, r.status())
        assertEquals("confirmed", r["data"]!!.jsonObject["status"]!!.jsonPrimitive.content)
        assertEquals(0, r["data"]!!.jsonObject["awarded"]!!.jsonArray.size)
        assertEquals(null, claims.pending.value)
    }

    @Test
    fun `оплата не з devnet чи не з вбудованого гаманця — остаточне «ні», заявки немає`() {
        val api = FakeApi()
        val claims = PointsClaims(api)
        val body = """{"signature":"$tx","ref":"$bill"}"""
        route(api, "POST", "/messenger/points/payment", body, wallet = WebMessengerPoints.Wallet(addr, "mainnet"), claims = claims).let {
            assertEquals(422, it.status()); assertEquals("not_devnet", it.code())
        }
        route(api, "POST", "/messenger/points/payment", body, wallet = WebMessengerPoints.Wallet(addr, "devnet", builtIn = false), claims = claims).let {
            assertEquals(422, it.status()); assertEquals("built_in_only", it.code())
        }
        assertEquals(null, claims.pending.value)
        assertTrue(api.asked.isEmpty())
    }

    @Test
    fun `без гаманця, підпису чи id рахунку заяви немає`() {
        val api = FakeApi()
        val claims = PointsClaims(api)
        route(api, "POST", "/messenger/points/payment", """{"signature":"$tx","ref":"$bill"}""", wallet = null, claims = claims).let {
            assertEquals(409, it.status()); assertEquals("no_wallet", it.code())
        }
        for (bad in listOf("""{"ref":"$bill"}""", """{"signature":"$tx"}""", """{"signature":"$tx","ref":"bill-1"}""", """{"signature":"$tx","ref":7}""", "nope")) {
            val r = route(api, "POST", "/messenger/points/payment", bad, claims = claims)
            assertEquals("тіло $bad", 422, r.status())
            assertEquals("bad_body", r.code())
        }
        assertEquals(null, claims.pending.value)
        assertTrue(api.asked.isEmpty())
    }

    @Test
    fun `остаточна відмова сервера на «Зарахувати» доходить до веба як 422 — повторювати марно`() = runBlocking {
        val api = FakeApi().apply { answer = VenueClient.Answer.Refused(422, "Не ваш платіж", "Not your payment") }
        val claims = PointsClaims(api)
        route(api, "POST", "/messenger/points/payment", """{"signature":"$tx","ref":"$bill"}""", claims = claims)
        claims.settle(addr) { ByteArray(64) }
        val again = route(api, "POST", "/messenger/points/payment", """{"signature":"$tx","ref":"$bill"}""", claims = claims)
        assertEquals(422, again.status())
        assertEquals("refused", again.code())
        assertEquals("Не ваш платіж", again["detail"]!!.jsonPrimitive.content)
        Unit
    }

    @Test
    fun `свої бали — GET me, відповідь сервера як є`() {
        val api = FakeApi().apply { answer = VenueClient.Answer.Ok("""{"total":15,"events":[{"kind":"meetup.happened"}]}""") }
        val r = route(api, "GET", "/messenger/points/me")
        assertEquals(15, r["data"]!!.jsonObject["total"]!!.jsonPrimitive.int)
        assertEquals(listOf("me"), api.asked)
    }

    @Test
    fun `відмови мають код і речення, а 429 — своє`() {
        val api = FakeApi()
        val body = """{"meetup_id":"$meetup","venue_id":"0123456789abcdef","at_ms":1}"""
        api.answer = VenueClient.Answer.Refused(422, "Не в закладі", "Not at the venue")
        route(api, "POST", "/messenger/points/checkin", body).let {
            assertEquals(422, it.status()); assertEquals("refused", it.code()); assertEquals("Не в закладі", it["detail"]!!.jsonPrimitive.content)
        }
        api.answer = VenueClient.Answer.Refused(429, null, null)
        assertEquals("limited", route(api, "POST", "/messenger/points/checkin", body).code())
        api.answer = VenueClient.Answer.NotEnabled
        assertEquals(503, route(api, "GET", "/messenger/points/me").status())
        api.answer = VenueClient.Answer.Unreachable
        assertEquals(502, route(api, "GET", "/messenger/points/me").status())
        api.answer = VenueClient.Answer.NoKey
        assertEquals("no_key", route(api, "GET", "/messenger/points/me").code())
        api.answer = VenueClient.Answer.Ok("not json")
        assertEquals("bad_answer", route(api, "GET", "/messenger/points/me").code())
    }

    @Test
    fun `дверей купити, переказати чи вивести бали немає`() {
        val api = FakeApi()
        for ((method, path) in listOf(
            "POST" to "/messenger/points/buy", "POST" to "/messenger/points/transfer", "POST" to "/messenger/points/withdraw",
            "POST" to "/messenger/points/me", "GET" to "/messenger/points/checkin", "DELETE" to "/messenger/points/me",
        )) {
            assertEquals("$method $path", 404, route(api, method, path, "{}").status())
        }
        assertTrue(api.asked.isEmpty())
    }

    @Test
    fun `причина відмови сервера їде кодом — веб розрізняє, що саме не так`() {
        val api = FakeApi()
        val body = """{"meetup_id":"$meetup","venue_id":"0123456789abcdef","at_ms":1}"""
        for (reason in listOf("not_at_venue", "outside_window", "venue_unknown")) {
            api.answer = VenueClient.Answer.Refused(422, "Ви не там", "Not there", reason)
            val r = route(api, "POST", "/messenger/points/checkin", body)
            assertEquals(422, r.status())
            assertEquals(reason, r.code())
            assertEquals("Ви не там", r["detail"]!!.jsonPrimitive.content)
        }
        api.answer = VenueClient.Answer.Refused(422, null, null)
        assertEquals("refused", route(api, "POST", "/messenger/points/checkin", body).code())
    }

    @Test
    fun `оплату чужого гаманця веб отримує з кодом причини, і більше її не повторює`() = runBlocking {
        val api = FakeApi()
        val claims = PointsClaims(api)
        val pay = """{"signature":"$tx","ref":"$bill"}"""
        assertEquals("pending", route(api, "POST", "/messenger/points/payment", pay, claims = claims)["data"]!!.jsonObject["status"]!!.jsonPrimitive.content)
        // Людина натиснула «Зарахувати» у Профілі; сервер сказав «гаманець чужий».
        api.answer = VenueClient.Answer.Refused(422, "Гаманець чужий", "Wallet taken", "wallet_taken")
        claims.settle(addr) { ByteArray(64) }
        val again = route(api, "POST", "/messenger/points/payment", pay, claims = claims)
        assertEquals(422, again.status())
        assertEquals("wallet_taken", again.code())
        assertEquals("Гаманець чужий", again["detail"]!!.jsonPrimitive.content)
    }

    @Test
    fun `мережа Solana не відповіла — відмова з причиною, а не «сервер не ввімкнено»`() {
        val api = FakeApi()
        api.answer = VenueClient.Answer.Refused(503, "Мережа Solana не відповіла", "Network down", "network_unavailable")
        val r = route(api, "GET", "/messenger/points/me")
        assertEquals(503, r.status())
        assertEquals("network_unavailable", r.code())
    }
}
