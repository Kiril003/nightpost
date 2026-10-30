package local.phantom.companion.core.sensor.sources

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.log10

/**
 * MIC-1 — passive dB-RMS estimator.
 *
 * Before BW2 this class opened its *own* always-on [android.media.AudioRecord]
 * to drive the orb visualiser, which kept the system mic indicator on
 * 24/7 and broke operator trust. The bytes never left the class, but the
 * orange/green dot in the status bar reads "you are being recorded" and
 * most operators correctly assume the worst.
 *
 * After BW2 the contract inverts: this source consumes whatever 0..1
 * amplitude the *one* legitimate microphone owner is currently publishing
 * (PTT [local.phantom.companion.core.voice.VoiceManager], Live Dialog,
 * an explicit wardriving recording). The provider returns null when no
 * recorder is active, in which case [state] holds null and downstream
 * consumers (SystemPulseRepository VOICE dot, AmbientAudioNode) treat
 * the channel as "off" instead of inventing a fake reading.
 *
 * Wiring lives in `AppContainer.sensorAdapter` — the lambda reads
 * `AudioLevelHub.level.value`, the same hub the AudioRecorder writes
 * into for every chunk it captures.
 */
class MicRmsSource(
    private val audioLevelProvider: () -> Float? = { null },
) {

    private val _state = MutableStateFlow<Float?>(null)
    val state: StateFlow<Float?> = _state.asStateFlow()

    /** No-op kept for API compatibility — the source no longer owns a recorder. */
    @Suppress("UNUSED_PARAMETER")
    fun start(scope: CoroutineScope) = Unit

    /** Pull the current dBFS reading from the live audio-level provider. */
    fun refresh() {
        _state.value = computeDb(audioLevelProvider())
    }

    fun stop() {
        _state.value = null
    }

    companion object {
        /**
         * Convert 0..1 normalised amplitude to dBFS, returning null when
         * no live signal is present. Matches the original AudioRecord-based
         * implementation: `20·log10(rms / 32768)` where the input here is
         * already `rms / 32768` (see `AudioRecorder.calculateLevel`). Floor
         * at -100 dB to match the legacy contract; AmbientAudioNode treats
         * < -55 dB as "quiet".
         */
        fun computeDb(level: Float?): Float? {
            if (level == null) return null
            if (level <= 0f) return null
            return (20f * log10(level)).coerceAtLeast(-100f)
        }
    }
}
