package local.phantom.companion.feature.stream.ui.map.peers

import java.io.File
import local.phantom.companion.core.data.peers.PeerBox
import local.phantom.companion.core.data.peers.PeerMotion
import local.phantom.companion.core.data.peers.PeerNear
import local.phantom.companion.core.data.peers.PeerPoint
import local.phantom.companion.core.data.peers.PeerPrecision
import local.phantom.companion.core.data.peers.PeerShare
import local.phantom.companion.core.data.peers.PeerSpot
import local.phantom.companion.core.data.peers.ShareMode
import local.phantom.companion.feature.stream.R
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PeopleOnMapTest {

    private val now = 1_790_000_000_000L
    private val box = PeerBox(50.44, 30.50, 50.47, 30.54)

    private fun where(
        id: String,
        lat: Double = 50.45,
        lon: Double = 30.52,
        precision: PeerPrecision = PeerPrecision.EXACT,
        box: PeerBox? = null,
        atMs: Long = now,
        near: PeerNear? = null,
        motion: PeerMotion? = null,
        spot: PeerSpot? = null,
        low: Boolean = false,
    ) = PeerPoint(
        nodeId = "where:$id", threadId = id, name = "Людина $id", lat = lat, lon = lon, atMs = atMs,
        share = PeerShare(ShareMode.WHERE, now + 15 * 60_000L), precision = precision, box = box,
        near = near, motion = motion, spot = spot, lowBattery = low,
    )

    private fun marks(json: String) = JSONObject(json).getJSONArray("features").let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }.filter { it.getJSONObject("geometry").getString("type") == "Point" }
    }

    /** Очікування пораховані веб-функцією `toneIndex` (styles/formTokens.ts) — FNV-1a по UTF-16, mod 6. */
    @Test
    fun `тон людини на мапі — той самий, що в месенджері`() {
        assertEquals(3, PersonTone.index("0123456789abcdef"))
        assertEquals(1, PersonTone.index("fedcba9876543210"))
        assertEquals(3, PersonTone.index("a1b2c3d4e5f60718"))
        assertEquals(2, PersonTone.index("oksana"))
        assertEquals(5, PersonTone.index("Київ 😀"))
        val web = File("../../phantom-os/src/frontend/src/styles/formTokens.ts").readText()
        assertEquals(
            "веб і мапа мусять ділити людей на ту саму кількість тонів",
            PersonTone.COUNT,
            Regex("PEOPLE_TONES = (\\d+);").find(web)!!.groupValues[1].toInt(),
        )
    }

    /**
     * Палітра — дзеркало `docs/design/system/tokens.json › person` (з 1d3dddb1,
     * «плоскі люди»): до редизайну тони жили в form.css між мітками
     * `>>> PEOPLE`, тепер їх друкує генератор токенів із цього файла.
     */
    @Test
    fun `палітра тонів — дзеркало tokens json`() {
        val person = JSONObject(File("../../docs/design/system/tokens.json").readText()).getJSONObject("person")
        fun argb(hex: String) = hex.removePrefix("#").toLong(16).toInt() or 0xFF000000.toInt()
        listOf("night" to true, "day" to false).forEach { (tag, night) ->
            val tones = person.getJSONArray(tag)
            assertEquals("$tag: скільки тонів", PersonTone.COUNT, tones.length())
            for (i in 0 until PersonTone.COUNT) {
                val t = PersonTone.of(i, night)
                assertEquals("$tag $i fill", argb(tones.getJSONObject(i).getString("fill")), t.fill)
                assertEquals("$tag $i ink", argb(tones.getJSONObject(i).getString("ink")), t.ink)
            }
        }
    }

    @Test
    fun `двоє в одній клітинці й двоє за 50 метрів — дві групи, а далекий сам`() {
        val json = peerWhereGeoJson(
            listOf(
                where("a", precision = PeerPrecision.DISTRICT, box = box),
                where("b", precision = PeerPrecision.DISTRICT, box = box),
                where("c", lat = 50.4500, lon = 30.5200),
                where("d", lat = 50.4504, lon = 30.5200),
                where("e", lat = 50.4600, lon = 30.5200),
            ),
            now,
        )
        val m = marks(json)
        assertEquals(3, m.size)
        val counts = m.map { it.getJSONObject("properties").getInt("count") }.sorted()
        assertEquals(listOf(1, 2, 2), counts)
        val group = m.first { it.getJSONObject("properties").getInt("count") == 2 && it.getJSONObject("properties").getString("members").contains("where:c") }
        assertEquals("where:c|where:d", group.getJSONObject("properties").getString("members"))
    }

    @Test
    fun `пляма й точка поруч не зливаються — центр плями — не місце людини`() {
        val m = marks(peerWhereGeoJson(listOf(where("a", precision = PeerPrecision.DISTRICT, box = box, lat = 50.455, lon = 30.52), where("b", lat = 50.455, lon = 30.52)), now))
        assertEquals(2, m.size)
    }

    @Test
    fun `значок — сідає телефон важливіше за машину, а тиша — без значка`() {
        assertEquals("low", peerBadge(where("a", motion = PeerMotion.VEHICLE, low = true)))
        assertEquals("car", peerBadge(where("a", motion = PeerMotion.VEHICLE)))
        assertEquals("foot", peerBadge(where("a", motion = PeerMotion.FOOT)))
        assertEquals("", peerBadge(where("a", motion = PeerMotion.STILL)))
    }

    @Test
    fun `точне місце несе координати до картки — не нуль`() {
        val p = marks(peerWhereGeoJson(listOf(where("a", lat = 50.4512, lon = 30.5234)), now)).single().getJSONObject("properties")
        assertEquals(50.4512, p.getDouble("lat"), 1e-9)
        assertEquals(30.5234, p.getDouble("lon"), 1e-9)
    }

    @Test
    fun `стан на картці — дім першим, застаріле — лише «давно не оновлювалось»`() {
        val home = where("a", spot = PeerSpot.HOME, motion = PeerMotion.FOOT, low = true).tapped(now)
        assertEquals(listOf(R.string.peer_state_home, R.string.peer_state_low_battery), peerStateWords(home, now))
        val old = where("a", spot = PeerSpot.HOME, atMs = now - 40 * 60_000L).tapped(now)
        assertEquals(listOf(R.string.peer_state_stale), peerStateWords(old, now))
        assertEquals(listOf(R.string.peer_state_vehicle), peerStateWords(where("a", motion = PeerMotion.VEHICLE).tapped(now), now))
    }

    @Test
    fun `поруч — лише свої, свіжі й справді поруч`() {
        val near = peersNear(
            listOf(
                where("far", near = PeerNear.FAR),
                where("same", near = PeerNear.SAME),
                where("old", near = PeerNear.NEAR, atMs = now - 20 * 60_000L),
                where("near", near = PeerNear.NEAR),
            ),
            now,
        )
        assertEquals(listOf("same", "near"), near.map { it.threadId })
    }

    @Test
    fun `мій світ ляже на мапу лише коли людина ввімкнула`() {
        val off = peerWhereGeoJson(emptyList(), now)
        val on = peerWhereGeoJson(emptyList(), now, listOf(box))
        assertTrue(!off.contains("\"world\""))
        assertTrue(on.contains("\"world\":true"))
    }

    @Test
    fun `до ранку — це до восьмої, а не на вісім годин`() {
        val zone = java.util.TimeZone.getTimeZone("Europe/Kyiv")
        val c = java.util.Calendar.getInstance(zone).apply { set(2026, 8, 28, 23, 30, 0) }
        val morning = java.util.Calendar.getInstance(zone).apply { timeInMillis = nextMorningMs(c.timeInMillis, zone) }
        assertEquals(29, morning.get(java.util.Calendar.DAY_OF_MONTH))
        assertEquals(8, morning.get(java.util.Calendar.HOUR_OF_DAY))
    }

    @Test
    fun `зустріч — хто на місці, хто в дорозі, а про пляму — словом, без метрів`() {
        val m = Meeting(50.4500, 30.5200, listOf("a" to "А", "b" to "Б", "c" to "В", "d" to "Г"), now)
        val rows = meetRows(
            m,
            listOf(
                where("a", lat = 50.4505, lon = 30.5200),
                where("b", lat = 50.4700, lon = 30.5200, motion = PeerMotion.VEHICLE),
                where("c", precision = PeerPrecision.DISTRICT, box = box),
            ),
            now,
        )
        assertEquals(listOf(MeetState.THERE, MeetState.ON_WAY, MeetState.NEAR, MeetState.UNSEEN), rows.map { it.state })
        assertEquals(2_226.0, rows[1].metres!!, 30.0)
        assertEquals(null, rows[2].metres)
    }

    @Test
    fun `посередині — лише коли хтось відомий, і рахується з мене`() {
        assertEquals(null, meetMiddle(50.0 to 30.0, listOf("x"), emptyList(), now))
        val mid = meetMiddle(50.0 to 30.0, listOf("a"), listOf(where("a", lat = 50.2, lon = 30.4)), now)!!
        assertEquals(50.1, mid.first, 1e-9)
        assertEquals(30.2, mid.second, 1e-9)
    }

    @Test
    fun `коло безпеки — тривога над людиною першою, мовчазний — не «тиша»`() {
        val rows = safetyCircle(
            listOf(
                where("kyiv", lat = 50.45),
                where("lviv", lat = 49.84),
                where("old", lat = 50.45, atMs = now - 45 * 60_000L),
            ),
            now,
            oblastAt = { lat, _ -> if (lat > 50) "Київська" else "Львівська" },
            alertOn = { it == "Київська" },
        )
        assertEquals(listOf("kyiv", "lviv", "old"), rows.map { it.peer.threadId })
        assertEquals(listOf(CircleSky.ALERT, CircleSky.QUIET, CircleSky.SILENT), rows.map { it.sky })
        assertEquals("Львівська", rows[1].oblast)
    }

    @Test
    fun `коло безпеки — не свої точки й прострочений показ не входять`() {
        val session = where("s").copy(share = PeerShare(ShareMode.LIVE, now + 60_000L), precision = null)
        val gone = where("g").copy(share = PeerShare(ShareMode.WHERE, now - 1))
        assertTrue(safetyCircle(listOf(session, gone), now, { _, _ -> "Київська" }, { true }).isEmpty())
    }
}
