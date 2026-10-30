package local.phantom.companion.core.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * MIC-1 — process-global audio-level publisher.
 *
 * Whichever component currently owns the microphone (PTT [VoiceManager],
 * a future Live Dialog service, a wardriving recorder) writes its 0..1
 * amplitude into this hub. Passive consumers — the orb visualiser, the
 * SystemPulse VOICE signal, [local.phantom.companion.core.sensor.sources.MicRmsSource]
 * — read from here without opening their own [android.media.AudioRecord].
 *
 * This is the load-bearing invariant of the BW2 microphone-transparency
 * refactor: there is *one* [android.media.AudioRecord] in the process at
 * any time, and the system mic indicator can flip off the moment the
 * sole owner releases. Multiple producers writing to the hub is fine —
 * Android only allows one [android.media.AudioRecord] alive at a time
 * anyway, so the writes are serialised by hardware.
 *
 * The hub holds 0f at rest. A consumer treating "is the mic actually
 * live?" should compare to `> 0f` (the producer resets to 0f on stop).
 */
object AudioLevelHub {
    private val _level = MutableStateFlow(0f)

    /** Current 0..1 amplitude. 0f = no recorder is active. */
    val level: StateFlow<Float> = _level.asStateFlow()

    /** Producer-side: publish the latest amplitude (clamped to 0..1). */
    fun publish(value: Float) {
        _level.value = value.coerceIn(0f, 1f)
    }

    /** Producer-side: clear the level when releasing the mic. */
    fun reset() {
        _level.value = 0f
    }
}
