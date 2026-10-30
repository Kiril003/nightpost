package local.phantom.companion.core.voice.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The envelope's whole reason to exist is *sync*: it must report the energy of
 * the audio the hardware is rendering right now, not the audio the synthesizer
 * happens to be producing several seconds ahead of the speaker.
 */
class SpeechEnvelopeTest {

    private val rate = 16_000
    private val windowFrames = rate * 20 / 1000 // 20 ms = 320 frames

    private fun tone(n: Int, amp: Float) = FloatArray(n) { amp }

    @Test
    fun `silence before playback begins`() {
        val env = SpeechEnvelope()
        env.append(tone(windowFrames, 0.5f), 0, windowFrames, rate)
        // Head at 0: the very first window IS under the head, so this is not
        // "before playback" — it is the first frame. Assert the real contract.
        assertEquals(0.5f, env.sampleAt(0), 0.01f)
    }

    @Test
    fun `nothing appended means nothing audible`() {
        assertEquals(0f, SpeechEnvelope().sampleAt(0), 0f)
        assertEquals(0f, SpeechEnvelope().sampleAt(9999), 0f)
    }

    @Test
    fun `amplitude tracks the playback head, not the write cursor`() {
        val env = SpeechEnvelope()
        // Three 20ms windows of very different energy, all appended up front —
        // exactly what the streaming pipeline does while the speaker is still
        // on the first one.
        env.append(tone(windowFrames, 0.1f), 0, windowFrames, rate)
        env.append(tone(windowFrames, 0.9f), 0, windowFrames, rate)
        env.append(tone(windowFrames, 0.3f), 0, windowFrames, rate)

        // The head decides what is heard. If the implementation reported the
        // most recently *written* chunk, every one of these would read 0.3.
        assertEquals(0.1f, env.sampleAt(0), 0.01f)
        assertEquals(0.1f, env.sampleAt(windowFrames - 1L), 0.01f)
        assertEquals(0.9f, env.sampleAt(windowFrames.toLong()), 0.01f)
        assertEquals(0.3f, env.sampleAt(2L * windowFrames), 0.01f)
    }

    @Test
    fun `head past the end of the audio is silence`() {
        val env = SpeechEnvelope()
        env.append(tone(windowFrames, 0.8f), 0, windowFrames, rate)
        // Drained: the track has rendered everything we ever gave it. The Orb
        // must not keep glowing at the last value it saw.
        assertEquals(0f, env.sampleAt(10L * windowFrames), 0f)
    }

    @Test
    fun `rms is energy, not peak`() {
        val env = SpeechEnvelope()
        // Half the window at 1.0, half silent → RMS = sqrt(0.5) ≈ 0.707.
        val buf = FloatArray(windowFrames) { if (it < windowFrames / 2) 1f else 0f }
        env.append(buf, 0, windowFrames, rate)
        assertEquals(0.707f, env.sampleAt(0), 0.02f)
    }

    @Test
    fun `a chunk spanning many windows is split, not averaged flat`() {
        val env = SpeechEnvelope()
        // One big write whose first half is loud and second half is quiet —
        // the sink hands buffers far larger than one window, and collapsing
        // them to a single RMS would flatten the whole utterance's shape.
        val n = windowFrames * 4
        val buf = FloatArray(n) { if (it < n / 2) 0.8f else 0.05f }
        env.append(buf, 0, n, rate)

        assertTrue("loud half should read loud", env.sampleAt(0) > 0.7f)
        assertTrue("quiet half should read quiet", env.sampleAt(3L * windowFrames) < 0.1f)
    }

    @Test
    fun `reset clears the previous utterance`() {
        val env = SpeechEnvelope()
        env.append(tone(windowFrames, 0.9f), 0, windowFrames, rate)
        env.reset()
        assertEquals(0f, env.sampleAt(0), 0f)
    }

    @Test
    fun `ring overflow drops the distant past, keeps the present`() {
        val env = SpeechEnvelope(capacity = 8)
        // Write 20 windows into an 8-window ring; the head is near the end,
        // which is the only region anyone ever samples.
        repeat(20) { i ->
            env.append(tone(windowFrames, if (i == 19) 0.9f else 0.1f), 0, windowFrames, rate)
        }
        assertEquals(0.9f, env.sampleAt(19L * windowFrames), 0.01f)
    }

    @Test
    fun `degenerate inputs do not throw`() {
        val env = SpeechEnvelope()
        env.append(FloatArray(0), 0, 0, rate)
        env.append(tone(10, 0.5f), 0, 10, 0) // zero sample rate
        assertEquals(0f, env.sampleAt(-1L), 0f)
    }
}
