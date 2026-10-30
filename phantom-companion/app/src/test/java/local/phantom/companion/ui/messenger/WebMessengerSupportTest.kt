package local.phantom.companion.ui.messenger

import kotlinx.coroutines.runBlocking
import local.phantom.companion.data.VenueClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WebMessengerSupportTest {

    private val sent = mutableListOf<Pair<String, JSONObject>>()

    private fun route(reply: (String) -> Pair<Int, String>?) = WebMessengerSupport(
        baseUrl = "https://api.example/",
        signer = { path, body -> WebMessengerSupport.Proof("PUB", "n".repeat(32), 1L, "sig:$path:${body.length}") },
        http = VenueClient.Http { _, url, json ->
            sent += url to JSONObject(json!!)
            reply(url)
        },
    )

    @Test
    fun `речення підпису збігається з сервером`() {
        assertEquals(
            "NP-SUPPORT-v1|/api/support/reports|PUB|" + "n".repeat(32) + "|1759852800000|" +
                "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a",
            WebMessengerSupport.sentence("/api/support/reports", "PUB", "n".repeat(32), 1759852800000L, "{}"),
        )
    }

    @Test
    fun `скарга йде на сервер підписаною і повертає його відповідь`() = runBlocking {
        val out = JSONObject(
            route { 200 to """{"id":"r1","status":"sent"}""" }
                .handle("POST", listOf("support", "reports"), """{"id":"r1"}"""),
        )
        assertTrue(out.getBoolean("ok"))
        assertEquals("sent", out.getJSONObject("data").getString("status"))
        val (url, env) = sent.single()
        assertEquals("https://api.example/api/support/reports", url)
        assertEquals("""{"id":"r1"}""", env.getString("body"))
        assertEquals("PUB", env.getString("pub_key"))
    }

    @Test
    fun `читання списку йде на list з порожнім тілом`() = runBlocking {
        route { 200 to "[]" }.handle("GET", listOf("support", "tickets"), null)
        assertEquals("https://api.example/api/support/tickets/list", sent.single().first)
        assertEquals("", sent.single().second.getString("body"))
    }

    @Test
    fun `сервер без ручок або без мережі — це очікування, не відмова`() = runBlocking {
        val notDeployed = JSONObject(route { 404 to """{"detail":"Not Found"}""" }.handle("GET", listOf("support", "reports"), null))
        assertEquals(503, notDeployed.getInt("status"))
        assertEquals("support_pending", notDeployed.getString("code"))
        val offline = JSONObject(route { null }.handle("POST", listOf("support", "crash"), "{}"))
        assertEquals("support_pending", offline.getString("code"))
        val missing = JSONObject(
            route { 404 to """{"detail":"report not found"}""" }
                .handle("POST", listOf("support", "reports", "r9", "appeal"), "{}"),
        )
        assertEquals(404, missing.getInt("status"))
    }

    @Test
    fun `годинник, черга сервера й постійна відмова розрізняються`() = runBlocking {
        suspend fun reply(status: Int, detail: String) =
            JSONObject(route { status to JSONObject().put("detail", detail).toString() }.handle("POST", listOf("support", "reports"), "{}"))
        val skew = reply(401, "заява не свіжа: годинник розійшовся більш ніж на 2 хвилини")
        assertEquals(401, skew.getInt("status"))
        assertEquals("clock_skew", skew.getString("code"))
        assertEquals("replay", reply(401, "цю заяву вже використано").getString("code"))
        val badKey = reply(401, "підпис пристрою не зійшовся")
        assertEquals("support_unauthorized", badKey.getString("code"))
        assertEquals("підпис пристрою не зійшовся", badKey.getString("detail"))
        assertEquals("support_unauthorized", reply(401, "bad device key").getString("code"))
        assertEquals("support_busy", reply(429, "rate limit exceeded").getString("code"))
        assertEquals("support_pending", reply(408, "timeout").getString("code"))
        assertEquals("support_pending", reply(504, "gateway").getString("code"))
        val refused = reply(413, "знімок завеликий")
        assertEquals(413, refused.getInt("status"))
        assertEquals("support_refused", refused.getString("code"))
        assertEquals("знімок завеликий", refused.getString("detail"))
    }

    @Test
    fun `завислий сервер стає очікуванням до межі мосту`() = kotlinx.coroutines.test.runTest {
        val slow = WebMessengerSupport(
            baseUrl = "https://api.example/",
            signer = { _, _ -> WebMessengerSupport.Proof("PUB", "n".repeat(32), 1L, "s") },
            http = VenueClient.Http { _, _, _ -> kotlinx.coroutines.awaitCancellation() },
        )
        assertTrue(WebMessengerSupport.DEADLINE_MS < 55_000L)
        val out = JSONObject(slow.handle("POST", listOf("support", "crash"), "{}"))
        assertEquals(WebMessengerSupport.DEADLINE_MS, testScheduler.currentTime)
        assertEquals("support_pending", out.getString("code"))
    }

    @Test
    fun `обмеження й їх оскарження йдуть на сервер підписаними`() = runBlocking {
        val r = route { 200 to "[]" }
        r.handle("POST", listOf("support", "notices"), """{"ids":["me-node"]}""")
        r.handle("POST", listOf("support", "notices", "7", "appeal"), """{"target":"me-node"}""")
        assertEquals(
            listOf("https://api.example/api/support/notices", "https://api.example/api/support/notices/7/appeal"),
            sent.map { it.first },
        )
        assertEquals("""{"ids":["me-node"]}""", sent.first().second.getString("body"))
        assertEquals(400, JSONObject(r.handle("POST", listOf("support", "notices", "x7", "appeal"), "{}")).getInt("status"))
    }

    @Test
    fun `чужий шлях і кривий id сервера не досягають`() = runBlocking {
        val r = route { 200 to "{}" }
        assertEquals(404, JSONObject(r.handle("DELETE", listOf("support", "reports"), null)).getInt("status"))
        assertEquals(400, JSONObject(r.handle("POST", listOf("support", "tickets", "a%2Fb", "messages"), "{}")).getInt("status"))
        assertEquals(emptyList<Pair<String, JSONObject>>(), sent)
    }
}
