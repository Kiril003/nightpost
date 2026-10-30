package local.phantom.companion.core.net.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

private const val T0 = 1_790_000_000_000L

class PeerWhereBeaconTest {

    private val level = PeerWhereGrid.Level.DISTRICT
    private val home = PeerWhereGrid.cellOf(50.45, 30.52, level)
    private val centre = PeerWhereGrid.centreOf(home)
    private val degPerM = 1.0 / 111_320.0

    private fun started(p: PeerWhere.Precision = PeerWhere.Precision.DISTRICT, rnd: Random = Random(1)): PeerWhereBeacon.State {
        val s = PeerWhereBeacon.grant(PeerWhereBeacon.State(), whereFriend("b"), p, T0, rnd)!!.state
        val first = PeerWhereBeacon.onFix(s, PeerWhereBeacon.Fix(centre.first, centre.second, 5, T0), T0, rnd)
        assertEquals(1, first.out.size)
        return first.state
    }

    private fun fix(lat: Double, lon: Double, t: Long) = PeerWhereBeacon.Fix(lat, lon, 5, t)

    private data class Trace(val stamps: List<Long>, val sizes: Set<Int>, val cells: List<PeerWhereGrid.Cell>)

    /** Дві години з фіксом що 30 с; [path] — де людина в мить t. */
    private fun run(seed: Int, path: (Long) -> Pair<Double, Double>): Trace {
        val rnd = Random(seed)
        var s = started(rnd = rnd)
        val stamps = mutableListOf<Long>()
        val sizes = mutableSetOf<Int>()
        val cells = mutableListOf<PeerWhereGrid.Cell>()
        for (i in 1..240) {
            val t = T0 + i * 30_000L
            val (lat, lon) = path(t)
            val step = PeerWhereBeacon.onFix(s, fix(lat, lon, t), t, rnd)
            s = step.state
            step.out.forEach {
                stamps += t
                sizes += it.body.length
                cells += (PeerWhere.decode(it.body) as PeerWhere.Area).cell
            }
        }
        return Trace(stamps, sizes, cells)
    }

    @Test
    fun `мить переходу межі не читається з часу кадрів`() {
        val still = run(11) { centre }
        val walker = run(11) { t ->
            val km = (t - T0) / 60_000.0 * 0.08
            centre.first + km * 1_000 * degPerM to centre.second
        }
        assertTrue("за дві години людина мала перетнути межу", walker.cells.toSet().size >= 3)
        assertEquals(setOf(home), still.cells.toSet())
        assertEquals("рух змінив розклад кадрів", still.stamps, walker.stamps)
        assertEquals(setOf(PeerWhere.BODY_CHARS), still.sizes + walker.sizes)
    }

    @Test
    fun `розклад — не метроном`() {
        val gaps = run(5) { centre }.stamps.zipWithNext { a, b -> b - a }
        val p = PeerWhere.Precision.DISTRICT
        assertTrue(gaps.size >= 8)
        assertTrue(gaps.all { it >= PeerWhereBeacon.slotFloorMs(p) - 30_000L && it <= PeerWhereBeacon.slotCapMs(p) + 30_000L })
        assertTrue("усі проміжки однакові — це годинник, а не шум", gaps.toSet().size > gaps.size / 2)
        val mean = (1..4_000).map { PeerWhereBeacon.nextGapMs(p, Random(it)) }.average()
        assertTrue(mean in 0.8 * PeerWhereBeacon.slotMeanMs(p)..1.05 * PeerWhereBeacon.slotMeanMs(p))
    }

    @Test
    fun `стеля слоту нижча за строк — точка в друга не гасне між кадрами`() {
        PeerWhere.Precision.entries.forEach { p ->
            assertTrue(PeerWhereBeacon.slotCapMs(p) < PeerWhereBeacon.ttlMs(p) - PeerWhereBeacon.FIX_LEAD_MS)
        }
    }

    @Test
    fun `вихід за межу менше за запас не міняє району`() {
        val north = centre.first + (1_500 + 0.1 * 3_000) * degPerM
        val s = started()
        val t = s.slotAtMs
        val step = PeerWhereBeacon.onFix(s, fix(north, centre.second, t), t, Random(1))
        assertEquals(home, (PeerWhere.decode(step.out.single().body) as PeerWhere.Area).cell)
    }

    @Test
    fun `новий район їде в найближчий слот, не в мить перетину`() {
        val north = centre.first + (1_500 + 0.3 * 3_000) * degPerM
        val s = started()
        val t = s.slotAtMs - 1_000L
        val crossed = PeerWhereBeacon.onFix(s, fix(north, centre.second, t), t, Random(7))
        assertTrue("кадр пішов у мить перетину", crossed.out.isEmpty())
        assertEquals(s.slotAtMs, PeerWhereBeacon.nextWakeMs(crossed.state, t))
        val at = s.slotAtMs
        val slot = PeerWhereBeacon.onTick(crossed.state, at, Random(7))
        val area = PeerWhere.decode(slot.out.single().body) as PeerWhere.Area
        assertEquals(home.row + 1, area.cell.row)
    }

    @Test
    fun `слот без свіжого виміру повторює сказане з його миттю`() {
        val s = started()
        val late = s.slotAtMs + PeerWhereBeacon.FIX_MAX_AGE_MS
        val step = PeerWhereBeacon.onTick(s, late, Random(2))
        val area = PeerWhere.decode(step.out.single().body) as PeerWhere.Area
        assertEquals(home, area.cell)
        assertEquals("повтор удав свіжий вимір", T0, area.atMs)
        val gone = T0 + PeerWhereBeacon.HOLD_MAX_AGE_MS + 1
        var q = step.state
        while (q.slotAtMs < gone) q = PeerWhereBeacon.onTick(q, q.slotAtMs, Random(3)).state
        assertTrue(PeerWhereBeacon.onTick(q, q.slotAtMs, Random(3)).out.isEmpty())
        assertEquals(null, PeerWhereBeacon.nextWakeMs(q, q.slotAtMs))
    }

    @Test
    fun `забута позиція не будить розклад і не повторюється`() {
        val forgotten = PeerWhereBeacon.forgetPosition(started())
        val at = forgotten.slotAtMs
        assertEquals(null, PeerWhereBeacon.nextWakeMs(forgotten, at))
        assertTrue(PeerWhereBeacon.onTick(forgotten, at, Random(4)).out.isEmpty())
    }

    @Test
    fun `на питання друга — одна відповідь за вікно`() {
        var s = started()
        val t = T0 + 10_000L
        var answers = 0
        s = PeerWhereBeacon.onFix(s, fix(centre.first, centre.second, t), t).state
        for (i in 0 until 20) {
            val step = PeerWhereBeacon.onAsk(s, "b", t + i * 5_000L)
            answers += step.out.size
            s = step.state
        }
        assertEquals(1, answers)
    }

    @Test
    fun `GNSS питають лише перед слотом`() {
        val s = started()
        val mid = T0 + PeerWhereBeacon.FIX_MAX_AGE_MS + 1
        if (s.slotAtMs - mid > PeerWhereBeacon.FIX_LEAD_MS) assertFalse(PeerWhereBeacon.wantsFix(s, mid))
        assertTrue(PeerWhereBeacon.wantsFix(s, maxOf(mid, s.slotAtMs - 1_000L)))
        assertFalse(PeerWhereBeacon.wantsFix(PeerWhereBeacon.State(), mid))
    }

    @Test
    fun `зниження точності перекриває точку одразу`() {
        val s = started(PeerWhere.Precision.EXACT)
        val t = T0 + 1_000L
        val down = PeerWhereBeacon.grant(s, whereFriend("b"), PeerWhere.Precision.DISTRICT, t)
        assertNotNull(down)
        val area = PeerWhere.decode(down!!.out.single().body) as PeerWhere.Area
        assertEquals(home, area.cell)
    }

    @Test
    fun `клітинка вміщує свій центр і має обіцяний розмір`() {
        val rnd = Random(24)
        repeat(3_000) {
            val lat = rnd.nextDouble(-85.0, 85.0)
            val lon = rnd.nextDouble(-180.0, 180.0)
            PeerWhereGrid.Level.entries.forEach { lvl ->
                val c = PeerWhereGrid.cellOf(lat, lon, lvl)
                val (cl, co) = PeerWhereGrid.centreOf(c)
                assertEquals(c, PeerWhereGrid.cellOf(cl, co, lvl))
            }
        }
        val east = PeerWhereGrid.cellOf(centre.first, centre.second + 3_000 * degPerM / Math.cos(Math.toRadians(50.45)), level)
        assertEquals(home.col + 1, east.col)
        val q = PeerWhereGrid.cellOf(50.45, 30.52, PeerWhereGrid.Level.QUARTER)
        val (ql, qo) = PeerWhereGrid.centreOf(q)
        assertEquals(q.row + 1, PeerWhereGrid.cellOf(ql + 460 * degPerM, qo, PeerWhereGrid.Level.QUARTER).row)
    }

    @Test
    fun `строк дозволу обрізає кожен кадр і сам стирає людину`() {
        val until = T0 + 15 * 60_000L
        val rnd = Random(3)
        var s = PeerWhereBeacon.grant(PeerWhereBeacon.State(), whereFriend("b"), PeerWhere.Precision.DISTRICT, T0, rnd, untilMs = until)!!.state
        val first = PeerWhereBeacon.onFix(s, fix(centre.first, centre.second, T0), T0, rnd)
        s = first.state
        val sent = PeerWhere.decode(first.out.single().body) as PeerWhere.Position
        assertEquals("кадр живе довше за обіцяні 15 хв", until, sent.expiresAtMs)
        assertTrue("годинник проспить кінець строку", PeerWhereBeacon.nextWakeMs(s, until - 1)!! <= until)

        val end = PeerWhereBeacon.onTick(s, until, rnd)
        assertTrue("дозвіл пережив свій строк", end.state.lanes.isEmpty())
        assertTrue(PeerWhere.decode(end.out.single().body) is PeerWhere.Erase)
        assertEquals(null, PeerWhereBeacon.grant(PeerWhereBeacon.State(), whereFriend("b"), PeerWhere.Precision.DISTRICT, until, rnd, untilMs = until))
    }

    @Test
    fun `скорочений строк пересилає кадр, бо старий на тому кінці живе довше`() {
        val rnd = Random(4)
        val s = started(rnd = rnd)
        val cut = T0 + 5 * 60_000L
        val step = PeerWhereBeacon.grant(s, whereFriend("b"), PeerWhere.Precision.DISTRICT, T0 + 1_000L, rnd, untilMs = cut)!!
        val again = PeerWhere.decode(step.out.single().body) as PeerWhere.Position
        assertEquals(cut, again.expiresAtMs)
    }

    // Збірка 18: вимір, старший за «стерти», приймач відкидав, а смуга мовчала до слоту.
    @Test
    fun `новий показ після «стерти» не шле виміру, старшого за стирання`() {
        val rnd = Random(3)
        var s = started(rnd = rnd)
        val erasedAt = T0 + 10_000L
        val stop = PeerWhereBeacon.revoke(s, "b", erasedAt)
        s = stop.state
        var inbox = PeerWhereInbox.Inbox()
        stop.out.forEach { inbox = PeerWhereInbox.absorb(inbox, "me", it.body, erasedAt, allowed = true) }

        val again = T0 + 20_000L
        val grant = PeerWhereBeacon.grant(s, whereFriend("b"), PeerWhere.Precision.DISTRICT, again, rnd)!!
        grant.out.forEach { inbox = PeerWhereInbox.absorb(inbox, "me", it.body, again, allowed = true) }
        s = grant.state

        val fresh = PeerWhereBeacon.onFix(s, fix(centre.first, centre.second, again + 1_000L), again + 1_000L, rnd)
        fresh.out.forEach { inbox = PeerWhereInbox.absorb(inbox, "me", it.body, again + 1_000L, allowed = true) }
        assertNotNull("новий показ так і не ліг у приймач", PeerWhereInbox.standingFor(inbox, "me", again + 1_000L))
        assertTrue(
            "смуга з невідправленим кадром будить годинник щосекунди",
            (PeerWhereBeacon.nextWakeMs(grant.state, again) ?: Long.MAX_VALUE) > again,
        )
    }
}
