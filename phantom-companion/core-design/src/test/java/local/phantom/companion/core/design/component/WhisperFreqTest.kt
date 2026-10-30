package local.phantom.companion.core.design.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3-E2 — pinned constants for the Whisper Line drift presets.
 *
 * The cycle durations are the prototype's exact values
 * (`phase2.jsx#WhisperLineScene` cadence picker — Slow 60s, Medium 38s,
 * Fast 22s). They will be persisted to disk by ordinal in
 * `VoicePreferences.whisperFreq` once Phase 3-E5 lands the settings UI;
 * this test pins both the cycle values AND the ordinal positions so a
 * future "let's reorder these for alphabetical sanity" refactor breaks
 * loudly here instead of silently rotating saved operator preferences
 * (Slow → Medium → Fast).
 */
class WhisperFreqTest {

    @Test
    fun `cycle durations match phase2_jsx prototype values`() {
        assertEquals(60_000, WhisperFreq.Slow.cycleMs)
        assertEquals(38_000, WhisperFreq.Medium.cycleMs)
        assertEquals(22_000, WhisperFreq.Fast.cycleMs)
    }

    @Test
    fun `ordinal positions are persistence-stable`() {
        // Order matters for VoicePreferences.whisperFreq disk format.
        // Reorder = silent corruption of operator's saved preference.
        assertEquals(0, WhisperFreq.Slow.ordinal)
        assertEquals(1, WhisperFreq.Medium.ordinal)
        assertEquals(2, WhisperFreq.Fast.ordinal)
    }

    @Test
    fun `enum values list size is exactly three`() {
        // If a fourth preset is added (e.g. "Glacial" 120s), the persisted
        // ordinal layout still works because new entries append at the
        // end. This test traps a count drop, which WOULD corrupt saves.
        assertEquals(3, WhisperFreq.values().size)
    }

    @Test
    fun `cycle durations are monotonically decreasing across the enum order`() {
        // "Slow" must drift slower than "Medium" must drift slower than
        // "Fast" — the labels and the math must agree.
        val cycles = WhisperFreq.values().map { it.cycleMs }
        assertTrue("Slow > Medium", cycles[0] > cycles[1])
        assertTrue("Medium > Fast", cycles[1] > cycles[2])
    }

    @Test
    fun `WhisperLineDefaultMessages is non-empty and Ukrainian`() {
        assertTrue(WhisperLineDefaultMessages.isNotEmpty())
        WhisperLineDefaultMessages.forEach { msg ->
            assertTrue("must contain at least one cyrillic letter: $msg",
                msg.any { it.code in 0x0400..0x04FF })
        }
    }
}
