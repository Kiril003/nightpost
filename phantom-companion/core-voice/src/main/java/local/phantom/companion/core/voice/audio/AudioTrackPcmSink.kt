package local.phantom.companion.core.voice.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import local.phantom.companion.core.voice.SpeechActivityHub

/**
 * [PcmSink] over a MODE_STREAM [AudioTrack].
 *
 * This replaces the old per-utterance MODE_STATIC + `Thread.sleep`
 * pattern: one track is opened per utterance and *streamed* into, so
 * fragmented synthesis chunks play gaplessly the moment they exist —
 * chunk k is audible while chunk k+1 is still being synthesized. An
 * underrun (synthesis slower than speech for a moment) yields silence,
 * never a crash or a torn buffer, and playback resumes when the next
 * chunk lands.
 *
 * The track carries USAGE_ASSISTANT | CONTENT_TYPE_SPEECH so the OS
 * routes/ducks it as the voice of the device, and LOW_LATENCY
 * performance mode (AAudio-backed on this minSdk) keeps the output
 * buffer small. [isNavigation] swaps USAGE_ASSISTANT for
 * USAGE_ASSISTANCE_NAVIGATION_GUIDANCE — the channel Android Auto
 * actually routes to the car speakers — for the one caller that speaks
 * turn cues; every other utterance (chat, live dialogue) leaves this
 * false and keeps today's routing untouched.
 *
 * No Compose, no Activity, no Lifecycle anywhere in this class — the
 * track lives on whatever thread the engine drives it from, which is
 * what makes the voice immune to strata changes in the UI.
 *
 * ## OMNIPRESENT VOICE — the speaking envelope
 *
 * This is the only place in the process that knows both *what PCM we are
 * about to play* and *which frame the hardware is actually rendering*. So
 * this is the only place that can publish an honest speaking amplitude:
 * every [write] records the buffer's energy into a [SpeechEnvelope] keyed
 * by frame index, and a poller thread samples that envelope at the live
 * `playbackHeadPosition` and pushes it to [SpeechActivityHub]. The Orb
 * therefore pulses with the syllable the operator is *hearing*, not the
 * one ONNX happens to be synthesizing several seconds ahead.
 *
 * The envelope doubles as the reference signal for barge-in: knowing how
 * loud we are right now is what lets
 * [local.phantom.companion.core.voice.duplex.BargeInGate] tell the
 * operator's voice apart from our own echo coming back through the mic.
 */
class AudioTrackPcmSink(private val isNavigation: Boolean = false) : PcmSink {

    private var track: AudioTrack? = null
    private var framesWritten = 0L
    private var sampleRate = 0

    private val envelope = SpeechEnvelope()
    private var poller: Thread? = null

    @Volatile
    private var polling = false

    override fun start(sampleRate: Int) {
        release()
        this.sampleRate = sampleRate
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT,
        )
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(
                        if (isNavigation) {
                            AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
                        } else {
                            AudioAttributes.USAGE_ASSISTANT
                        },
                    )
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setBufferSizeInBytes(maxOf(minBuf, MIN_BUFFER_BYTES))
            .build()
        t.play()
        track = t
        framesWritten = 0L
        synchronized(envelope) { envelope.reset() }

        // The session id is what an AcousticEchoCanceler on the *capture*
        // side ultimately has to cancel against; publishing it lets the
        // duplex engine know a real output stream is live.
        SpeechActivityHub.beginEnvelope()
        startPolling()
    }

    override fun write(samples: FloatArray, offset: Int, length: Int) {
        val t = track ?: return
        // Record before the blocking write: these are exactly the samples
        // the hardware is about to render, and the frame cursor must be
        // advanced in stream order regardless of how long the write parks.
        synchronized(envelope) { envelope.append(samples, offset, length, sampleRate) }

        var written = 0
        while (written < length) {
            val n = t.write(
                samples, offset + written, length - written,
                AudioTrack.WRITE_BLOCKING,
            )
            if (n <= 0) return // track released/died underneath us — abort quietly
            written += n
        }
        framesWritten += written
    }

    override fun awaitDrained() {
        val t = track ?: return
        // playbackHeadPosition is frames actually rendered; poll until it
        // catches up with what we wrote. Deadline guards against a stalled
        // route (BT teardown etc.) turning drain into a hang.
        val remainingFrames = framesWritten - t.playbackHeadPosition.toUInt().toLong()
        val deadline = SystemClock.elapsedRealtime() +
            (remainingFrames * 1000 / t.sampleRate) + DRAIN_GRACE_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val current = track ?: return
            if (current.playbackHeadPosition.toUInt().toLong() >= framesWritten) return
            SystemClock.sleep(DRAIN_POLL_MS)
        }
    }

    override fun flush() {
        track?.let { t ->
            runCatching {
                t.pause()
                t.flush()
            }
        }
    }

    override fun setGain(gain: Float) {
        track?.let { t -> runCatching { t.setVolume(gain.coerceIn(0f, 1f)) } }
    }

    override fun framesRendered(): Long =
        track?.let { t -> runCatching { t.playbackHeadPosition.toUInt().toLong() }.getOrNull() } ?: 0L

    override fun release() {
        stopPolling()
        track?.let { t ->
            runCatching {
                t.pause()
                t.flush()
                t.release()
            }
        }
        track = null
        framesWritten = 0L
        // Whatever happened — drained, cancelled mid-buffer, or the route
        // died — nothing of ours is audible now. Say so, or the Orb keeps
        // pulsing at whatever amplitude it last saw.
        SpeechActivityHub.endEnvelope()
    }

    /**
     * Sample the envelope at the live playback head and publish. Runs on its
     * own thread rather than the feeder: the feeder spends most of its life
     * parked inside a blocking `write`, which is precisely when the Orb most
     * needs updating (the hardware is busy rendering a long buffer).
     */
    private fun startPolling() {
        polling = true
        poller = Thread({
            while (polling) {
                val t = track ?: break
                val head = runCatching { t.playbackHeadPosition.toUInt().toLong() }.getOrNull() ?: break
                val level = synchronized(envelope) { envelope.sampleAt(head) }
                // Speech RMS sits well below full scale; lift it into the
                // 0..1 the visualiser expects without ever clipping past 1.
                SpeechActivityHub.publishAmplitude((level * ENVELOPE_GAIN).coerceIn(0f, 1f))
                SystemClock.sleep(POLL_MS)
            }
        }, "phantom-voice-envelope").apply {
            isDaemon = true
            start()
        }
    }

    private fun stopPolling() {
        polling = false
        poller?.interrupt()
        poller = null
    }

    private companion object {
        // Roomy enough that a scheduling hiccup on the feeder thread does
        // not underrun; small enough that stop() feels immediate.
        const val MIN_BUFFER_BYTES = 64 * 1024
        const val DRAIN_POLL_MS = 20L
        const val DRAIN_GRACE_MS = 2_000L

        /** ~60 Hz: the Orb cannot show more than the display can draw. */
        const val POLL_MS = 16L

        /**
         * Speech RMS on a normalised float track lands around 0.05–0.25 even
         * when subjectively loud. Without a lift the Orb would barely twitch.
         * This is a display gain on a real measurement — not a synthesised
         * curve — so the *shape* still belongs to the voice.
         */
        const val ENVELOPE_GAIN = 4f
    }
}
