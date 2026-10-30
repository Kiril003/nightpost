package local.phantom.companion.core.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-global publisher for PHANTOM's *own* speech — the mirror of
 * [AudioLevelHub], which only ever describes the operator's microphone.
 *
 * Before this existed, the UI had no way to know PHANTOM was talking: the
 * voice overlay was gated purely on `capturing` (mic held down), so the
 * whole speaking half of a conversation was invisible. He would answer out
 * loud and nothing on screen moved.
 *
 * Whoever owns the utterance ([local.phantom.companion.core.voice.route.RoutedTts])
 * publishes [begin]/[end]; whoever owns the *samples*
 * ([local.phantom.companion.core.voice.audio.AudioTrackPcmSink]) publishes
 * the envelope.
 *
 * ## Amplitude is conditional, and that is the whole point
 *
 * This hub used to carry no amplitude at all, deliberately: Android's
 * `TextToSpeech` gives no output envelope, and the visual it replaced was a
 * hardcoded `sin()` curve scaled by one scalar — every bar twitching in
 * lockstep, showing the operator nothing real.
 *
 * That constraint has *not* been repealed; it has been narrowed to the route
 * it actually applies to. The premium route (Supertonic) streams raw PCM
 * through our own [android.media.AudioTrack], so its true energy is
 * measurable, and [amplitude] carries it — sampled against the hardware
 * playback head, so it is in sync with what the ear hears.
 *
 * Android System TTS still gives us nothing, so on that route [hasEnvelope]
 * stays **false** and [amplitude] stays **0f**. Consumers must branch on
 * [hasEnvelope] and render a non-amplitude speaking state rather than
 * inventing a waveform. `speaking && !hasEnvelope` means: *he is talking,
 * and we honestly do not know how loudly.*
 */
object SpeechActivityHub {

    private val _speaking = MutableStateFlow(false)

    /** True while an utterance is being spoken by any TTS route. */
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    private val _text = MutableStateFlow<String?>(null)

    /** The line currently being spoken; null at rest. */
    val text: StateFlow<String?> = _text.asStateFlow()

    private val _hasEnvelope = MutableStateFlow(false)

    /**
     * True only while a route that can actually measure its own output is
     * driving the speaker. False on System TTS — see the class doc. A
     * visualiser that ignores this flag and reads [amplitude] anyway will
     * simply render a flat 0, which is at least not a lie.
     */
    val hasEnvelope: StateFlow<Boolean> = _hasEnvelope.asStateFlow()

    private val _amplitude = MutableStateFlow(0f)

    /**
     * 0..1 energy of the audio *currently leaving the speaker*, or 0f when
     * unknown/silent. Only meaningful while [hasEnvelope] is true.
     */
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    /** Producer-side: an utterance started. */
    fun begin(spoken: String) {
        _text.value = spoken
        _speaking.value = true
    }

    /** Producer-side: the utterance finished, failed, or was stopped. */
    fun end() {
        _speaking.value = false
        _text.value = null
        // Belt and braces: the PCM sink clears its own envelope on release,
        // but a route that never opened one (System TTS) must not leave a
        // stale amplitude behind from a previous premium utterance.
        _hasEnvelope.value = false
        _amplitude.value = 0f
    }

    /** Producer-side: a measurable PCM output stream opened. */
    fun beginEnvelope() {
        _hasEnvelope.value = true
        _amplitude.value = 0f
    }

    /** Producer-side: the measurable output stream closed. */
    fun endEnvelope() {
        _hasEnvelope.value = false
        _amplitude.value = 0f
    }

    /** Producer-side: the energy of the frame the hardware is rendering now. */
    fun publishAmplitude(value: Float) {
        _amplitude.value = value.coerceIn(0f, 1f)
    }
}
