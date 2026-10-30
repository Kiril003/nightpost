package local.phantom.companion.feature.stream.whisper

import local.phantom.companion.core.design.component.cyclesElapsed
import local.phantom.companion.core.design.component.fullRibbonText
import local.phantom.companion.core.design.component.ribbonProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1.5-C — pin the marquee math + verbatim ribbon copy. Animator
 * timing inside Compose is hard to assert (frames are coupled to
 * `withFrameNanos`); the helpers here are extracted exactly so a plain
 * JVM test can verify drift cycles and the prototype's text shape.
 */
class WhisperLineRibbonTest {

    private val prototypeMessages = listOf(
        "олег чекає твою відповідь · 23 хв",
        "дощ за 30 хв · парасоля в передпокої",
        "олег чекає твою відповідь · 23 хв",
        "кави не було 3 дні · local roasters · 4 хв пішки",
    )

    /* ---- fullRibbonText ---- */

    @Test
    fun `joins prototype messages with verbatim separator and trailing dots`() {
        // phase2.jsx line 397: messages.join('   ···   ') + '   ···   '
        // Pinned exactly — if anyone adjusts the separator, the visual
        // rhythm of the marquee breaks.
        val text = fullRibbonText(prototypeMessages)
        assertTrue("must end with the trailing separator", text.endsWith("   ···   "))
        assertEquals(prototypeMessages.size - 1, count(text, "   ···   ") - 1)
    }

    @Test
    fun `empty messages produce empty ribbon text`() {
        assertEquals("", fullRibbonText(emptyList()))
    }

    @Test
    fun `single message still gets the trailing separator`() {
        // The trailing separator is what keeps the loop-around handoff
        // continuous — it must apply even with one message.
        assertEquals("hello   ···   ", fullRibbonText(listOf("hello")))
    }

    /* ---- ribbonProgress ---- */

    @Test
    fun `progress is zero at cycle boundaries`() {
        assertEquals(0f, ribbonProgress(0L, 38_000L), 0.0001f)
        assertEquals(0f, ribbonProgress(38_000L, 38_000L), 0.0001f)
        assertEquals(0f, ribbonProgress(76_000L, 38_000L), 0.0001f)
    }

    @Test
    fun `progress is half at half cycle`() {
        assertEquals(0.5f, ribbonProgress(19_000L, 38_000L), 0.0001f)
    }

    @Test
    fun `progress wraps within the cycle window`() {
        // 38000 + 9500 = 1.25 cycles → progress 0.25
        assertEquals(0.25f, ribbonProgress(47_500L, 38_000L), 0.0001f)
    }

    @Test
    fun `nonpositive cycle duration short-circuits to zero`() {
        assertEquals(0f, ribbonProgress(1_000L, 0L), 0f)
        assertEquals(0f, ribbonProgress(1_000L, -38_000L), 0f)
    }

    @Test
    fun `negative elapsed time is clamped to zero`() {
        assertEquals(0f, ribbonProgress(-100L, 38_000L), 0.0001f)
    }

    /* ---- cyclesElapsed ---- */

    @Test
    fun `cycles count rolls over exactly at duration boundary`() {
        assertEquals(0L, cyclesElapsed(0L, 38_000L))
        assertEquals(0L, cyclesElapsed(37_999L, 38_000L))
        assertEquals(1L, cyclesElapsed(38_000L, 38_000L))
        assertEquals(2L, cyclesElapsed(76_000L, 38_000L))
        assertEquals(2L, cyclesElapsed(80_000L, 38_000L))
    }

    @Test
    fun `cycles count nonpositive duration returns zero`() {
        assertEquals(0L, cyclesElapsed(100L, 0L))
        assertEquals(0L, cyclesElapsed(100L, -1L))
    }

    private fun count(haystack: String, needle: String): Int {
        var idx = 0
        var n = 0
        while (true) {
            val found = haystack.indexOf(needle, idx)
            if (found < 0) break
            n++
            idx = found + needle.length
        }
        return n
    }
}
