package local.phantom.companion.core.truth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PromptAndImpactTest {

    private val planner = PromptPlanner()

    private fun unknown(kind: FactKind, obj: String, cell: CellId = HERE) =
        Verdict(key(kind, cell, obj), TruthState.UNKNOWN, null, 0.5, 0.0, 0, null, null, false)

    private fun likely(kind: FactKind, obj: String, lastMs: Long, dissent: Boolean = false) =
        Verdict(key(kind, HERE, obj), TruthState.LIKELY, kind.readings.first(), 0.9, 5.0, 5, lastMs, lastMs, dissent)

    @Test
    fun `never more than three questions in any 24 hours, never closer than 45 minutes`() {
        val rnd = Random(5)
        repeat(50) { run ->
            val asked = ArrayList<Asked>()
            var t = NOW
            var n = 0
            while (t < NOW + 7 * DAY) {
                t += rnd.nextLong(MIN, 40 * MIN)
                val cands = (0 until rnd.nextInt(1, 30)).map {
                    PromptCandidate(unknown(FactKind.entries.random(rnd), "o${n++}"), rnd.nextInt(0, 500))
                }
                planner.next(HERE, cands, asked, emptyList(), alertActive = false, nowMs = t)?.let {
                    asked += Asked(it.verdict.key, t)
                }
            }
            assertTrue("run=$run asked nothing", asked.size >= 7)
            for (a in asked) {
                val window = asked.count { it.atMs > a.atMs - DAY && it.atMs <= a.atMs }
                assertTrue("run=$run $window in a day", window <= 3)
            }
            asked.zipWithNext().forEach { (a, b) -> assertTrue(b.atMs - a.atMs >= 45 * MIN) }
        }
    }

    @Test
    fun `only the person nearby is asked`() {
        val far = PromptCandidate(unknown(FactKind.SHELTER_OPEN, "far", THERE), 1000)
        assertNull(planner.next(HERE, listOf(far), emptyList(), emptyList(), false, NOW))
    }

    @Test
    fun `nobody is asked during an alert`() {
        val c = PromptCandidate(unknown(FactKind.SHELTER_OPEN, "s"), 1000)
        assertNull(planner.next(HERE, listOf(c), emptyList(), emptyList(), alertActive = true, nowMs = NOW))
    }

    @Test
    fun `the fact on the edge of fading that many rely on comes first, a fresh one is never asked`() {
        val fresh = PromptCandidate(likely(FactKind.SHELTER_OPEN, "fresh", NOW - HOUR), 900)
        val fading = PromptCandidate(likely(FactKind.PHARMACY, "fading", NOW - 5 * HOUR), 200)
        val lonely = PromptCandidate(likely(FactKind.PHARMACY, "lonely", NOW - 5 * HOUR), 0)
        assertEquals(fading, planner.next(HERE, listOf(fresh, lonely, fading), emptyList(), emptyList(), false, NOW))
        assertNull(planner.next(HERE, listOf(fresh), emptyList(), emptyList(), false, NOW))
    }

    @Test
    fun `whoever just answered is not asked the same thing`() {
        val c = PromptCandidate(unknown(FactKind.POWER, "p"), 50)
        val mine = listOf(Observation(VoterId("me"), c.verdict.key, Reading.ON, NOW - 10 * MIN))
        assertNull(planner.next(HERE, listOf(c), emptyList(), mine, false, NOW))
    }

    @Test
    fun `usefulness counts other people, only while the word still stood`() {
        val k = key(FactKind.SHELTER_OPEN)
        val mine = Observation(VoterId("me"), k, Reading.OPEN, NOW)
        val counter = ImpactCounter()
        counter.view(k, "me-today", NOW + MIN)
        counter.view(k, "before", NOW - MIN)
        (1..27).forEach { counter.view(k, "v$it", NOW + it * HOUR) }
        counter.view(k, "v1", NOW + 2 * HOUR)
        counter.view(k, "late", NOW + 30 * DAY)
        assertEquals(27, counter.reach(mine, "me-today", null, NOW + 60 * DAY))
        assertEquals(10, counter.reach(mine, "me-today", NOW + 10 * HOUR, NOW + 60 * DAY))
        assertEquals(25, ImpactCounter.band(27))
        assertEquals(1, ImpactCounter.band(3))
        assertEquals(0, ImpactCounter.band(0))
        assertEquals(140, ImpactCounter.band(147))
        assertEquals("Твоє підтвердження побачили щонайменше 25 людей", Speech.impact(25))
        assertEquals("Твоє підтвердження вже допомогло кільком людям", Speech.impact(1))
    }
}
