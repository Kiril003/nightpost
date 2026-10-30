package local.phantom.companion.core.truth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TruthDecayTest {

    private fun rank(s: TruthState) = when (s) { TruthState.LIKELY -> 2; TruthState.UNKNOWN -> 1; TruthState.STALE -> 0 }

    @Test
    fun `without new voices trust never grows — for any mix of voices`() {
        val rnd = Random(24_09)
        repeat(400) { trial ->
            val kind = FactKind.entries.random(rnd)
            val k = key(kind)
            val span = kind.halfLifeMs * 3
            val voters = (0 until rnd.nextInt(1, 16)).map { i ->
                Voter(
                    VoterId("v$i"),
                    NOW - rnd.nextLong(0, 60 * DAY),
                    "net-${rnd.nextInt(4)}",
                    if (rnd.nextBoolean()) VoterId("inv-${rnd.nextInt(3)}") else null,
                )
            }
            val rep = voters.associate { it.id to rnd.nextDouble(0.05, Reputation.CEILING) }
            val obs = voters.flatMap { v ->
                (0 until rnd.nextInt(1, 3)).map {
                    saw(v, k, kind.readings.random(rnd), NOW - rnd.nextLong(0, span), rnd.nextBoolean())
                }
            }
            val start = obs.maxOf { it.atMs }
            var prev = verdictOf(k, obs, voters, start) { rep.getValue(it) }
            var t = start
            repeat(40) {
                t += rnd.nextLong(1, kind.halfLifeMs / 2)
                val v = verdictOf(k, obs, voters, t) { rep.getValue(it) }
                val ctx = "trial=$trial kind=$kind t=${t - start}"
                assertEquals(ctx, prev.reading, v.reading)
                assertTrue("$ctx mass ${prev.mass} -> ${v.mass}", v.mass <= prev.mass + 1e-12)
                assertTrue("$ctx p ${prev.probability} -> ${v.probability}", v.probability <= prev.probability + 1e-12)
                assertTrue("$ctx state ${prev.state} -> ${v.state}", rank(v.state) <= rank(prev.state))
                prev = v
            }
            assertEquals("trial=$trial", TruthState.STALE, prev.state)
        }
    }

    @Test
    fun `power forgets in hours, a shelter in weeks`() {
        val a = elder("a"); val b = elder("b")
        val threeHoursAgo = NOW - 3 * HOUR
        val power = key(FactKind.POWER)
        val shelter = key(FactKind.SHELTER_OPEN)
        val obs = listOf(
            saw(a, power, Reading.ON, threeHoursAgo), saw(b, power, Reading.ON, threeHoursAgo),
            saw(a, shelter, Reading.OPEN, threeHoursAgo), saw(b, shelter, Reading.OPEN, threeHoursAgo),
        )
        assertEquals(TruthState.STALE, verdictOf(power, obs, listOf(a, b)).state)
        assertEquals(TruthState.LIKELY, verdictOf(shelter, obs, listOf(a, b)).state)
        assertTrue(FactKind.QUEUE.halfLifeMs < FactKind.POWER.halfLifeMs)
        assertTrue(FactKind.POWER.halfLifeMs < FactKind.SHELTER_OPEN.halfLifeMs)
        assertTrue(FactKind.SHELTER_OPEN.halfLifeMs < FactKind.SHELTER_STEP_FREE.halfLifeMs)
    }

    @Test
    fun `one grown witness on site is enough, one fresh key is not`() {
        val k = key(FactKind.PHARMACY)
        val grown = elder("g")
        assertEquals(TruthState.LIKELY, verdictOf(k, listOf(saw(grown, k, Reading.WORKING, NOW)), listOf(grown), reputation = { 1.0 }).state)
        val newbie = fresh("n", NOW - HOUR)
        val v = verdictOf(k, listOf(saw(newbie, k, Reading.WORKING, NOW)), listOf(newbie))
        assertEquals(TruthState.UNKNOWN, v.state)
        assertEquals("Невідомо · одне непідтверджене повідомлення", Speech.verdict(v, NOW))
    }

    @Test
    fun `a fresh-key farm spread over separate networks does not make a fact`() {
        val k = key(FactKind.ROAD)
        val farm = (1..3).map { fresh("bot$it", NOW - HOUR) }
        val v = verdictOf(k, farm.map { saw(it, k, Reading.BLOCKED, NOW) }, farm)
        assertEquals(TruthState.UNKNOWN, v.state)
    }

    @Test
    fun `nobody can see a queue from afar`() {
        val k = key(FactKind.QUEUE)
        val far = (1..5).map { elder("far$it") }
        val v = verdictOf(k, far.map { saw(it, k, Reading.QUEUE_LONG, NOW, onSite = false) }, far, reputation = { 2.0 })
        assertEquals(0.0, v.mass, 0.0)
        assertEquals(TruthState.UNKNOWN, v.state)
    }

    @Test
    fun `the words a person reads`() {
        val k = key(FactKind.SHELTER_OPEN)
        val people = (1..12).map { elder("p$it") }
        val obs = people.mapIndexed { i, p -> saw(p, k, Reading.OPEN, NOW - 8 * MIN - i * HOUR) }
        val v = verdictOf(k, obs, people, reputation = { 1.5 })
        assertEquals(true, v.yes)
        assertEquals("Ймовірно відчинено · підтвердили 12 людей, останнє 8 хв тому", Speech.verdict(v, NOW))
        assertEquals("Probably open · confirmed by 12 people, last 8 min ago", Speech.verdict(v, NOW, Lang.EN))
        val stale = verdictOf(k, obs, people, NOW + 20 * DAY, reputation = { 1.5 })
        assertEquals("Застаріло · востаннє «відчинено» 20 дн тому", Speech.verdict(stale, NOW + 20 * DAY))
        assertEquals("підтвердила 21 людина, останнє щойно", Speech.confirmed(21, 0, Lang.UK))
        assertEquals("підтвердили 3 людини, останнє 2 год тому", Speech.confirmed(3, 2 * HOUR, Lang.UK))
        assertEquals("підтвердили 11 людей, останнє щойно", Speech.confirmed(11, 0, Lang.UK))
    }

    @Test
    fun `a person's older word is replaced by their newer one`() {
        val k = key(FactKind.POWER)
        val a = elder("a")
        val obs = listOf(saw(a, k, Reading.ON, NOW - 20 * MIN), saw(a, k, Reading.OFF, NOW - MIN))
        val v = verdictOf(k, obs, listOf(a), reputation = { 1.0 })
        assertEquals(Reading.OFF, v.reading)
        assertEquals(1, v.supporters)
    }

    @Test
    fun `confirm and deny only speak about what is already likely`() {
        val k = key(FactKind.SHELTER_OPEN)
        val a = elder("a")
        val likely = verdictOf(k, listOf(saw(a, k, Reading.OPEN, NOW)), listOf(a), reputation = { 1.0 })
        assertEquals(Reading.OPEN, Claim.Confirm.toReading(k.kind, likely))
        assertEquals(Reading.CLOSED, Claim.Deny.toReading(k.kind, likely))
        assertEquals(null, Claim.Deny.toReading(FactKind.QUEUE, verdictOf(key(FactKind.QUEUE), emptyList(), emptyList())))
        assertEquals(null, Claim.Confirm.toReading(k.kind, verdictOf(k, emptyList(), emptyList())))
        assertEquals(null, Claim.Assert(Reading.ON).toReading(FactKind.SHELTER_OPEN, null))
    }
}
