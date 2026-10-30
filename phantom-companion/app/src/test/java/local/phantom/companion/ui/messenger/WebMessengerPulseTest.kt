package local.phantom.companion.ui.messenger

import local.phantom.companion.core.data.peers.PeerNear
import local.phantom.companion.core.data.peers.PeerPoint
import local.phantom.companion.core.data.peers.PeerPrecision
import local.phantom.companion.core.data.peers.PeerShare
import local.phantom.companion.core.data.peers.ShareMode
import local.phantom.companion.feature.stream.ui.map.AlertSky
import local.phantom.companion.feature.stream.ui.map.AlertState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Жива смуга каже лише те, що має джерело. Кожен тест тут — одна з
 * відсутностей, яку легко переплутати з «спокійно» чи «нікого».
 */
class WebMessengerPulseTest {

    private val now = 1_800_000_000_000L
    private val kyiv = "Київська область"
    private val short: (String) -> String = { it.removeSuffix(" область") }

    private fun sky(active: Boolean, fetchedAgoMs: Long?, since: Long? = now - 12 * 60_000L) = AlertSky(
        oblasts = mapOf(kyiv to AlertState(active = active, changedEpochMs = since)),
        ownOblast = kyiv,
        fetchedAtMs = fetchedAgoMs?.let { now - it },
    )

    @Test
    fun `тривога в області людини при свіжій стрічці — слово з областю й початком`() {
        val s = WebMessengerPulse.sky(sky(active = true, fetchedAgoMs = 30_000L), now, short)
        assertEquals("Київська", s?.oblast)
        assertEquals(now - 12 * 60_000L, s?.sinceMs)
        assertEquals(false, s?.feedSilent)
    }

    @Test
    fun `тихо — смуга мовчить, а не каже «спокійно»`() {
        assertNull(WebMessengerPulse.sky(sky(active = false, fetchedAgoMs = 30_000L), now, short))
    }

    @Test
    fun `стрічку не читали жодного разу — мовчання, не тривога і не спокій`() {
        assertNull(WebMessengerPulse.sky(sky(active = true, fetchedAgoMs = null), now, short))
    }

    @Test
    fun `піднята тривога над мовчазною стрічкою лишається, але з позначкою`() {
        val s = WebMessengerPulse.sky(sky(active = true, fetchedAgoMs = 3_600_000L), now, short)
        assertEquals(true, s?.feedSilent)
    }

    @Test
    fun `область не відома — тривоги в «вашій області» немає`() {
        val s = AlertSky(oblasts = mapOf(kyiv to AlertState(true, now)), ownOblast = null, fetchedAtMs = now)
        assertNull(WebMessengerPulse.sky(s, now, short))
    }

    @Test
    fun `час початку з майбутнього не видається за «щойно»`() {
        val s = WebMessengerPulse.sky(sky(active = true, fetchedAgoMs = 1_000L, since = now + 60_000L), now, short)
        assertNull(s?.sinceMs)
    }

    private fun point(
        peer: String,
        mode: ShareMode = ShareMode.WHERE,
        near: PeerNear? = PeerNear.NEAR,
        expires: Long? = now + 600_000L,
        at: Long = now - 60_000L,
        precision: PeerPrecision = PeerPrecision.QUARTER,
        node: String = "where:$peer",
    ) = PeerPoint(
        nodeId = node,
        threadId = peer,
        name = peer.uppercase(),
        lat = 50.45,
        lon = 30.52,
        atMs = at,
        share = PeerShare(mode, expires),
        precision = precision,
        near = near,
    )

    @Test
    fun `лише ваші, лише WHERE, лише не протерміновані`() {
        val got = WebMessengerPulse.near(
            listOf(
                point("a"),
                point("stand-debug"),
                point("b", mode = ShareMode.LIVE, node = "live:b"),
                point("c", expires = now - 1),
            ),
            people = setOf("a", "b", "c"),
            nowMs = now,
        )
        assertEquals(listOf("a"), got.map { it.peerId })
        assertEquals("near", got.single().near)
        assertEquals("quarter", got.single().precision)
    }

    @Test
    fun `ближчі перші, серед рівних — свіжіші`() {
        val got = WebMessengerPulse.near(
            listOf(
                point("far", near = PeerNear.FAR),
                point("old", near = PeerNear.SAME, at = now - 600_000L),
                point("new", near = PeerNear.SAME, at = now - 10_000L),
                point("unknown", near = null),
            ),
            people = setOf("far", "old", "new", "unknown"),
            nowMs = now,
        )
        assertEquals(listOf("new", "old", "far", "unknown"), got.map { it.peerId })
    }

    @Test
    fun `нікого — порожньо, без заглушок`() {
        assertTrue(WebMessengerPulse.near(emptyList(), setOf("a"), now).isEmpty())
    }

    @Test
    fun `фокус мапи за точністю, протермінований — немає`() {
        val pts = listOf(point("d", precision = PeerPrecision.DISTRICT), point("x", expires = now - 5))
        assertEquals(12.0, WebMessengerPulse.focusOf(pts, "d", now)!!.zoom, 0.0)
        assertNull(WebMessengerPulse.focusOf(pts, "x", now))
        assertNull(WebMessengerPulse.focusOf(pts, "nobody", now))
    }
}
