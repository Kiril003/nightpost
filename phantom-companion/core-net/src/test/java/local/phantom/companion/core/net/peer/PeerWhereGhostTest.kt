package local.phantom.companion.core.net.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

private const val T0 = 1_790_000_000_000L

/**
 * Привид: від кого сховались, той бачить те саме, що від телефона без супутників —
 * ті самі слоти, та сама довжина, останнє місце з його старою миттю, далі тиша.
 */
class PeerWhereGhostTest {

    private val exact = PeerWhere.Precision.EXACT
    private val rnd = Random(7)
    private val walk = PeerWhere.State(PeerWhere.Motion.FOOT)

    private fun start(): PeerWhereBeacon.State {
        var s = PeerWhereBeacon.grant(PeerWhereBeacon.State(), whereFriend("aaaa"), exact, T0, rnd)!!.state
        s = PeerWhereBeacon.grant(s, whereFriend("bbbb"), exact, T0, rnd)!!.state
        return PeerWhereBeacon.onFix(s, PeerWhereBeacon.Fix(50.45, 30.52, 5, T0, walk), T0, rnd).state
    }

    private fun drive(s0: PeerWhereBeacon.State, fromMs: Long, toMs: Long): Pair<PeerWhereBeacon.State, List<Pair<Long, PeerWhereBeacon.Out>>> {
        var s = s0
        val out = ArrayList<Pair<Long, PeerWhereBeacon.Out>>()
        var t = fromMs
        while (t < toMs) {
            val lat = 50.45 + (t - T0) / 60_000.0 * 0.0005
            val step = PeerWhereBeacon.onFix(s, PeerWhereBeacon.Fix(lat, 30.52, 5, t, PeerWhere.State(PeerWhere.Motion.VEHICLE)), t, rnd)
            s = step.state
            out += step.out.map { t to it }
            val next = PeerWhereBeacon.nextWakeMs(s, t)
            assertTrue("годинник будиться в минуле", next == null || next > t)
            t += 30_000L
        }
        return s to out
    }

    @Test
    fun `схований бачить старе місце в ті самі слоти, решта — нове`() {
        val hidden = PeerWhereBeacon.hide(start(), "aaaa", T0 + 3 * 3_600_000L)
        val (_, out) = drive(hidden, T0 + 30_000L, T0 + 90 * 60_000L)
        val toA = out.filter { it.second.peerId == "aaaa" }
        val toB = out.filter { it.second.peerId == "bbbb" }
        assertTrue(toB.isNotEmpty())
        assertEquals("слоти розійшлись — привида видно з часу", toB.map { it.first }, toA.map { it.first })
        assertEquals(setOf(toB.first().second.body.length), (toA + toB).map { it.second.body.length }.toSet())
        toA.forEach {
            val p = PeerWhere.decode(it.second.body) as PeerWhere.Point
            assertEquals(T0, p.atMs)
            assertEquals(50.45, p.lat, 1e-7)
            assertEquals("стан теж застиг", walk, p.state)
        }
        assertTrue(toB.all { (PeerWhere.decode(it.second.body) as PeerWhere.Point).atMs > T0 })
    }

    @Test
    fun `після двох годин схований не отримує нічого, а потім знову все`() {
        val until = T0 + 3 * 3_600_000L
        val (mid, late) = drive(PeerWhereBeacon.hide(start(), "aaaa", until), T0 + 30_000L, T0 + PeerWhereBeacon.HOLD_MAX_AGE_MS + 60_000L)
        val tail = late.filter { it.first > T0 + PeerWhereBeacon.HOLD_MAX_AGE_MS }
        assertTrue(tail.none { it.second.peerId == "aaaa" })
        val (_, after) = drive(mid, until, until + 30 * 60_000L)
        val back = after.filter { it.second.peerId == "aaaa" }.map { PeerWhere.decode(it.second.body) as PeerWhere.Point }
        assertTrue(back.isNotEmpty())
        assertTrue(back.all { it.atMs >= until })
    }

    @Test
    fun `привид від усіх накриває й нового друга, а «де ти?» не дає свіжого`() {
        var s = PeerWhereBeacon.hide(start(), null, T0 + 3_600_000L)
        val t = T0 + 10 * 60_000L
        s = PeerWhereBeacon.onFix(s, PeerWhereBeacon.Fix(50.5, 30.6, 5, t), t, rnd).state
        val asked = PeerWhereBeacon.onAsk(s, "aaaa", t, rnd)
        asked.out.forEach { assertEquals(T0, (PeerWhere.decode(it.body) as PeerWhere.Point).atMs) }
        val fresh = PeerWhereBeacon.grant(asked.state, whereFriend("cccc"), exact, t, rnd)!!
        assertTrue("новий друг отримав свіже місце під привидом", fresh.out.none { it.peerId == "cccc" })
        assertNotEquals(t, PeerWhereBeacon.nextWakeMs(fresh.state, t))
    }

    @Test
    fun `зменшити точність під привидом — стерти, а не віддати свіже`() {
        val s = PeerWhereBeacon.hide(start(), "aaaa", T0 + 3_600_000L)
        val t = T0 + 60_000L
        val moved = PeerWhereBeacon.onFix(s, PeerWhereBeacon.Fix(50.5, 30.6, 5, t), t, rnd).state
        val step = PeerWhereBeacon.grant(moved, whereFriend("aaaa"), PeerWhere.Precision.DISTRICT, t, rnd)!!
        val frames = step.out.filter { it.peerId == "aaaa" }.map { PeerWhere.decode(it.body) }
        assertEquals(1, frames.size)
        assertTrue(frames.single() is PeerWhere.Erase)
        assertFalse(PeerWhereBeacon.hidden(step.state, step.state.lanes.getValue("aaaa"), t + 3_600_000L))
        assertTrue(PeerWhereBeacon.hidden(step.state, step.state.lanes.getValue("aaaa"), t))
    }
}
