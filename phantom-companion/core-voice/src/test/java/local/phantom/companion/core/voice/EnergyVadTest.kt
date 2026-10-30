package local.phantom.companion.core.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * SV-2 — pin the dual-threshold + duration-gating contract that
 * LiveDialogService relies on. These thresholds determine whether the
 * operator's "Фантоме, скільки годин" gets caught as one turn or chopped
 * into pieces; regression tests stop a future "let's tune the magic
 * numbers" PR from silently degrading the experience.
 */
class EnergyVadTest {

    @Test
    fun `silence stays silent`() {
        val vad = EnergyVad()
        for (t in 0L..2_000L step 100L) {
            assertNull(vad.onAmplitude(0.0f, t))
        }
    }

    @Test
    fun `single noisy spike does not trigger SpeechStart`() {
        val vad = EnergyVad()
        // One tick above threshold, then back to silence.
        assertNull(vad.onAmplitude(0.5f, 0L))
        for (t in 100L..2_000L step 100L) {
            assertNull(vad.onAmplitude(0.0f, t))
        }
    }

    @Test
    fun `sustained amplitude triggers SpeechStart after debounce`() {
        val vad = EnergyVad()
        // 200 ms above (under the 250 ms threshold) → no event yet.
        assertNull(vad.onAmplitude(0.2f, 0L))
        assertNull(vad.onAmplitude(0.2f, 100L))
        assertNull(vad.onAmplitude(0.2f, 200L))
        // 300 ms above (now over 250 ms) → SpeechStart.
        val ev = vad.onAmplitude(0.2f, 300L)
        assertEquals(VadEvent.SpeechStart, ev)
    }

    @Test
    fun `SpeechEnd needs sustained silence not a single quiet sample`() {
        val vad = EnergyVad()
        // Get into SPEAKING state.
        for (t in 0L..400L step 100L) vad.onAmplitude(0.2f, t)
        // One quiet sample at 500 ms doesn't end speech (need 800 ms).
        assertNull(vad.onAmplitude(0.0f, 500L))
        assertNull(vad.onAmplitude(0.2f, 600L)) // back to speaking
        assertNull(vad.onAmplitude(0.2f, 700L))
        // Now sustained quiet.
        assertNull(vad.onAmplitude(0.0f, 800L))
        assertNull(vad.onAmplitude(0.0f, 1_200L))
        val ev = vad.onAmplitude(0.0f, 1_700L) // 900 ms continuous quiet
        assertEquals(VadEvent.SpeechEnd, ev)
    }

    @Test
    fun `natural pause inside an utterance does not split into two turns`() {
        // "Фантоме… скільки зараз годин" — the operator pauses for ~400 ms
        // between words. The 800 ms silence threshold should keep the
        // utterance as a single turn.
        val vad = EnergyVad()
        // First phrase 0..500 ms.
        for (t in 0L..500L step 100L) vad.onAmplitude(0.2f, t)
        // 400 ms quiet pause.
        for (t in 600L..900L step 100L) {
            assertNull(vad.onAmplitude(0.0f, t))
        }
        // Second phrase resumes.
        assertNull(vad.onAmplitude(0.2f, 1_000L))
        // Final long silence ends the turn.
        for (t in 1_100L..1_900L step 100L) vad.onAmplitude(0.2f, t)
        for (t in 2_000L..2_700L step 100L) {
            // Quiet from 2_000 — first SpeechEnd at 2_000+800 = 2_800.
            // Verify no event in this window.
            val ev = vad.onAmplitude(0.0f, t)
            assertNull("unexpected event at $t: $ev", ev)
        }
        assertEquals(VadEvent.SpeechEnd, vad.onAmplitude(0.0f, 2_900L))
    }

    @Test
    fun `noisy room still ends the turn`() {
        // Кімната з рівним шумом 0.03 — вище за абсолютний поріг тиші 0.02.
        // На фіксованих порогах SpeechEnd не наставав ніколи, і жива
        // розмова мовчала у відповідь.
        val vad = EnergyVad()
        val noise = 0.03f
        // Даємо підлогу вирівнятись під кімнату.
        for (t in 0L..3_000L step 100L) assertNull(vad.onAmplitude(noise, t))
        // Голос помітно гучніший за шум.
        var started: VadEvent? = null
        for (t in 3_100L..3_600L step 100L) {
            vad.onAmplitude(0.4f, t)?.let { started = it }
        }
        assertEquals(VadEvent.SpeechStart, started)
        // Повернення до шуму кімнати має рахуватись тишею.
        var ended: VadEvent? = null
        for (t in 3_700L..4_900L step 100L) {
            vad.onAmplitude(noise, t)?.let { ended = it }
        }
        assertEquals(VadEvent.SpeechEnd, ended)
    }

    @Test
    fun `reset returns to SILENT state`() {
        val vad = EnergyVad()
        for (t in 0L..400L step 100L) vad.onAmplitude(0.2f, t)
        // We're SPEAKING; reset should send us back so the next loud
        // sample requires a fresh debounce.
        vad.reset()
        assertNull(vad.onAmplitude(0.5f, 500L))
        // Still need sustained.
        assertNull(vad.onAmplitude(0.5f, 600L))
        assertNotNull(vad.onAmplitude(0.5f, 800L))
    }
}
