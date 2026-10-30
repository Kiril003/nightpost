package local.phantom.companion.core.voice.duplex

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate's job is to answer one question on every frame while PHANTOM speaks:
 * *is that the operator, or is it me coming back through the microphone?*
 *
 * Getting it wrong in one direction makes barge-in impossible. Getting it wrong
 * in the other makes PHANTOM interrupt himself on his own first syllable and
 * never finish a sentence — so the self-interrupt cases below are the ones that
 * actually matter.
 */
class BargeInGateTest {

    private fun frames(
        gate: BargeInGate,
        mic: Float,
        out: Float,
        outKnown: Boolean = true,
        aec: Boolean,
        fromMs: Long = 0L,
        toMs: Long,
        stepMs: Long = 20L,
    ): Boolean {
        var t = fromMs
        while (t <= toMs) {
            if (gate.onFrame(mic, out, outKnown, aec, t)) return true
            t += stepMs
        }
        return false
    }

    /* ── The failure that would break the product ──────────────────────────── */

    @Test
    fun `our own loud voice does not barge in on us when AEC is unavailable`() {
        val gate = BargeInGate()
        // No echo cancellation, PHANTOM is speaking loudly (0.8), and the mic
        // hears essentially that and nothing else (κ≈0.75 → ~0.6). This is the
        // self-interrupt loop; it must never fire.
        assertFalse(
            frames(gate, mic = 0.6f, out = 0.8f, aec = false, toMs = 3_000L),
        )
    }

    @Test
    fun `our own voice does not barge in even sustained for a long time`() {
        val gate = BargeInGate()
        assertFalse(frames(gate, mic = 0.45f, out = 0.6f, aec = false, toMs = 10_000L))
    }

    /* ── The thing the operator is trying to do ────────────────────────────── */

    @Test
    fun `operator talking over us does barge in when AEC is unavailable`() {
        val gate = BargeInGate()
        // Same output level, but the mic is now much hotter than our own echo
        // could explain — someone is in the room and talking.
        assertTrue(frames(gate, mic = 0.9f, out = 0.4f, aec = false, toMs = 1_000L))
    }

    @Test
    fun `barge-in is easy and fast when AEC is doing its job`() {
        val gate = BargeInGate()
        // With echo cancelled, our own output does not reach the mic, so a
        // normal speaking voice is enough — and it lands quickly.
        assertTrue(frames(gate, mic = 0.10f, out = 0.9f, aec = true, toMs = 300L))
    }

    @Test
    fun `AEC lowers the bar substantially`() {
        val gate = BargeInGate()
        val cancelled = gate.thresholdFor(outputLevel = 0.8f, outputKnown = true, echoCancelled = true)
        val raw = gate.thresholdFor(outputLevel = 0.8f, outputKnown = true, echoCancelled = false)
        assertTrue("AEC must make the gate more sensitive, not less", cancelled < raw)
    }

    /* ── The dynamic floor: the gate breathes with our own voice ───────────── */

    @Test
    fun `threshold rises with our own output level`() {
        val gate = BargeInGate()
        val quiet = gate.thresholdFor(outputLevel = 0.1f, outputKnown = true, echoCancelled = false)
        val loud = gate.thresholdFor(outputLevel = 0.9f, outputKnown = true, echoCancelled = false)
        assertTrue("louder us ⇒ harder to be believed", loud > quiet)
    }

    @Test
    fun `the gate becomes sensitive in the gaps between our own words`() {
        val gate = BargeInGate()
        // Between syllables our output drops to ~0, so a human cutting in at a
        // very ordinary level should get through — which is exactly the moment
        // a person naturally tries to interject.
        assertTrue(frames(gate, mic = 0.20f, out = 0.0f, aec = false, toMs = 800L))
    }

    /* ── Sustain, not spikes ───────────────────────────────────────────────── */

    @Test
    fun `a single transient does not barge in`() {
        val gate = BargeInGate()
        // One hot frame — a door, a cough, a clipped consonant of our own.
        assertFalse(gate.onFrame(0.95f, 0.1f, true, false, 0L))
        // …and then the room goes quiet again, so the sustain never completes.
        assertFalse(gate.onFrame(0.02f, 0.1f, true, false, 20L))
        assertFalse(gate.onFrame(0.02f, 0.1f, true, false, 500L))
    }

    @Test
    fun `sustain must be continuous, not cumulative`() {
        val gate = BargeInGate()
        // Loud, quiet, loud — never continuously loud for the required window.
        assertFalse(gate.onFrame(0.9f, 0.0f, true, false, 0L))
        assertFalse(gate.onFrame(0.9f, 0.0f, true, false, 200L))
        assertFalse(gate.onFrame(0.01f, 0.0f, true, false, 220L)) // resets
        assertFalse(gate.onFrame(0.9f, 0.0f, true, false, 240L))
        assertFalse(gate.onFrame(0.9f, 0.0f, true, false, 400L))
    }

    @Test
    fun `uncancelled barge-in demands more sustain than cancelled`() {
        val withAec = BargeInGate()
        val withoutAec = BargeInGate()
        // 250 ms of clear speech: enough when AEC protects us, not yet enough
        // when we are grading against our own echo and must be sure.
        assertTrue(frames(withAec, mic = 0.5f, out = 0.0f, aec = true, toMs = 250L))
        assertFalse(frames(withoutAec, mic = 0.5f, out = 0.0f, aec = false, toMs = 250L))
    }

    /* ── Honesty when we cannot measure ourselves ──────────────────────────── */

    @Test
    fun `unknown output envelope falls back to a strict flat bar`() {
        val gate = BargeInGate()
        // System TTS: we have no idea how loud we are. We must not predict an
        // echo from an output level we do not know — so the bar is flat, and
        // strict enough that our own voice at an unknown level is unlikely to
        // clear it.
        val unknown = gate.thresholdFor(outputLevel = 0f, outputKnown = false, echoCancelled = false)
        val knownSilent = gate.thresholdFor(outputLevel = 0f, outputKnown = true, echoCancelled = false)
        assertTrue("not knowing must cost sensitivity, not grant it", unknown > knownSilent)
    }

    @Test
    fun `reset forgets a partial sustain`() {
        val gate = BargeInGate()
        assertFalse(gate.onFrame(0.9f, 0f, true, true, 0L))
        gate.reset()
        // The sustain clock restarts from here, so the frame that would have
        // completed the old window does not fire.
        assertFalse(gate.onFrame(0.9f, 0f, true, true, 100L))
        assertTrue(gate.onFrame(0.9f, 0f, true, true, 300L))
    }
}
