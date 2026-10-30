package local.phantom.companion.core.voice.route

/**
 * Prosody the operator controls: how high the voice sits and how fast it goes.
 *
 * Until 31.08 nothing in this tree ever called `setPitch`, `setSpeechRate`,
 * `setVoice` or `getVoices` — grep across every module returned zero. The
 * system engine therefore spoke on whatever the vendor shipped, at whatever
 * speed, in whichever voice the engine happened to list first. The owner's
 * complaint («інтонація… взагалі не задіювалась») was not an impression: the
 * knobs existed in Android the whole time and nobody in PHANTOM ever turned
 * one.
 *
 * This lives in `core-voice` and holds no Android types on purpose, so the
 * clamping below can be tested without a device. The clamping is the part
 * worth testing: [android.speech.tts.TextToSpeech] accepts **any** float and
 * says nothing, so a stray `0f`, a negative, or a NaN arriving from a slider
 * or a corrupted preference produces silence or gibberish with no error to
 * read afterwards. A value the engine cannot refuse must be refused here.
 */
/**
 * НЕ ПЛУТАТИ з `…voice.supertonic.VoiceTuning`: той — object, що тримає
 * обраний СТИЛЬ рушія Supertonic. Тут — просодія системного TTS: висота й
 * швидкість, які крутить оператор. Різні рушії, різні угоди, спільне лише імʼя.
 */
data class VoiceTuning(
    /** 1.0 is the engine's own pitch; below is deeper, above is higher. */
    val pitch: Float = NEUTRAL,
    /** 1.0 is the engine's own speed; below is slower, above is faster. */
    val rate: Float = NEUTRAL,
) {
    companion object {
        const val NEUTRAL = 1.0f

        /**
         * Deliberately narrower than what the engine tolerates.
         *
         * Android accepts 0.5–2.0 and will happily go further; but a voice
         * that reads a route instruction at 0.25× finishes the sentence after
         * the turn, and at 4× is not language. These bounds are about the
         * product's job — being understood while the hands are busy — not
         * about what the codec survives.
         */
        const val MIN = 0.5f
        const val MAX = 2.0f

        /**
         * Build a tuning that the engine cannot be hurt by.
         *
         * NaN maps to [NEUTRAL] rather than to a bound: NaN means «this number
         * was never really computed», and the honest answer to that is the
         * default, not the nearest edge. Clamping it to MIN would silently
         * turn a broken value into a deliberate-looking slow voice.
         */
        fun of(pitch: Float, rate: Float): VoiceTuning =
            VoiceTuning(sane(pitch), sane(rate))

        private fun sane(v: Float): Float = when {
            v.isNaN() -> NEUTRAL
            v < MIN -> MIN
            v > MAX -> MAX
            else -> v
        }
    }
}
