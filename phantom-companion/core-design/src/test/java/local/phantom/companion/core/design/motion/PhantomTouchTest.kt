package local.phantom.companion.core.design.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhantomTouchTest {

    @Test
    fun `an untouched control sits at rest`() {
        listOf(PRESS_DEPTH_PRIMARY, PRESS_DEPTH_CONTROL, PRESS_DEPTH_SURFACE).forEach {
            assertEquals(1f, pressTarget(pressed = false, depth = it), 0f)
        }
    }

    @Test
    fun `a pressed control dips to its rung`() {
        assertEquals(PRESS_DEPTH_CONTROL, pressTarget(true, PRESS_DEPTH_CONTROL), 0f)
    }

    @Test
    fun `the scale runs deepest for the dominant action and shallowest for a surface`() {
        assertTrue(PRESS_DEPTH_PRIMARY < PRESS_DEPTH_CONTROL)
        assertTrue(PRESS_DEPTH_CONTROL < PRESS_DEPTH_SURFACE)
        assertTrue(PRESS_DEPTH_SURFACE < 1f)
    }

    @Test
    fun `a press never collapses the target`() {
        // A caller passing 0f means the control vanishes under the thumb — the
        // one press that reads as a bug rather than as feedback.
        assertEquals(PRESS_DEPTH_FLOOR, pressTarget(true, 0f), 0f)
        assertEquals(PRESS_DEPTH_FLOOR, pressTarget(true, -3f), 0f)
    }

    @Test
    fun `a press never grows the target`() {
        // Growing under the finger puts the target under the fingertip that is
        // already covering it; the dip is the only direction that stays visible.
        assertEquals(1f, pressTarget(true, 1.4f), 0f)
    }

    @Test
    fun `every rung stays inside the legal band`() {
        listOf(PRESS_DEPTH_PRIMARY, PRESS_DEPTH_CONTROL, PRESS_DEPTH_SURFACE).forEach {
            assertTrue("$it is below the floor", it >= PRESS_DEPTH_FLOOR)
        }
    }
}
