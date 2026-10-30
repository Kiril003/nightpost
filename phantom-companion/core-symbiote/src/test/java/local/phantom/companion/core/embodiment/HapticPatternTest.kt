package local.phantom.companion.core.embodiment

import local.phantom.companion.core.context.SystemState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HapticPatternTest {

    @Test
    fun `every state has a pattern`() {
        for (s in SystemState.values()) {
            HapticPattern.forState(s)
        }
    }

    @Test
    fun `ghost is silence`() {
        val p = HapticPattern.forState(SystemState.GHOST)
        assertTrue(p.amplitudes.all { it == 0 })
    }

    @Test
    fun `focus is single short tick`() {
        val p = HapticPattern.forState(SystemState.FOCUS)
        assertEquals(2, p.timings.size)
        assertEquals(50L, p.timings[1])
    }

    @Test
    fun `amplitudes stay below alarm intensity`() {
        for (s in SystemState.values()) {
            val p = HapticPattern.forState(s)
            assertTrue("$s amplitudes must stay subtle",
                p.amplitudes.all { it <= 80 })
        }
    }

    @Test
    fun `timing and amplitude arrays match length`() {
        for (s in SystemState.values()) {
            val p = HapticPattern.forState(s)
            assertEquals(p.timings.size, p.amplitudes.size)
        }
    }
}
