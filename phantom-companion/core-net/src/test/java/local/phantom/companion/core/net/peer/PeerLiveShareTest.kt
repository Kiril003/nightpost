package local.phantom.companion.core.net.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerLiveShareTest {

    private val t0 = 1_725_381_000_000L

    @Test
    fun `чверть години й година мають межу, «до зупинки» — ні`() {
        assertEquals(t0 + 900_000L, PeerLiveShare.endsAtMs(PeerLiveShare.Span.QUARTER_HOUR, t0))
        assertEquals(t0 + 3_600_000L, PeerLiveShare.endsAtMs(PeerLiveShare.Span.HOUR, t0))
        assertNull(PeerLiveShare.endsAtMs(PeerLiveShare.Span.UNTIL_STOP, t0))
    }

    @Test
    fun `вісім годин мають тверду межу, а хвилини з веба мапляться на строк`() {
        assertEquals(t0 + 8 * 3_600_000L, PeerLiveShare.endsAtMs(PeerLiveShare.Span.EIGHT_HOURS, t0))
        assertEquals(PeerLiveShare.Span.QUARTER_HOUR, PeerLiveShare.spanFor(15))
        assertEquals(PeerLiveShare.Span.HOUR, PeerLiveShare.spanFor(60))
        assertEquals(PeerLiveShare.Span.EIGHT_HOURS, PeerLiveShare.spanFor(480))
        assertEquals(PeerLiveShare.Span.UNTIL_STOP, PeerLiveShare.spanFor(481))
    }

    @Test
    fun `строк листа є завжди, навіть «до зупинки»`() {
        val open = PeerLiveShare.open("p", PeerLiveShare.Span.UNTIL_STOP, t0)
        assertEquals(t0 + PeerLiveShare.ROLLING_MS, PeerLiveShare.expiryFor(open, t0))
        // Котиться разом з тіком: замовклий телефон гасне сам.
        assertEquals(
            t0 + 600_000L + PeerLiveShare.ROLLING_MS,
            PeerLiveShare.expiryFor(open, t0 + 600_000L),
        )
    }

    @Test
    fun `сеанс на чверть години закінчується сам`() {
        val open = PeerLiveShare.open("p", PeerLiveShare.Span.QUARTER_HOUR, t0)
        assertFalse(PeerLiveShare.over(open, t0 + 899_000L))
        assertTrue(PeerLiveShare.over(open, t0 + 900_000L))
        assertEquals(1_000L, PeerLiveShare.remainingMs(open, t0 + 899_000L))
        assertEquals(0L, PeerLiveShare.remainingMs(open, t0 + 999_000L))
        assertNull(PeerLiveShare.remainingMs(PeerLiveShare.open("p", PeerLiveShare.Span.UNTIL_STOP, t0), t0))
    }

    // ── дросель ──────────────────────────────────────────────────────────

    private fun started() = PeerLiveShare
        .open("p", PeerLiveShare.Span.HOUR, t0)
        .let { PeerLiveShare.noteSent(it, 50.4501, 30.5234, t0) }

    @Test
    fun `перший тік іде негайно`() {
        assertTrue(PeerLiveShare.shouldSend(PeerLiveShare.open("p", PeerLiveShare.Span.HOUR, t0), 50.45, 30.52, t0))
    }

    @Test
    fun `частіше за підлогу не буває, навіть у машині`() {
        // Кілометр за двадцять секунд — і все одно ні: підлога тримає батарею
        // обох телефонів і не дає сеансу перетворитись на потік.
        assertFalse(PeerLiveShare.shouldSend(started(), 50.4591, 30.5234, t0 + 20_000L))
    }

    @Test
    fun `зсув понад межу після підлоги — іде`() {
        val far = 50.4501 + 0.0009
        assertTrue(PeerLiveShare.shouldSend(started(), far, 30.5234, t0 + 50_000L))
    }

    @Test
    fun `дрижання на місці не їде`() {
        assertFalse(PeerLiveShare.shouldSend(started(), 50.45012, 30.52342, t0 + 60_000L))
    }

    @Test
    fun `той, хто стоїть, однаково озивається до стелі`() {
        assertTrue(PeerLiveShare.shouldSend(started(), 50.4501, 30.5234, t0 + PeerLiveShare.MAX_GAP_MS))
    }

    @Test
    fun `після кінця сеансу не їде нічого`() {
        val ended = PeerLiveShare.open("p", PeerLiveShare.Span.QUARTER_HOUR, t0)
        assertFalse(PeerLiveShare.shouldSend(ended, 49.0, 24.0, t0 + 900_001L))
    }

    @Test
    fun `лічильник тіків росте без дір`() {
        var s = PeerLiveShare.open("p", PeerLiveShare.Span.HOUR, t0)
        repeat(3) { s = PeerLiveShare.noteSent(s, 50.45, 30.52, t0 + it * 60_000L) }
        assertEquals(3, s.seq)
    }

    @Test
    fun `відстань міряється в метрах, не в градусах`() {
        // 0,001 градуса широти — це ~111 м; сотня метрів має читатись сотнею.
        assertEquals(111.0, PeerLiveShare.metersBetween(50.0, 30.0, 50.001, 30.0), 2.0)
        assertEquals(0.0, PeerLiveShare.metersBetween(50.0, 30.0, 50.0, 30.0), 0.001)
    }

    // ── чи дійде ─────────────────────────────────────────────────────────

    @Test
    fun `без ключа чекати марно і про це кажуть першим`() {
        assertEquals(
            PeerLiveShare.Reach.NO_KEY,
            PeerLiveShare.reach(hasKey = false, meshNow = true, relayOpen = true),
        )
    }

    @Test
    fun `телефон поруч важить більше за сховок`() {
        assertEquals(
            PeerLiveShare.Reach.MESH,
            PeerLiveShare.reach(hasKey = true, meshNow = true, relayOpen = true),
        )
        assertEquals(
            PeerLiveShare.Reach.RELAY,
            PeerLiveShare.reach(hasKey = true, meshNow = false, relayOpen = true),
        )
        assertEquals(
            PeerLiveShare.Reach.QUEUED,
            PeerLiveShare.reach(hasKey = true, meshNow = false, relayOpen = false),
        )
    }

    // ── чи є що везти ────────────────────────────────────────────────────

    @Test
    fun `сеанс без виміру й без жодної репліки нічого не обіцяє`() {
        assertFalse(
            PeerLiveShare.promises(
                banner(sent = false, position = PeerLiveShare.Position.SEARCHING),
            ),
        )
        assertFalse(
            PeerLiveShare.promises(
                banner(sent = false, position = PeerLiveShare.Position.NO_PERMISSION),
            ),
        )
    }

    @Test
    fun `щойно вимір є — або щойно бодай одна репліка поїхала — обіцяти можна`() {
        assertTrue(
            PeerLiveShare.promises(banner(sent = false, position = PeerLiveShare.Position.HELD)),
        )
        // Фікс міг зникнути (тунель), але те, що вже поїхало, не зникає: точка
        // на тому кінці далі старіє вголос за власним строком.
        assertTrue(
            PeerLiveShare.promises(banner(sent = true, position = PeerLiveShare.Position.SEARCHING)),
        )
    }

    private fun banner(sent: Boolean, position: PeerLiveShare.Position) = PeerLiveShare.Banner(
        peerId = "p",
        name = "Марта",
        span = PeerLiveShare.Span.HOUR,
        endsAtMs = null,
        reach = PeerLiveShare.Reach.MESH,
        position = position,
        sent = sent,
    )
}
