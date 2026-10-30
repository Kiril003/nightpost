package local.phantom.companion.ui.messenger

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import local.phantom.companion.core.net.peer.PeerCard
import local.phantom.companion.core.net.peer.PeerGroup
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.data.PointsApi
import local.phantom.companion.data.PointsClaims
import local.phantom.companion.data.VenueApi
import local.phantom.companion.data.VenueClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Дорога сцени зустрічі від входу до сітки/сервера.
 *
 * Розвилка «метод + шлях → двері» тут — СПРАВЖНЯ: [WebMessengerScene] той самий
 * клас, що міст кличе одним рядком. Шлях і тіло запиту — рівно ті, що веб
 * шле за договором (`docs/scene/MEETUP_CONTRACT.md`): список викликів складено
 * з договору, а не вгадано, і вимір іде не пошуком літералів у вихідному коді, а
 * справжнім викликом з відповіддю, яку читає веб. У вихідному коді лишилось
 * лише те, що з JVM не викликати (Robolectric у `:app` немає): рядок мосту, що
 * кличе розвилку, і підключення контейнера.
 */
class SceneBridgeReachTest {

    // ── підміни: сервер, місце, гаманець, журнал ────────────────────────────

    private class Venues : VenueApi {
        val asked = mutableListOf<String>()
        override suspend fun near(areas: List<String>) = VenueClient.Answer.Ok("""{"venues":[]}""").also { asked += "near" }
        override suspend fun venue(id: String) = VenueClient.Answer.Ok("""{"id":"$id"}""").also { asked += "venue:$id" }
        override suspend fun rate(id: String, stars: Int) =
            VenueClient.Answer.Ok("""{"venue_id":"$id","rating":{"mean":$stars.0,"count":1}}""").also { asked += "rate:$id:$stars" }
    }

    private class Points : PointsApi {
        val asked = mutableListOf<String>()
        override suspend fun checkin(meetupId: String, venueId: String, cell: String, meetupAtMs: Long) =
            VenueClient.Answer.Ok("""{"checked_in":true,"awarded":[]}""").also { asked += "checkin" }
        override suspend fun settled(cluster: String, tx: String, address: String, ref: String, walletSign: (ByteArray) -> ByteArray) =
            VenueClient.Answer.Ok("""{"status":"confirmed","awarded":[]}""").also { asked += "settled" }
        override suspend fun me() = VenueClient.Answer.Ok("""{"total":0,"events":[]}""").also { asked += "me" }
    }

    private val org = "a1a1a1a1a1a1a1a1"
    private val olena = "b2b2b2b2b2b2b2b2"
    private val group = PeerGroup.Group("0f0f0f0f0f0f0f0f", "Cafe X · 19:00", listOf(org, olena))
    private val card = PeerCard.encode(PeerCard.Body(PeerCard.Card.MEETUP, """{"v":1,"id":"m1"}"""))!!
    private val cardLine = PeerMessage("11111111-1111-1111-1111-111111111111", org, olena, PeerGroup.encode(group, card), 1_760_000_000_000L)

    private class World {
        val venues = Venues()
        val points = Points()
        val claims = PointsClaims(points)
        val log = mutableListOf<PeerMessage>()
        val sent = mutableListOf<Pair<String, String>>()
    }

    private fun scene(w: World, here: WebMessengerVenues.Here = WebMessengerVenues.Here.Point(50.45, 30.52)): WebMessengerScene {
        w.log += cardLine
        val v = WebMessengerVenues(w.venues, here = { here }, words = { it.name }, english = { false })
        val p = WebMessengerPoints(
            w.points, w.claims, here = { here }, wallet = { WebMessengerPoints.Wallet("1".repeat(32), "devnet") },
            words = { it.name }, english = { false },
        )
        val m = WebMessengerCardMarkDoor(
            log = { w.log.toList() }, me = { olena }, keyed = { setOf(org) }, scene = { setOf(org) },
            send = { to, frame -> w.sent += to to frame; PeerMessage("s${w.sent.size}", olena, to, frame, 1_760_000_100_000L).also { w.log += it } },
            words = { "рядок#$it" },
        )
        return WebMessengerScene(v, p, m)
    }

    private fun call(scene: WebMessengerScene, method: String, path: String, body: String? = null): JsonObject? {
        val seg = path.trim('/').split('/')
        if (!scene.claims(method, seg)) return null
        return Json.parseToJsonElement(runBlocking { scene.handle(method, seg, body) }).jsonObject
    }

    private fun JsonObject.status() = this["status"]!!.jsonPrimitive.int

    private val meetupId = "0f0f0f0f-1111-4222-8333-444444444444"
    private val tx = "5".repeat(88)
    private val bill = "7a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"

    /** Виклики сцени, які веб робить за договором: (метод, шлях, тіло). */
    private val webCalls = listOf(
        Triple("GET", "/messenger/venues/near", null),
        Triple("GET", "/messenger/venues/0123456789abcdef", null),
        Triple("POST", "/messenger/venues/0123456789abcdef/rate", """{"stars":4}"""),
        Triple("POST", "/messenger/points/checkin", """{"meetup_id":"$meetupId","venue_id":"0123456789abcdef","at_ms":1759860000000}"""),
        Triple("POST", "/messenger/points/payment", """{"signature":"$tx","ref":"$bill"}"""),
        Triple("GET", "/messenger/points/me", null),
        Triple("POST", "/messenger/messages/${cardLine.id}/marks", """{"value":"rsvp:yes","on":true}"""),
    )

    @Test
    fun `кожен виклик веба доходить до справжніх дверей і дає відповідь 200`() {
        for ((method, path, body) in webCalls) {
            val w = World()
            val r = call(scene(w), method, path, body) ?: fail("міст не пустив $method $path у сцену") as Nothing
            assertEquals("$method $path → ${r}", 200, r.status())
        }
    }

    @Test
    fun `кожне з дверей справді робить своє — сервер, сервер, дріт`() {
        val w = World()
        val s = scene(w)
        call(s, "GET", "/messenger/venues/near")
        call(s, "POST", "/messenger/venues/0123456789abcdef/rate", """{"stars":4}""")
        call(s, "POST", "/messenger/points/checkin", """{"meetup_id":"$meetupId","venue_id":"0123456789abcdef","at_ms":1}""")
        call(s, "POST", "/messenger/points/payment", """{"signature":"$tx","ref":"$bill"}""")
        call(s, "GET", "/messenger/points/me")
        assertEquals(listOf("near", "rate:0123456789abcdef:4"), w.venues.asked)
        // Оплату міст лише ПРИЙМАЄ: підпис гаманця — відбиток, його просить Профіль, не веб.
        assertEquals(listOf("checkin", "me"), w.points.asked)
        assertEquals(PointsClaims.Claim("devnet", tx, "1".repeat(32), bill), w.claims.pending.value)
        assertTrue("дотик по картці ще не пішов", w.sent.isEmpty())
        call(s, "POST", "/messenger/messages/${cardLine.id}/marks", """{"value":"rsvp:yes","on":true}""")
        assertEquals(listOf(org), w.sent.map { it.first })
    }

    @Test
    fun `вигадані шляхи й чужі методи не належать сцені`() {
        val s = scene(World())
        // Шлях під points «свій», але дверей на ньому немає: 404 зі свого класу, не сервер.
        assertEquals(404, call(s, "POST", "/messenger/points/withdraw")!!.status())
        assertEquals(404, call(s, "GET", "/messenger/points/checkin")!!.status())
        assertEquals(404, call(s, "DELETE", "/messenger/venues/0123456789abcdef")!!.status())
        // Дотик — лише POST і лише на /marks.
        assertFalse(s.claims("GET", listOf("messenger", "messages", "x", "marks")))
        assertFalse(s.claims("POST", listOf("messenger", "messages", "x", "reactions")))
        assertFalse(s.claims("POST", listOf("messenger", "messages", "x", "marks", "extra")))
        assertFalse(s.claims("POST", listOf("messenger", "conversations", "x", "marks")))
        assertFalse(s.claims("GET", listOf("messenger", "conversations", "x", "messages")))
    }

    @Test
    fun `без місця сцена не питає сервер`() {
        val w = World()
        val s = scene(w, here = WebMessengerVenues.Here.None)
        assertEquals(409, call(s, "GET", "/messenger/venues/near")!!.status())
        assertEquals(409, call(s, "POST", "/messenger/points/checkin", """{"meetup_id":"$meetupId","venue_id":"v","at_ms":1}""")!!.status())
        assertTrue(w.venues.asked.isEmpty() && w.points.asked.isEmpty())
    }

    @Test
    fun `договір називає ті самі шлях і тіло дотику, що перевіряє тест`() {
        val contract = File(repoRoot().parentFile, "docs/scene/MEETUP_CONTRACT.md").readText()
        assertTrue(contract.contains("POST /messenger/messages/{id}/marks {value, on}"))
        assertTrue(WebMessengerScene.isMark("POST", listOf("messenger", "messages", "{id}", "marks")))
    }

    // ── те, що з JVM не викликати: підключення мосту й контейнера ───────────

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("корінь дерева не знайдено") as Nothing
    }

    private fun source(rel: String): String =
        File(repoRoot(), "app/src/main/java/local/phantom/companion/$rel").readLines()
            .filterNot { val t = it.trimStart(); t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") }
            .joinToString("\n")

    @Test
    fun `міст кличе розвилку сцени одним рядком і будує двері з контейнера`() {
        val bridge = source("ui/messenger/WebMessengerBridge.kt")
        assertTrue(
            "міст не пускає шляхи сцени у WebMessengerScene",
            bridge.contains("if (scene.claims(method, seg)) return scene.handle(method, seg, body)"),
        )
        assertTrue("WebMessengerScene зібрано не з тих дверей", bridge.contains("WebMessengerScene(venues, points, cardMarks)"))
        assertTrue("заклади — не з контейнера", bridge.contains("api = container.venues"))
        assertTrue("бали — не з контейнера", bridge.contains("api = container.points"))
        assertTrue("місце — не з воріт місця", bridge.contains("container.locationSnapshotProvider()"))
        assertTrue("адреса гаманця — не з гаманця телефона", bridge.contains("SolanaLayer.state.value"))
        assertTrue("дотик ходить не в дріт мосту", bridge.contains("send = { to, frame -> container.mesh.send(to, frame) }"))
        assertTrue("кому слати позначку, вирішує не оголошення в профілі", bridge.contains("it.sceneLevel >= PeerProfile.SCENE_V1"))
    }

    @Test
    fun `заклади й бали підписує ключ заяв, а двері сцени йдуть через прапорець`() {
        val container = source("data/AppContainer.kt")
        assertEquals(
            "заклади й бали мають підписувати ключем заяв (№6.4)",
            2, Regex("VenueClient\\.deviceSigner\\(claims::prove\\)").findAll(container).count(),
        )
        assertEquals(
            "обидва клієнти мають брати адресу через прапорець сцени",
            2, Regex("VenueClient\\.sceneBase\\(").findAll(container).count(),
        )
        val venue = source("data/VenueClient.kt")
        assertEquals("ключ особи підписує лише доказ власника сторінки (B1 3b), не заяви", 1, Regex("Ed25519\\.sign").findAll(venue).count())
        assertTrue(venue.substringAfter("fun ownerSigner(").substringBefore("\n        }\n").contains("Ed25519.sign"))
        assertTrue("заяви сцени підписує не ClaimSigner", source("data/ClaimSigner.kt").contains("fun prove(claim: DeviceClaim)"))
        assertFalse("PeerMesh знову підписує заяви сцени", source("data/PeerMesh.kt").contains("proveClaim"))
    }

    @Test
    fun `хост кличе двері чернетки зустрічі і не вважає її взятою, доки веб не відповів`() {
        val host = source("ui/messenger/WebMessengerHost.kt")
        assertTrue("хост не слухає черги чернеток", host.contains("WebMessengerMeetupDraft.pending"))
        assertTrue("хост не віддає чернетку у веб", host.contains("WebMessengerMeetupDraft.deliver(ask"))
        assertTrue("хост не виконує сценарій у WebView", host.contains("webView.evaluateJavascript(script)"))
        assertTrue(
            "двері чернетки названо не так, як їх відчиняє веб",
            source("ui/messenger/WebMessengerMeetupDraft.kt").contains("window.__phantomOpenMeetupDraft"),
        )
    }

    @Test
    fun `дельта журналу знає, до якої розмови належить позначка картки`() {
        assertTrue(
            "зміна журналу не доходить до відкритої групи",
            source("ui/messenger/WebMessengerDeltas.kt").contains("WebMessengerCardMarks.targetOf(m)"),
        )
        assertTrue("хост не ходить дельтою", source("ui/messenger/WebMessengerHost.kt").contains("WebMessengerDeltas.touched(was, next, me)"))
    }
}
