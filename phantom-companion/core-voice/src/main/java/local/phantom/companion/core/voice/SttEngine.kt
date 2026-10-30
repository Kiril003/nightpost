package local.phantom.companion.core.voice

import kotlinx.coroutines.flow.Flow

/**
 * Чого слух не зміг, — видом, а не реченням: слова добирає той шар, що знає
 * мову людини. Двигун мови інтерфейсу не знає і знати не мусить.
 */
enum class SttNotice {
    /** Репліку розібрано частково — щось із неї загублено. */
    PartOfUtteranceLost,

    /** З репліки не вийшло жодного слова. */
    NothingRecognised,
}

/**
 * Common interface for Speech-to-Text engines.
 */
interface SttEngine {

    val displayName: String

    /**
     * Feeds an audio chunk into the engine.
     * Use for streaming engines like Vosk.
     */
    fun feed(chunk: ByteArray)

    /**
     * Transcribes a full audio segment.
     * Use for non-streaming engines like Moonshine.
     */
    suspend fun transcribe(audio: ByteArray): String?

    /**
     * Flow of partial transcription results.
     */
    val partialResults: Flow<String>

    /**
     * Flow of final transcription results.
     */
    val finalResults: Flow<String>

    /**
     * ЖИВА РОЗМОВА — забрати те, що двигун уже розібрав, і закрити репліку.
     *
     * Потоковий розпізнавач тримає гіпотезу в собі; доти єдиним способом
     * отримати «остаточний» текст було переслухати весь буфер із нуля —
     * ціна, лінійна від довжини репліки, на кожному ході. Злив коштує нуль,
     * бо роботу вже зроблено, поки людина говорила.
     *
     * `null` — двигун не потоковий; той, хто питає, повертається до
     * [transcribe] по всьому буферу.
     */
    suspend fun drain(): String? = null

    /**
     * Те, що двигун НЕ зміг розібрати, — словами, а не тишею.
     *
     * Порожньо за замовчуванням: двигун, якому нема про що попередити,
     * нічого й не каже. Але двигун, який ЧАСТИНУ репліки втратив, мусить
     * сказати це вголос — інакше людина бачить коротший текст і не має
     * способу здогадатись, що решту зʼїли.
     */
    val notices: Flow<SttNotice> get() = kotlinx.coroutines.flow.emptyFlow()

    /**
     * Resets the engine state.
     */
    fun reset()

    /**
     * Releases resources.
     */
    fun release()
}
