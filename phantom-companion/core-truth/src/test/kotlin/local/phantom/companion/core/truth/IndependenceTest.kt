package local.phantom.companion.core.truth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class IndependenceTest {

    @Test
    fun `ten voices of one circle weigh less than three independent ones`() {
        val rnd = Random(10_3)
        repeat(300) { trial ->
            val kind = FactKind.entries.filter { it.binary }.random(rnd)
            val k = key(kind)
            val (yes, no) = kind.readings
            val w = rnd.nextDouble(0.05, Reputation.CEILING)
            val size = rnd.nextInt(10, 60)
            val circle = when (trial % 3) {
                0 -> (1..size).map { fresh("c$it", NOW - 20 * DAY, net = "same-wifi") }
                1 -> (1..size).map { elder("c$it", inviter = "boss") }
                else -> (1..size).map { i -> elder("c$i", inviter = if (i == 1) null else "c${i - 1}") }
            }
            val three = (1..3).map { elder("i$it") }
            val obs = circle.map { saw(it, k, no, NOW - rnd.nextLong(0, 5 * MIN)) } +
                three.map { saw(it, k, yes, NOW - rnd.nextLong(0, 5 * MIN)) }
            val v = verdictOf(k, obs, circle + three) { w }
            assertEquals("trial=$trial size=$size w=$w", yes, v.reading)
        }
    }

    @Test
    fun `a circle never weighs more than twice its strongest member`() {
        val rnd = Random(2)
        repeat(1000) {
            val ws = List(rnd.nextInt(1, 200)) { rnd.nextDouble(0.0, Reputation.CEILING) }
            assertTrue(Independence.circleMass(ws) < 2 * ws.max() + 1e-12)
            assertTrue(Independence.circleMass(ws) >= ws.max())
        }
    }

    @Test
    fun `one shared carrier address does not merge settled people`() {
        val people = (1..5).map { elder("p$it", net = "cgnat-kyivstar") }
        val circles = Independence.circles(people, NOW)
        assertEquals(5, circles.values.toSet().size)
        val young = (1..5).map { fresh("y$it", NOW - 3 * DAY, net = "cgnat-kyivstar") }
        assertEquals(1, Independence.circles(young, NOW).values.toSet().size)
    }

    @Test
    fun `invitation lineage is one circle, strangers are not`() {
        val a = elder("a"); val b = elder("b", inviter = "a"); val c = elder("c", inviter = "b")
        val stranger = elder("s")
        val circles = Independence.circles(listOf(a, b, c, stranger), NOW)
        assertEquals(circles[a.id], circles[c.id])
        assertNotEquals(circles[a.id], circles[stranger.id])
    }
}
