package local.phantom.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArrivalWatchTest {

    private val t0 = 1_790_000_000_000L
    private val goal = ArrivalWatch.Goal(50.45, 30.52, listOf("oksana"), "Я на місці", t0)

    @Test
    fun `два виміри в колі — один лист, і більше ніколи`() {
        var s = ArrivalWatch.onFix(listOf(goal), 50.46, 30.52, 5f, t0 + 1_000)
        assertTrue(s.fire.isEmpty())
        s = ArrivalWatch.onFix(s.goals, 50.4503, 30.52, 5f, t0 + 2_000)
        assertTrue("один вимір — ще не прибуття", s.fire.isEmpty())
        s = ArrivalWatch.onFix(s.goals, 50.4502, 30.5201, 5f, t0 + 3_000)
        assertEquals(listOf("oksana"), s.fire.single().peerIds)
        assertTrue(s.goals.isEmpty())
        assertTrue(ArrivalWatch.onFix(s.goals, 50.45, 30.52, 5f, t0 + 4_000).fire.isEmpty())
    }

    @Test
    fun `грубий вимір і вчорашня обіцянка не кажуть «я на місці»`() {
        var s = ArrivalWatch.onFix(listOf(goal), 50.45, 30.52, 900f, t0 + 1_000)
        s = ArrivalWatch.onFix(s.goals, 50.45, 30.52, 900f, t0 + 2_000)
        assertTrue(s.fire.isEmpty())
        val late = t0 + ArrivalWatch.LIFE_MS + 1
        s = ArrivalWatch.onFix(s.goals, 50.45, 30.52, 5f, late)
        s = ArrivalWatch.onFix(s.goals, 50.45, 30.52, 5f, late + 1)
        assertTrue(s.fire.isEmpty())
        assertTrue(s.goals.isEmpty())
    }
}
