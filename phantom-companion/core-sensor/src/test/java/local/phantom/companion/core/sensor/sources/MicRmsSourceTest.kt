package local.phantom.companion.core.sensor.sources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MIC-1 — pin the dB-conversion contract that consumers rely on.
 *
 * The legacy AudioRecord-backed source emitted dBFS in the range
 * [-100, 0] for active speech and `null` when the mic was off. Several
 * downstream nodes branch on these thresholds:
 *   - SystemPulseRepository normalises `(db + 80) / 60` for the VOICE
 *     dot brightness, so a missing reading must stay null (not -100f).
 *   - AmbientAudioNode treats null as "unknown", < -55 as "quiet", and
 *     > -25 as "speech-loud" for the FOCUS/SENTINEL/DREAM decision tree.
 *
 * The refactor moves the recorder off and consumes a 0..1 amplitude
 * lambda. These tests guard the conversion math + the null-when-idle
 * invariant that keeps the system mic indicator off.
 */
class MicRmsSourceTest {

    @Test
    fun `null amplitude maps to null dB`() {
        assertNull(MicRmsSource.computeDb(null))
    }

    @Test
    fun `zero amplitude maps to null dB so VOICE dot reads OFF`() {
        // log10(0) is -Infinity; we explicitly emit null instead so the
        // operator's mic indicator and the SystemPulse VOICE dot agree
        // that nothing is recording.
        assertNull(MicRmsSource.computeDb(0f))
    }

    @Test
    fun `full-scale amplitude maps to 0 dBFS`() {
        val db = MicRmsSource.computeDb(1f)
        assertNotNull(db)
        assertEquals(0f, db!!, 0.0001f)
    }

    @Test
    fun `half-scale amplitude maps to about -6 dBFS`() {
        val db = MicRmsSource.computeDb(0.5f)
        assertNotNull(db)
        // 20 * log10(0.5) ≈ -6.0206
        assertEquals(-6.02f, db!!, 0.05f)
    }

    @Test
    fun `quiet amplitude is floored at -100 dBFS`() {
        // Sub-threshold amplitudes (very low rms) would otherwise
        // produce values like -200 dBFS that AmbientAudioNode wasn't
        // designed for. The legacy source clamped at -100; we preserve
        // that contract.
        val db = MicRmsSource.computeDb(0.0000000001f)
        assertNotNull(db)
        assertEquals(-100f, db!!, 0.0001f)
    }

    @Test
    fun `passive source emits null until refresh and provider returns a value`() {
        var current: Float? = null
        val source = MicRmsSource(audioLevelProvider = { current })

        // Idle — no refresh, no provider value.
        assertNull(source.state.value)

        // Provider stays null — refresh keeps state null.
        source.refresh()
        assertNull(source.state.value)

        // Recorder publishes a real amplitude — refresh picks it up.
        current = 0.5f
        source.refresh()
        val db = source.state.value
        assertNotNull(db)
        assertTrue(db!! < -5f && db > -7f)

        // Recorder stops (publishes 0f → provider clamps to null) — state goes null.
        current = null
        source.refresh()
        assertNull(source.state.value)

        // Explicit stop also clears.
        current = 0.5f
        source.refresh()
        assertNotNull(source.state.value)
        source.stop()
        assertNull(source.state.value)
    }
}
