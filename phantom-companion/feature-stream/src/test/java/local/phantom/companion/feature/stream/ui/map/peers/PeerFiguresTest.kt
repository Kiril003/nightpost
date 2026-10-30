package local.phantom.companion.feature.stream.ui.map.peers

import java.util.TimeZone
import local.phantom.companion.core.data.peers.PEER_FRESH_WINDOW_MS
import local.phantom.companion.core.data.peers.PEER_LIVE_FRESH_WINDOW_MS
import local.phantom.companion.core.data.peers.PeerPoint
import local.phantom.companion.core.data.peers.PeerShare
import local.phantom.companion.core.data.peers.ShareMode
import local.phantom.companion.feature.stream.ui.map.icons.marks.MarkBox
import local.phantom.companion.feature.stream.ui.map.icons.marks.MarkPen
import local.phantom.companion.feature.stream.ui.map.icons.marks.MarkTone
import local.phantom.companion.feature.stream.ui.map.icons.marks.RecordingMarkPen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Що доїжджає від точки людини до шару стилю — і що мусить загубитись дорогою.
 */
class PeerFiguresTest {

    private val now = 1_724_500_000_000L

    private fun peer(
        nodeId: String = "n1",
        name: String = "Олег",
        atMs: Long = now,
        verified: Boolean = true,
        stale: Boolean = false,
        expiresAtMs: Long? = null,
        lat: Double = 50.4722,
        lon: Double = 30.3985,
        threadId: String = "t1",
    ) = PeerPoint(
        nodeId = nodeId,
        threadId = threadId,
        name = name,
        lat = lat,
        lon = lon,
        atMs = atMs,
        verified = verified,
        stale = stale,
        share = PeerShare(ShareMode.LIVE, expiresAtMs),
    )

    // ── сцена ────────────────────────────────────────────────────────────

    @Test
    fun `a point keeps its place, in GeoJSON order`() {
        val json = peerFiguresGeoJson(peerFigures(listOf(peer()), now))
        // GeoJSON — це [довгота, широта]. Переставлені числа кладуть київський
        // контакт у Казахстан: лічильник каже «є», а екрана фігурка не торкається.
        assertTrue(json, json.contains("\"coordinates\":[30.3985,50.4722]"))
    }

    @Test
    fun `verification rides through as form, and so does age`() {
        val figures = peerFigures(
            listOf(
                peer(nodeId = "a", verified = true),
                peer(nodeId = "b", verified = false, atMs = now - PEER_LIVE_FRESH_WINDOW_MS - 1_000L),
            ),
            now,
        )
        assertEquals(listOf(true, false), figures.map { it.verified })
        assertEquals(listOf(true, false), figures.map { it.fresh })

        val json = peerFiguresGeoJson(figures)
        assertTrue(json.contains("\"verified\":true"))
        assertTrue(json.contains("\"verified\":false"))
        assertTrue(json.contains("\"fresh\":true"))
        assertTrue(json.contains("\"fresh\":false"))
    }

    @Test
    fun `a point sealed stale by the mailbox never reads live, however recent`() {
        // Закон №3: точка зі скриньки народжується застарілою — навіть якщо
        // доїхала за секунду після виміру.
        val figure = peerFigures(listOf(peer(atMs = now, stale = true)), now).single()
        assertFalse(figure.fresh)
    }

    @Test
    fun `age is measured against the measurement, not against the reader`() {
        val p = peer(atMs = now - PEER_LIVE_FRESH_WINDOW_MS + 1_000L)
        assertTrue(peerFigures(listOf(p), now).single().fresh)
        assertFalse("вік тікає сам, без нових точок", peerFigures(listOf(p), now + 2_000L).single().fresh)
    }

    @Test
    fun `a live tick and a one-shot place age by different clocks`() {
        // Живий сеанс домовився з приймачем про свій крок мовчання; разове
        // місце з розмови не обіцяло оновитись узагалі, тож судиться вужчим
        // вікном. Одне число на обох блідило б людину, яка чесно стоїть.
        val age = PEER_FRESH_WINDOW_MS + 1_000L
        val live = peer(nodeId = "live", atMs = now - age)
        val once = live.copy(nodeId = "once", share = PeerShare(ShareMode.ONE_SHOT))
        assertTrue(peerFigures(listOf(live), now).single().fresh)
        assertFalse(peerFigures(listOf(once), now).single().fresh)
    }

    @Test
    fun `an expired share is not drawn, even before the sweep reaches it`() {
        val alive = peer(nodeId = "a", expiresAtMs = now + 1)
        val done = peer(nodeId = "b", expiresAtMs = now)
        assertEquals(listOf("a"), peerFigures(listOf(alive, done), now).map { it.nodeId })
    }

    @Test
    fun `threadId is plumbed all the way to the layer, with no UI on it yet`() {
        // Фільтр груп — хвиля 4. Поле мусить доїхати вже зараз, інакше та хвиля
        // почнеться з переробки цього файлу.
        val json = peerFiguresGeoJson(peerFigures(listOf(peer(threadId = "група-3")), now))
        assertTrue(json, json.contains("\"threadId\":\"група-3\""))
    }

    @Test
    fun `an empty flow builds an empty collection, and invents nothing`() {
        val json = peerFiguresGeoJson(peerFigures(emptyList(), now))
        assertEquals("""{"type":"FeatureCollection","features":[]}""", json)
    }

    @Test
    fun `a name with a quote cannot break the collection`() {
        val json = peerFiguresGeoJson(peerFigures(listOf(peer(name = "Оле\"г\n")), now))
        assertFalse(json.contains("\"Оле\"г"))
        assertEquals(json.count { it == '{' }, json.count { it == '}' })
    }

    // ── фігурка ──────────────────────────────────────────────────────────

    @Test
    fun `both figures stand on the same ground, inside the same box`() {
        listOf("суцільна" to PeerBody.solid(), "контурна" to PeerBody.outline())
            .forEach { (what, program) ->
                val pen = RecordingMarkPen()
                program(pen)
                assertTrue("$what нічого не намалювала", pen.ops > 0)
                assertTrue("$what вилізла ліворуч за коробку", pen.minX >= 0f)
                assertTrue("$what вилізла праворуч за коробку", pen.maxX <= MarkBox.W)
                assertTrue("$what висить над землею", pen.maxY >= MarkBox.BASELINE - 2f)
                assertTrue("$what провалилась крізь землю", pen.maxY <= MarkBox.BASELINE)
            }
    }

    @Test
    fun `verification is form, not colour — the unverified figure is hollow`() {
        val solid = FormPen().also { PeerBody.solid()(it) }
        val outline = FormPen().also { PeerBody.outline()(it) }

        assertTrue("звірений контакт мусить бути залитим тілом", solid.fills > 0)
        assertEquals("незвірений мусить лишитись порожнім — інакше різниця лише в кольорі", 0, outline.fills)
        assertTrue("контур без обводу — це відсутність знака", outline.strokes >= 4)
    }

    @Test
    fun `both figures speak one family tone — the difference is never the colour`() {
        val solid = RecordingMarkPen().also { PeerBody.solid()(it) }
        val outline = RecordingMarkPen().also { PeerBody.outline()(it) }
        assertTrue("родинний тон загублено", MarkTone.BONE in solid.tones)
        assertTrue(outline.tones.isNotEmpty() && outline.tones.all { it == MarkTone.BONE })
    }

    // ── картка ───────────────────────────────────────────────────────────

    @Test
    fun `as-of reads the measurement clock, in the reader's own hours`() {
        val kyiv = TimeZone.getTimeZone("Europe/Kyiv")
        // 2024-08-24 14:32 за київським часом.
        assertEquals("14:32", peerClockOf(1_724_499_120_000L, kyiv))
        assertEquals("11:32", peerClockOf(1_724_499_120_000L, TimeZone.getTimeZone("UTC")))
    }
}

/**
 * Перо, яке рахує ЗАЛИВКИ окремо від ОБВОДІВ. `RecordingMarkPen` міряє
 * геометрію і на цьому питанні мовчить, а саме воно тут і є законом: різниця
 * між звіреним і незвіреним мусить бути формою, а не тоном.
 */
private class FormPen : MarkPen {
    var fills = 0
        private set
    var strokes = 0
        private set

    override fun poly(tone: MarkTone, vararg pts: Float) { fills++; strokes++ }
    override fun plane(tone: MarkTone, vararg pts: Float) { fills++ }
    override fun edge(vararg pts: Float) { strokes++ }
    override fun thin(tone: MarkTone, width: Float, vararg pts: Float) { fills++; strokes++ }
    override fun box(tone: MarkTone, x: Float, y: Float, w: Float, h: Float, radius: Float) {
        fills++
        strokes++
    }
    override fun plate(tone: MarkTone, x: Float, y: Float, w: Float, h: Float, radius: Float) { fills++ }
    override fun boxEdge(x: Float, y: Float, w: Float, h: Float, radius: Float) { strokes++ }
    override fun disc(tone: MarkTone, cx: Float, cy: Float, r: Float) { fills++; strokes++ }
    override fun dot(tone: MarkTone, cx: Float, cy: Float, r: Float) { fills++ }
    override fun oval(tone: MarkTone, cx: Float, cy: Float, rx: Float, ry: Float) { fills++; strokes++ }
    override fun line(tone: MarkTone, width: Float, vararg pts: Float) { strokes++ }

    override fun clipped(clip: FloatArray, block: MarkPen.() -> Unit) = absorb(block)

    override fun rotated(degrees: Float, pivotX: Float, pivotY: Float, block: MarkPen.() -> Unit) =
        absorb(block)

    private fun absorb(block: MarkPen.() -> Unit) {
        val inner = FormPen()
        inner.block()
        fills += inner.fills
        strokes += inner.strokes
    }
}
