package local.phantom.companion.core.voice.supertonic

/**
 * One segment of text → one PCM buffer. Implemented by
 * [OrtSupertonicSynth] (real ONNX Runtime inference); tests substitute
 * a fake so [local.phantom.companion.core.voice.route.SupertonicRoute]'s
 * pipelining is verifiable on the JVM.
 */
interface SupertonicSynth {

    /** Output sample rate, valid after [load]. */
    val sampleRate: Int

    /** True once the model files are loaded and sessions are live. */
    val isLoaded: Boolean

    /**
     * Чи Є З ЧОГО завантажитись — без завантаження.
     *
     * `SupertonicRoute.isReady()` раніше відповідало на це викликом [load],
     * а [load] створює чотири сесії ONNX над ~398 МБ ваг. `RoutedTts`
     * питає готовність на СТАРТІ, тож питання коштувало 400 МБ нативної
     * купи ще до першого екрана. Питання мусить бути дешевим.
     *
     * Умовчання консервативне: хто не вміє відповісти дешево, вважається
     * готовим лише вже завантаженим.
     */
    val canLoad: Boolean get() = isLoaded

    /** Load sessions + assets from disk. Idempotent. Throws on bad assets. */
    fun load()

    /**
     * Synthesize one text segment. Blocking, CPU-heavy (hundreds of ms).
     * Returns mono float PCM at [sampleRate].
     */
    fun synthesize(text: String, lang: String): FloatArray

    /** Free sessions and native memory. */
    fun close()
}
