package local.phantom.companion.core.truth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ReputationTest {

    @Test
    fun `a new person cannot overturn an old well-confirmed fact with one voice`() {
        val rnd = Random(7)
        repeat(300) { trial ->
            val kind = FactKind.entries.filter { it.binary }.random(rnd)
            val k = key(kind)
            val (held, other) = kind.readings
            val backers = (1..rnd.nextInt(5, 30)).map { elder("b$it") }
            val obs = backers.map { saw(it, k, held, NOW - 1 - rnd.nextLong(0, kind.halfLifeMs / 2)) }
            val newbie = fresh("n", NOW - rnd.nextLong(0, 14 * DAY))
            val all = obs + saw(newbie, k, other, NOW)
            val rep = { id: VoterId -> if (id == newbie.id) Reputation.NEWCOMER else Reputation.weight(Standing(20.0, 1.0)) }
            val v = verdictOf(k, all, backers + newbie, reputation = rep)
            assertEquals("trial=$trial", TruthState.LIKELY, v.state)
            assertEquals("trial=$trial", held, v.reading)
            assertTrue("trial=$trial the doubt must be visible", v.dissent)
        }
    }

    @Test
    fun `a refuted liar loses weight, honest witnesses gain it, nobody passes the ceiling`() {
        val ledger = ReputationLedger()
        val liar = elder("liar")
        val honest = (1..3).map { elder("h$it") }
        val voters = (honest + liar).associateBy { it.id }
        val k = key(FactKind.POWER)
        val obs = ArrayList<Observation>()
        var t = NOW
        var prevLiar = ledger.weight(liar.id, t)
        repeat(12) {
            honest.forEach { obs += saw(it, k, Reading.ON, t) }
            obs += saw(liar, k, Reading.OFF, t + MIN)
            t += FactKind.POWER.halfLifeMs
            ledger.settle(obs, voters, t)
            val w = ledger.weight(liar.id, t)
            assertTrue("liar $prevLiar -> $w", w < prevLiar)
            prevLiar = w
        }
        assertTrue(prevLiar < 0.3)
        honest.forEach { assertTrue(ledger.weight(it.id, t) > 2.0) }
        assertTrue(ledger.weight(honest[0].id, t) < Reputation.CEILING)
    }

    @Test
    fun `weight stays inside its bounds for any record`() {
        val rnd = Random(3)
        repeat(5000) {
            val w = Reputation.weight(Standing(rnd.nextDouble(0.0, 1e6), rnd.nextDouble(0.0, 1e6)))
            assertTrue(w > 0.0 && w < Reputation.CEILING)
        }
        assertEquals(Reputation.NEWCOMER, Reputation.weight(Standing()), 1e-12)
    }

    @Test
    fun `seeing darkness is not a lie when the light comes back later`() {
        val k = key(FactKind.POWER)
        val a = elder("a")
        val later = (1..4).map { elder("l$it") }
        val obs = listOf(saw(a, k, Reading.OFF, NOW)) + later.map { saw(it, k, Reading.ON, NOW + 3 * HOUR) }
        val ledger = ReputationLedger()
        ledger.settle(obs, (later + a).associateBy { it.id }, NOW + DAY)
        assertEquals(0.0, ledger.standing(a.id, NOW + DAY).refuted, 0.0)
    }

    @Test
    fun `a ring of one circle cannot confirm itself`() {
        val k = key(FactKind.SHOP)
        val ring = (1..6).map { elder("r$it", inviter = "boss") }
        val obs = ring.map { saw(it, k, Reading.WORKING, NOW) }
        val ledger = ReputationLedger()
        val out = ledger.settle(obs, ring.associateBy { it.id }, NOW + DAY)
        assertTrue(out.all { it.second == Outcome.OPEN })
        assertEquals(Reputation.NEWCOMER, ledger.weight(ring[0].id, NOW + DAY), 1e-12)
    }

    @Test
    fun `merit and shame fade over seasons`() {
        val s = Standing(10.0, 4.0, NOW).at(NOW + Reputation.HALF_LIFE_MS)
        assertEquals(5.0, s.confirmed, 1e-9)
        assertEquals(2.0, s.refuted, 1e-9)
    }
}
