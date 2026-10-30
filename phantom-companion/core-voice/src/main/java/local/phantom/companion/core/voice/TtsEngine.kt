package local.phantom.companion.core.voice

/**
 * TTS abstraction. Implementations: [route.AndroidSystemTts] (immediate
 * uk-UA via android.speech.tts.TextToSpeech) and [route.SupertonicRoute]
 * (Supertonic 3 ONNX on-device, streaming segment-pipelined playback,
 * downloadable from Settings → Голос).
 *
 * The composite [route.RoutedTts] picks Supertonic when its model files
 * are present, falls back to System TTS otherwise. Operator code talks
 * only to this interface.
 */
interface TtsEngine {

    /**
     * Speak [text] in the given [locale]. Returns when the utterance
     * has finished playing (or failed). [locale] follows BCP-47 —
     * default "uk-UA" for the Ukrainian Familiar voice.
     */
    suspend fun speak(text: String, locale: String = "uk-UA"): Result<Unit>

    /** Cancel any in-flight utterance. */
    fun stop()

    /**
     * ЖИВА РОЗМОВА — стишити, не обриваючи (1f = на повну).
     *
     * Перехоплення слова стало двоступеневим: спершу PHANTOM просідає в
     * гучності за десятки мілісекунд — це поступка, яку людина чує одразу, —
     * і лише підтверджене розпізнавачем слово вбиває репліку. Хибне
     * спрацювання коштує коротенького просідання, а не цілої фрази.
     *
     * Двигуни без власного PCM (системний TTS) не вміють цього — і кажуть
     * правду мовчанням, а не вдають, що стишились.
     */
    fun setGain(gain: Float) = Unit

    /**
     * True if the engine has finished initialising and can speak now.
     */
    suspend fun isReady(): Boolean

    /** Friendly identifier surfaced in Settings ("Android System · uk-UA"). */
    val displayName: String
}
