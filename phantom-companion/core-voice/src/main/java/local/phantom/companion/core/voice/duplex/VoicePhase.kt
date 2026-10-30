package local.phantom.companion.core.voice.duplex

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * OMNIPRESENT VOICE — the acoustic state of the channel, as one value.
 *
 * This is what the Orb renders. It is deliberately *physical* rather than
 * conversational: it says what is happening to air and silicon right now,
 * not what the dialogue means. A UI that knows the phase knows everything it
 * needs to visualise the symbiote without ever asking the Chat module a
 * question — which is precisely what lets the voice be omnipresent while the
 * Chat stratum is three depths away behind the glass.
 */
enum class VoicePhase {
    /** The channel is closed. No mic, no voice. The Orb is simply itself. */
    Dormant,

    /** Mic is open and nothing of consequence is arriving. */
    Listening,

    /** Mic is open and the operator is actively speaking into it. */
    Hearing,

    /** The operator stopped; his words are in flight to the model. */
    Processing,

    /** PHANTOM is speaking. The mic stays open — this is a duplex channel. */
    Speaking,
}

/**
 * Process-global acoustic phase + the one amplitude that matters for it.
 *
 * The Orb reads exactly this and nothing else. Note that [amplitude] is
 * *phase-relative*: during [VoicePhase.Hearing] it is the operator's voice
 * (from [local.phantom.companion.core.voice.AudioLevelHub]), during
 * [VoicePhase.Speaking] it is PHANTOM's own output envelope (from
 * [local.phantom.companion.core.voice.SpeechActivityHub]) — the visualiser
 * should not have to know which hub is which, only "how hard is the thing
 * that is currently happening".
 *
 * [amplitudeKnown] is the honesty valve. During [VoicePhase.Speaking] on the
 * Android System TTS route there is no envelope to be had, so this is false
 * and [amplitude] stays 0f. A consumer must then render *that he is
 * speaking* without pretending to know how loudly. Never synthesise a curve
 * to fill this in — that bug has already been fixed once.
 */
object VoicePhaseHub {

    private val _phase = MutableStateFlow(VoicePhase.Dormant)
    val phase: StateFlow<VoicePhase> = _phase.asStateFlow()

    private val _amplitude = MutableStateFlow(0f)

    /** 0..1 energy of whatever the current [phase] is about. */
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private val _amplitudeKnown = MutableStateFlow(false)

    /** False when the current phase has no measurable amplitude — see class doc. */
    val amplitudeKnown: StateFlow<Boolean> = _amplitudeKnown.asStateFlow()

    fun publish(phase: VoicePhase, amplitude: Float, amplitudeKnown: Boolean) {
        _phase.value = phase
        _amplitude.value = amplitude.coerceIn(0f, 1f)
        _amplitudeKnown.value = amplitudeKnown
    }

    fun reset() {
        _phase.value = VoicePhase.Dormant
        _amplitude.value = 0f
        _amplitudeKnown.value = false
    }
}
