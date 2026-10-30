package local.phantom.companion.core.net.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerLiveTest {

    private val fix = PeerLive.Fix(
        lat = 50.45010,
        lon = 30.52340,
        accuracyM = 12,
        atMs = 1_725_381_000_000L,
        expiresAtMs = 1_725_384_600_000L,
        seq = 7,
    )

    @Test
    fun `положення туди й назад`() {
        val wire = PeerLive.encodeFix(fix)
        assertNotNull(wire)
        assertEquals(fix, PeerLive.decodeFix(wire!!))
    }

    @Test
    fun `точність не обовʼязкова`() {
        val wire = PeerLive.encodeFix(fix.copy(accuracyM = null))!!
        assertNull(PeerLive.decodeFix(wire)!!.accuracyM)
    }

    @Test
    fun `лист без строку не збирається`() {
        assertNull(PeerLive.encodeFix(fix.copy(expiresAtMs = 0L)))
    }

    @Test
    fun `координата поза межами землі не їде`() {
        assertNull(PeerLive.encodeFix(fix.copy(lat = 91.0)))
        assertNull(PeerLive.decodeFix("PH-LIV:91.00000,30.00000;;1;2;0"))
    }

    @Test
    fun `обрізаний кадр — не половина точки`() {
        val wire = PeerLive.encodeFix(fix)!!
        assertNull(PeerLive.decodeFix(wire.substringBeforeLast(';')))
    }

    @Test
    fun `звичайне слово не прикидається положенням`() {
        assertFalse(PeerLive.isFix("я на місці, 50.45, 30.52"))
        assertNull(PeerLive.decodeFix("PH-LOC:50.45010,30.52340;12"))
    }

    @Test
    fun `відклик туди й назад`() {
        val wire = PeerLive.encodeStop(1_725_381_000_000L)
        assertTrue(PeerLive.isStop(wire))
        assertEquals(1_725_381_000_000L, PeerLive.decodeStop(wire))
    }

    @Test
    fun `тік і відклик — вантаж, маршрут — сказане`() {
        assertTrue(PeerLive.isCargo(PeerLive.encodeFix(fix)!!))
        assertTrue(PeerLive.isCargo(PeerLive.encodeStop(1L)))
        assertFalse(PeerLive.isCargo(PeerLive.encodeRoute(route)!!))
        assertFalse(PeerLive.isCargo("привіт"))
    }

    // ── маршрут ──────────────────────────────────────────────────────────

    private val route = PeerLive.Route(
        expiresAtMs = 1_725_384_600_000L,
        distanceM = 4_310.0,
        durationS = 812L,
        profile = "foot",
        waypoints = listOf(50.4501 to 30.5234, 50.4610 to 30.5401),
        shape = listOf(
            50.4501 to 30.5234,
            50.4530 to 30.5280,
            50.4570 to 30.5330,
            50.4610 to 30.5401,
        ),
    )

    @Test
    fun `маршрут туди й назад`() {
        val wire = PeerLive.encodeRoute(route)!!
        val back = PeerLive.decodeRoute(wire)!!
        assertEquals(route.expiresAtMs, back.expiresAtMs)
        assertEquals(route.distanceM, back.distanceM, 0.5)
        assertEquals(route.durationS, back.durationS)
        assertEquals("foot", back.profile)
        assertEquals(route.waypoints.size, back.waypoints.size)
        assertEquals(route.shape.size, back.shape.size)
        // Точність 5 знаків — це ~1,1 м; більшого маршрутна ламана не заявляє.
        route.shape.zip(back.shape).forEach { (a, b) ->
            assertEquals(a.first, b.first, 1e-5)
            assertEquals(a.second, b.second, 1e-5)
        }
    }

    @Test
    fun `маршрут без строку не збирається`() {
        assertNull(PeerLive.encodeRoute(route.copy(expiresAtMs = 0L)))
    }

    @Test
    fun `маршрут з однієї точки — не маршрут`() {
        assertNull(PeerLive.encodeRoute(route.copy(waypoints = listOf(50.45 to 30.52))))
        assertNull(PeerLive.encodeRoute(route.copy(shape = listOf(50.45 to 30.52))))
    }

    @Test
    fun `довга ламана проріджується, кінці лишаються на місці`() {
        val long = (0 until 5_000).map { 50.0 + it * 1e-4 to 30.0 + it * 1e-4 }
        val thin = PeerLive.thin(long)
        assertEquals(PeerLive.MAX_SHAPE_POINTS, thin.size)
        assertEquals(long.first(), thin.first())
        assertEquals(long.last(), thin.last())
    }

    @Test
    fun `найдовший маршрут пролізає у кадр`() {
        val long = (0 until 20_000).map { 50.0 + it * 1e-4 to 30.0 + it * 1e-4 }
        val wire = PeerLive.encodeRoute(
            route.copy(
                shape = long,
                waypoints = (0 until PeerLive.MAX_WAYPOINTS).map { 50.0 + it to 30.0 },
            ),
        )!!
        assertTrue(
            "маршрут мусить лишати запас під base64 конверта: ${wire.length}",
            wire.toByteArray(Charsets.UTF_8).size < PeerWire.MAX_BODY_BYTES / 3,
        )
        assertEquals(PeerLive.MAX_SHAPE_POINTS, PeerLive.decodeRoute(wire)!!.shape.size)
    }

    @Test
    fun `зіпсута ламана дає порожнечу, не випадкові координати`() {
        assertTrue(PeerLive.decodePath("це не ламана").isEmpty())
        assertTrue(PeerLive.decodePath(PeerLive.encodePath(route.shape).dropLast(1)).isEmpty())
    }

    @Test
    fun `роздільники кадру не трапляються в ламаній`() {
        // Абетка кодування — символи 63..126; ані ';', ані ',' у неї не входять,
        // і саме тому кадр можна різати ними без екранування.
        val dense = (0 until 2_000).map {
            -89.0 + it * 0.08 to -179.0 + it * 0.17
        }
        val text = PeerLive.encodePath(dense)
        assertFalse(text.contains(';'))
        assertFalse(text.contains(','))
        assertEquals(dense.size, PeerLive.decodePath(text).size)
    }
}
