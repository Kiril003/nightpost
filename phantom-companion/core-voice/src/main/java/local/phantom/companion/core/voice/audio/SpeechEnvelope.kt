package local.phantom.companion.core.voice.audio

import kotlin.math.sqrt

/**
 * OMNIPRESENT VOICE — the true output envelope of PHANTOM's own speech.
 *
 * ## Why this class has to exist at all
 *
 * The Orb is asked to pulse with what PHANTOM is *actually saying*. The
 * naive way to do that is to RMS each synthesized chunk as it is produced
 * and publish it. That would be a lie, and an obvious one on device: the
 * streaming pipeline synthesizes segment k+1 *while segment k is playing*
 * (that is the whole point of [PcmStreamPlayer]), and [AudioTrackPcmSink]
 * hands ~64 KB to the hardware before a single sample is audible. Publish
 * at synthesis time and the Orb pulses a half-second to several seconds
 * *before* the voice — the operator sees a mouth moving out of sync with
 * its words.
 *
 * So amplitude is recorded here against the only clock that cannot lie:
 * the **frame index**. Chunk k occupies frames [start, start+n). The sink
 * separately polls `AudioTrack.playbackHeadPosition` — the count of frames
 * the hardware has genuinely *rendered* — and asks this timeline "what is
 * under the head right now?". The result is an envelope in sync with what
 * the ear hears, and it stays in sync through an underrun, because an
 * underrun stalls the playback head too.
 *
 * ## Honesty
 *
 * This only ever describes PCM we generated ourselves — i.e. the Supertonic
 * route. Android's system TTS emits no PCM to us and no envelope exists for
 * it; nothing here invents one. See [local.phantom.companion.core.voice.SpeechActivityHub].
 *
 * Not thread-safe by itself; [AudioTrackPcmSink] confines it to the feeder
 * thread for writes and guards the read. Pure Kotlin — no Android, so the
 * sync maths is unit-testable on the JVM.
 */
class SpeechEnvelope(
    /**
     * Envelope resolution. 20 ms is below the eye's flicker-fusion for a
     * glow (~60 Hz frame budget) and above the period of any speech
     * fundamental, so a window carries syllable energy rather than the
     * carrier waveform — the Orb should breathe with syllables, not buzz
     * at 100 Hz.
     */
    private val windowMs: Int = 20,
    /**
     * Ring capacity in windows. 10 s of speech at 20 ms is plenty: windows
     * behind the playback head are dead weight, and an utterance longer
     * than the ring simply drops its own distant past, which nothing reads.
     */
    private val capacity: Int = 512,
) {

    /** Frame index at which window i begins. Parallel array to [rms]. */
    private val startFrame = LongArray(capacity)
    private val rms = FloatArray(capacity)

    /** Monotonic count of windows ever appended; the ring index is this % capacity. */
    private var written = 0L

    /** Frames appended so far — where the next chunk will begin. */
    private var frameCursor = 0L

    /** Reset to a fresh utterance. */
    fun reset() {
        written = 0L
        frameCursor = 0L
    }

    /**
     * Record [length] samples from [samples] at [offset] as the next audio
     * in the stream, splitting them into [windowMs] windows.
     *
     * Called on the feeder thread with exactly the buffer being handed to
     * the hardware, so what is recorded is what will be heard.
     */
    fun append(samples: FloatArray, offset: Int, length: Int, sampleRate: Int) {
        if (length <= 0 || sampleRate <= 0) return
        val windowFrames = (sampleRate * windowMs / 1000).coerceAtLeast(1)

        var i = 0
        while (i < length) {
            val n = minOf(windowFrames, length - i)
            var sum = 0.0
            for (j in 0 until n) {
                val s = samples[offset + i + j]
                sum += s.toDouble() * s
            }
            val slot = (written % capacity).toInt()
            startFrame[slot] = frameCursor + i
            rms[slot] = sqrt(sum / n).toFloat().coerceIn(0f, 1f)
            written++
            i += n
        }
        frameCursor += length
    }

    /**
     * The amplitude of the audio being rendered *right now*, given the
     * hardware's [headFrame] (`AudioTrack.playbackHeadPosition`).
     *
     * Returns 0f before playback starts, after it has passed everything we
     * appended, or if the head has fallen off the back of the ring — all of
     * which mean "nothing of ours is audible", which is the honest answer.
     */
    fun sampleAt(headFrame: Long): Float {
        if (written == 0L || headFrame < 0L) return 0f

        // Oldest window still in the ring.
        val oldest = maxOf(0L, written - capacity)

        // The head has outrun everything we recorded: silence (or drained).
        val newestSlot = ((written - 1) % capacity).toInt()
        if (headFrame >= startFrame[newestSlot] + windowFramesOf(newestSlot)) return 0f

        // Windows are strictly increasing in startFrame, so binary-search the
        // last window whose start is <= head. Linear scan would be fine at
        // 512, but this is polled every frame while speaking.
        var lo = oldest
        var hi = written - 1
        var found = -1L
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val slot = (mid % capacity).toInt()
            if (startFrame[slot] <= headFrame) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (found < 0L) return 0f // head is behind the ring's oldest surviving window
        return rms[(found % capacity).toInt()]
    }

    /**
     * Window [slot] runs until the next window starts; the newest window has
     * no successor, so fall back to its nominal length. Only used to decide
     * "has the head passed the end of our audio", so the approximation at the
     * tail is harmless.
     */
    private fun windowFramesOf(slot: Int): Long {
        val idx = written - 1
        if (idx <= 0L) return 1L
        val prevSlot = ((idx - 1) % capacity).toInt()
        val len = startFrame[slot] - startFrame[prevSlot]
        return if (len > 0L) len else 1L
    }
}
