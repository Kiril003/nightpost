package local.phantom.companion.core.voice.duplex

/**
 * OMNIPRESENT VOICE — "did the operator just talk over me, or am I hearing
 * myself?"
 *
 * This is the one genuinely hard question in full duplex, and it has to be
 * answered on every audio frame while PHANTOM is speaking.
 *
 * The mic is open while the loudspeaker is playing PHANTOM's voice from a
 * few centimetres away. So the capture stream contains, at minimum:
 *
 *   mic = operator (maybe zero) + κ · our_own_output (never zero) + room
 *
 * A naive "mic is loud → the operator is barging in" test therefore fires on
 * PHANTOM's own first syllable, cancels his own utterance, and does it again
 * on the next one. The failure mode is not subtle: he becomes unable to
 * finish a sentence.
 *
 * ## Two regimes, because the hardware is not a given
 *
 * **AEC active** ([echoCancelled] = true). The platform's
 * [android.media.audiofx.AcousticEchoCanceler] has subtracted the far-end
 * signal from the capture stream. κ is small. We can be generous: modest
 * threshold, short sustain, so barge-in feels instant — which is the whole
 * point of duplex.
 *
 * **AEC unavailable**. κ is large and we get no help. But we are not blind:
 * we know *exactly* how loud we are right now, because we measured our own
 * PCM against the playback head ([local.phantom.companion.core.voice.audio.SpeechEnvelope]).
 * So we grade the mic against our own output rather than against a constant:
 * the operator has to be louder than the echo our current output could
 * plausibly produce, by a margin, for longer. This is crude next to a real
 * adaptive filter — but it is *honest*, it is testable, and it degrades to
 * "you have to speak up and mean it" instead of to "he interrupts himself".
 *
 * The floor rises and falls with our own voice in real time, so during the
 * quiet moments between PHANTOM's syllables the gate naturally becomes more
 * sensitive — which happens to be exactly when a human would try to cut in.
 *
 * Pure: no Android, no clock of its own. Feed it frames, get a verdict.
 */
class BargeInGate(
    /**
     * With echo cancelled, anything clearly above room noise is the operator.
     */
    private val cancelledThreshold: Float = 0.06f,

    /**
     * Without cancellation, the floor never drops below this no matter how
     * quiet we are — otherwise room noise in a gap between our own words
     * would read as an interruption.
     */
    private val uncancelledFloor: Float = 0.12f,

    /**
     * How much of our own output leaks back into the mic when the platform is
     * not cancelling it. No longer a constant: [EchoCouplingProbe] measures it
     * from this device's own audio while PHANTOM speaks unchallenged. The
     * pessimistic 0.75 survives only as the answer before there is evidence.
     */
    private val echoCoupling: () -> Float = { 0.75f },

    /**
     * Additional headroom above the predicted echo before we believe a human.
     */
    private val echoMargin: Float = 0.05f,

    /**
     * Sustain required with cancellation — short, so cut-in feels immediate.
     * The gate can afford to be jumpy now that a mistake costs a ~300 ms dip
     * in volume rather than a whole utterance (see the two-stage yield in
     * [DuplexVoiceEngine]).
     */
    private val cancelledSustainMs: Long = 120L,

    /**
     * Sustain required without cancellation. Longer: a single transient of our
     * own speech leaking through must not be mistaken for a human, but half a
     * second of someone talking cannot be anything else.
     */
    private val uncancelledSustainMs: Long = 400L,
) {

    /** Sentinel below any plausible clock (tests may start at 0). */
    private val unset = -1L
    private var aboveSinceMs = unset

    /**
     * Feed one frame captured while PHANTOM is speaking.
     *
     * @param micLevel 0..1 amplitude from the capture stream right now.
     * @param outputLevel 0..1 amplitude of our own audio leaving the speaker
     *   right now (0f if that is unknown — see [outputKnown]).
     * @param outputKnown false on a TTS route with no measurable envelope
     *   (Android System TTS). We then cannot predict our own echo at all, so
     *   we fall back to a flat, strict threshold rather than a dynamic floor.
     * @param echoCancelled whether an [android.media.audiofx.AcousticEchoCanceler]
     *   is genuinely attached and enabled on this capture session.
     * @param nowMs monotonic clock.
     *
     * @return true exactly once, on the frame at which the operator's speech
     *   has been sustained long enough to be believed. The caller should stop
     *   the TTS and [reset] the gate.
     */
    fun onFrame(
        micLevel: Float,
        outputLevel: Float,
        outputKnown: Boolean,
        echoCancelled: Boolean,
        nowMs: Long,
    ): Boolean {
        val threshold = thresholdFor(outputLevel, outputKnown, echoCancelled)
        val sustainMs = if (echoCancelled) cancelledSustainMs else uncancelledSustainMs

        if (micLevel < threshold) {
            aboveSinceMs = unset
            return false
        }
        if (aboveSinceMs == unset) {
            aboveSinceMs = nowMs
            // A sustain of 0 should fire immediately rather than on the next frame.
            return sustainMs <= 0L
        }
        if (nowMs - aboveSinceMs >= sustainMs) {
            aboveSinceMs = unset
            return true
        }
        return false
    }

    /**
     * The live bar the operator's voice must clear. Exposed for tests and for
     * diagnostics — being able to see this number on device is what makes
     * [echoCoupling] tunable rather than guessed forever.
     */
    fun thresholdFor(outputLevel: Float, outputKnown: Boolean, echoCancelled: Boolean): Float {
        if (echoCancelled) return cancelledThreshold

        // No envelope (System TTS): we cannot model our own echo, so a flat
        // strict bar is all we honestly have. Predicting an echo from an
        // output level we do not know would be inventing data.
        val kappa = echoCoupling()
        if (!outputKnown) return uncancelledFloor + kappa * echoMargin + echoMargin

        val predictedEcho = kappa * outputLevel.coerceIn(0f, 1f)
        return maxOf(uncancelledFloor, predictedEcho + echoMargin)
    }

    /** Forget any partial sustain — call on barge-in and when speech ends. */
    fun reset() {
        aboveSinceMs = unset
    }
}
