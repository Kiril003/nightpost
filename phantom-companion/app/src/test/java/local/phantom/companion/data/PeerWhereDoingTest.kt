package local.phantom.companion.data

import local.phantom.companion.core.ai.context.LocationFix
import local.phantom.companion.core.net.peer.PeerWhere
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerWhereDoingTest {

    private val home = PeerWhereDoing.Anchor(50.4500, 30.5200, PeerWhere.Spot.HOME)
    private val work = PeerWhereDoing.Anchor(50.4600, 30.5300, PeerWhere.Spot.WORK)
    private val full = PeerWhereDoing.Battery(80, charging = false)

    private fun at(lat: Double, lon: Double, speed: Float?, acc: Float = 10f) = LocationFix(lat, lon, accuracyM = acc, speedMps = speed)

    @Test
    fun `під вікнами пішки — вдома, за кермом — ні`() {
        assertEquals(PeerWhere.Spot.HOME, PeerWhereDoing.of(at(50.4503, 30.5200, 0.2f), listOf(home, work), full, false).spot)
        assertEquals(PeerWhere.Spot.NONE, PeerWhereDoing.of(at(50.4503, 30.5200, 14f), listOf(home, work), full, false).spot)
        assertEquals(PeerWhere.Spot.WORK, PeerWhereDoing.of(at(50.4601, 30.5301, 1.2f), listOf(home, work), full, false).spot)
        assertEquals("за квартал від дому — не вдома", PeerWhere.Spot.NONE, PeerWhereDoing.of(at(50.4540, 30.5200, 0.2f), listOf(home), full, false).spot)
    }

    @Test
    fun `рух зі швидкості виміру, без неї — не вигадуємо`() {
        assertEquals(PeerWhere.Motion.UNKNOWN, PeerWhereDoing.of(at(1.0, 1.0, null), emptyList(), full, false).motion)
        assertEquals(PeerWhere.Motion.STILL, PeerWhereDoing.of(at(1.0, 1.0, 0.1f), emptyList(), full, false).motion)
        assertEquals(PeerWhere.Motion.FOOT, PeerWhereDoing.of(at(1.0, 1.0, 1.4f), emptyList(), full, false).motion)
        assertEquals(PeerWhere.Motion.VEHICLE, PeerWhereDoing.of(at(1.0, 1.0, 12f), emptyList(), full, false).motion)
    }

    @Test
    fun `про батарею лише з дозволу, лише коли сідає і не на зарядці`() {
        val low = PeerWhereDoing.Battery(9, charging = false)
        assertFalse(PeerWhereDoing.of(at(1.0, 1.0, null), emptyList(), low, shareLowBattery = false).lowBattery)
        assertTrue(PeerWhereDoing.of(at(1.0, 1.0, null), emptyList(), low, shareLowBattery = true).lowBattery)
        assertFalse(PeerWhereDoing.of(at(1.0, 1.0, null), emptyList(), low.copy(charging = true), true).lowBattery)
        assertFalse(PeerWhereDoing.of(at(1.0, 1.0, null), emptyList(), full, true).lowBattery)
    }
}
