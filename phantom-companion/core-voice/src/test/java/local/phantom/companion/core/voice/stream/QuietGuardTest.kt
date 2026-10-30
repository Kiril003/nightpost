package local.phantom.companion.core.voice.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Договір «ніколи не мовчить довше півтори секунди», поки PHANTOM думає чи діє. */
class QuietGuardTest {

    private val fillers = listOf("Хвилинку.", "Ще думаю.", "Майже.")

    @Test
    fun `thinking without a word - the first filler comes before 1,5 s of silence`() {
        val g = QuietGuard(fillers, "Це займе довше.")
        g.started(0)
        assertNull(g.next(1_000))
        assertEquals("Хвилинку.", g.next(1_250))
    }

    @Test
    fun `no gap anywhere is longer than 1,5 s while the model keeps thinking`() {
        val g = QuietGuard(fillers, "Це займе довше.")
        g.started(0)
        var quietSince = 0L
        var worst = 0L
        var t = 0L
        while (t <= 12_000) {
            val said = g.next(t)
            if (said != null) {
                worst = maxOf(worst, t - quietSince)
                quietSince = g.speakingUntil
            }
            t += 250
        }
        assertTrue("найдовша тиша $worst мс", worst <= 1_500)
    }

    @Test
    fun `while the answer itself is being spoken no filler cuts in`() {
        val g = QuietGuard(fillers, null)
        g.started(0)
        g.spoke("Перевірив маршрут, найближче укриття за двісті метрів.", 300)
        for (t in 300L..5_000L step 250) assertNull("t=$t", g.next(t))
    }

    @Test
    fun `after a few fillers it says once that it will take longer, then stays honestly quiet`() {
        val g = QuietGuard(fillers, "Це займе довше.", maxFillers = 2)
        g.started(0)
        val said = generateSequence(0L) { it + 250 }.takeWhile { it < 30_000 }.mapNotNull { g.next(it) }.toList()
        assertEquals(listOf("Хвилинку.", "Ще думаю.", "Це займе довше."), said)
    }

    @Test
    fun `a closed turn needs no filler`() {
        val g = QuietGuard(fillers, null)
        g.started(0)
        g.closed()
        assertNull(g.next(10_000))
    }
}
