package local.phantom.companion.core.net.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * OSRM reports the road's heading before and after a maneuver; the difference
 * is how far it actually turns. Both are compass bearings, so the subtraction
 * wraps — and a wrapped angle points an arrow the wrong way round.
 */
class TurnAngleTest {

    @Test
    fun `a plain right turn is ninety degrees`() {
        assertEquals(90f, turnAngleOf(0.0, 90.0)!!, 0.01f)
    }

    @Test
    fun `a left turn is negative`() {
        assertEquals(-90f, turnAngleOf(90.0, 0.0)!!, 0.01f)
    }

    @Test
    fun `crossing north does not become a u-turn`() {
        // 350° → 10° is a gentle 20° right, not 340° of spinning.
        assertEquals(20f, turnAngleOf(350.0, 10.0)!!, 0.01f)
        assertEquals(-20f, turnAngleOf(10.0, 350.0)!!, 0.01f)
    }

    @Test
    fun `a u-turn stays at the boundary rather than flipping sign`() {
        assertEquals(180f, turnAngleOf(0.0, 180.0)!!, 0.01f)
    }

    @Test
    fun `a step without bearings has no angle to draw`() {
        assertNull(turnAngleOf(null, 90.0))
        assertNull(turnAngleOf(0.0, null))
    }
}
