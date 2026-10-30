package local.phantom.companion.core.net.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerLiveInboxTest {

    private val t0 = 1_725_381_000_000L
    private val until = t0 + 900_000L

    private fun tick(atMs: Long, lat: Double = 50.45, seq: Int = 0, expires: Long = until) =
        PeerLive.encodeFix(
            PeerLive.Fix(lat = lat, lon = 30.52, accuracyM = 10, atMs = atMs, expiresAtMs = expires, seq = seq),
        )!!

    private fun row(
        id: String,
        body: String,
        from: String = "them",
        to: String = "me",
        sentAtMs: Long = t0,
        state: PeerMessage.State = PeerMessage.State.QUEUED,
    ) = PeerMessage(id = id, fromId = from, toId = to, body = body, sentAtMs = sentAtMs, state = state)

    @Test
    fun `положення лягає й читається`() {
        val after = PeerLiveInbox.absorb(emptyMap(), "them", tick(t0), t0)
        assertEquals(1, after.size)
        assertEquals(50.45, after.getValue("them").lat, 1e-6)
    }

    @Test
    fun `історія не зберігається — сто тіків лишають одну точку`() {
        var standing = emptyMap<String, PeerLiveInbox.Standing>()
        repeat(100) { i ->
            standing = PeerLiveInbox.absorb(
                standing,
                "them",
                tick(atMs = t0 + i * 1_000L, lat = 50.45 + i * 1e-4, seq = i),
                t0 + i * 1_000L,
            )
        }
        assertEquals(1, standing.size)
        assertEquals(50.45 + 99 * 1e-4, standing.getValue("them").lat, 1e-6)
        assertEquals(99, standing.getValue("them").seq)
    }

    @Test
    fun `протухле відкидається, навіть щойно прийнявши`() {
        val standing = PeerLiveInbox.absorb(emptyMap(), "them", tick(t0, expires = t0 - 1L), t0)
        assertTrue(standing.isEmpty())
    }

    @Test
    fun `протухле не збиває живого`() {
        val live = PeerLiveInbox.absorb(emptyMap(), "them", tick(t0), t0)
        val after = PeerLiveInbox.absorb(live, "them", tick(t0 + 1, expires = t0), t0 + 1)
        assertEquals(1, after.size)
        assertEquals(t0, after.getValue("them").atMs)
    }

    @Test
    fun `старіша точка поверх новішої — рух, якого не було`() {
        val live = PeerLiveInbox.absorb(emptyMap(), "them", tick(t0 + 60_000L, lat = 50.46), t0)
        val after = PeerLiveInbox.absorb(live, "them", tick(t0, lat = 49.0), t0)
        assertEquals(50.46, after.getValue("them").lat, 1e-6)
    }

    @Test
    fun `відклик прибирає точку`() {
        val live = PeerLiveInbox.absorb(emptyMap(), "them", tick(t0), t0)
        val after = PeerLiveInbox.absorb(live, "them", PeerLive.encodeStop(t0 + 1), t0 + 1)
        assertTrue(after.isEmpty())
    }

    @Test
    fun `відклик одного не гасить іншого`() {
        var s = PeerLiveInbox.absorb(emptyMap(), "a", tick(t0), t0)
        s = PeerLiveInbox.absorb(s, "b", tick(t0), t0)
        s = PeerLiveInbox.absorb(s, "a", PeerLive.encodeStop(t0), t0)
        assertEquals(setOf("b"), s.keys)
    }

    @Test
    fun `строк гасить точку і без відклику`() {
        val live = PeerLiveInbox.absorb(emptyMap(), "them", tick(t0), t0)
        assertEquals(1, PeerLiveInbox.sweep(live, until - 1).size)
        assertTrue(PeerLiveInbox.sweep(live, until).isEmpty())
    }

    @Test
    fun `звичайне слово не рухає піна`() {
        val live = PeerLiveInbox.absorb(emptyMap(), "them", tick(t0), t0)
        assertEquals(live, PeerLiveInbox.absorb(live, "them", "я на місці", t0))
    }

    @Test
    fun `лист без відправника не приймається`() {
        assertTrue(PeerLiveInbox.absorb(emptyMap(), "", tick(t0), t0).isEmpty())
    }

    // ── журнал: після строку не лишається ЖОДНОГО запису ─────────────────

    @Test
    fun `протухлий тік зникає з журналу, а не тільки з екрана`() {
        val log = listOf(row("m1", tick(t0)))
        assertTrue(PeerLiveInbox.spent(log, until - 1).isEmpty())
        assertEquals(setOf("m1"), PeerLiveInbox.spent(log, until))
        assertTrue(PeerOutbox.delete(log, PeerLiveInbox.spent(log, until)).isEmpty())
    }

    @Test
    fun `після строку в журналі не лишається жодного сліду чужого положення`() {
        // Ціла сесія: сорок тіків і відклик. Через добу — порожньо.
        val log = (0 until 40).map { i ->
            row("m$i", tick(atMs = t0 + i * 60_000L, seq = i, expires = t0 + i * 60_000L + 900_000L), sentAtMs = t0 + i * 60_000L)
        } + row("stop", PeerLive.encodeStop(t0 + 40 * 60_000L), sentAtMs = t0 + 40 * 60_000L)
        val aDayLater = t0 + 24 * 3_600_000L
        val left = PeerOutbox.delete(log, PeerLiveInbox.spent(log, aDayLater))
        assertTrue("у журналі лишилось: ${left.map { it.body }}", left.isEmpty())
        assertNull(left.firstOrNull { PeerLive.decodeFix(it.body) != null })
    }

    @Test
    fun `черга тримає рівно один тік на людину й напрямок`() {
        val log = (0 until 30).map { i ->
            row("m$i", tick(atMs = t0 + i * 60_000L, seq = i, expires = until + i * 60_000L), sentAtMs = t0 + i * 60_000L)
        }
        val left = PeerOutbox.delete(log, PeerLiveInbox.spent(log, t0 + 60_000L))
        assertEquals(1, left.size)
        assertEquals("m29", left.single().id)
    }

    @Test
    fun `тіки в різні боки не витісняють одне одного`() {
        val log = listOf(
            row("in", tick(t0), from = "them", to = "me"),
            row("out", tick(t0), from = "me", to = "them"),
        )
        assertTrue(PeerLiveInbox.spent(log, t0).isEmpty())
    }

    @Test
    fun `слово і фото сторож живого положення не чіпає`() {
        val log = listOf(row("word", "привіт"), row("chunk", "PH-FCH:" + "a".repeat(32) + ":0:1:AAAA"))
        assertTrue(PeerLiveInbox.spent(log, t0 + 10_000_000L).isEmpty())
    }

    @Test
    fun `тік не показується в розмові й не рахується непрочитаним`() {
        val log = listOf(row("word", "привіт"), row("m1", tick(t0)))
        assertEquals(listOf("word"), PeerOutbox.conversation(log, "them").map { it.id })
        assertEquals(1, PeerOutbox.unread(log, "them", 0L))
        assertEquals(0, PeerOutbox.waiting(listOf(row("m1", tick(t0)))))
    }

    // ── надісланий маршрут ───────────────────────────────────────────────

    private fun routeBody(expires: Long = t0 + PeerLive.ROUTE_LIFE_MS) = PeerLive.encodeRoute(
        PeerLive.Route(
            expiresAtMs = expires,
            distanceM = 4_200.0,
            durationS = 900L,
            profile = "car",
            waypoints = listOf(50.45 to 30.52, 50.47 to 30.55),
            shape = listOf(50.45 to 30.52, 50.46 to 30.54, 50.47 to 30.55),
        ),
    )!!

    @Test
    fun `маршрут лягає й читається назад`() {
        val after = PeerLiveInbox.absorbRoute(emptyMap(), "them", routeBody(), t0)

        assertEquals(1, after.size)
        assertEquals(3, after.getValue("them").route.shape.size)
    }

    @Test
    fun `протухлий маршрут не лягає навіть щойно принесений`() {
        // Строк відлічується від СКЛАДАННЯ на тому кінці, а не від доставки:
        // лист міг пролежати в сховку добу.
        val stale = PeerLiveInbox.absorbRoute(emptyMap(), "them", routeBody(expires = t0 - 1L), t0)

        assertTrue(stale.isEmpty())
    }

    @Test
    fun `мітла знімає маршрут за його власним строком`() {
        val deadline = t0 + 60_000L
        val held = PeerLiveInbox.absorbRoute(emptyMap(), "them", routeBody(expires = deadline), t0)

        assertEquals(1, PeerLiveInbox.sweepRoutes(held, deadline - 1L).size)
        assertTrue(PeerLiveInbox.sweepRoutes(held, deadline).isEmpty())
    }

    @Test
    fun `на людину припадає один маршрут, а не список`() {
        var held = emptyMap<String, PeerLiveInbox.StandingRoute>()
        repeat(20) { held = PeerLiveInbox.absorbRoute(held, "them", routeBody(), t0 + it) }

        assertEquals(1, held.size)
    }

    @Test
    fun `слово не робиться маршрутом`() {
        assertTrue(PeerLiveInbox.absorbRoute(emptyMap(), "them", "привіт", t0).isEmpty())
        assertTrue(PeerLiveInbox.absorbRoute(emptyMap(), "", routeBody(), t0).isEmpty())
    }
}
