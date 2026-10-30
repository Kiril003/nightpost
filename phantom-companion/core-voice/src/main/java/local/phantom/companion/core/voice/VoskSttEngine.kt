package local.phantom.companion.core.voice

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Vosk implementation for instant streaming partials.
 *
 * Recognizer і Model — нативні обʼєкти. Тут вони жили без жодного замка:
 * потік запису кликав feed(), а initStt() тим часом кликав release(), і
 * acceptWaveForm лізла у вже звільнену памʼять. Процес падав цілком —
 * SIGSEGV у Recognizer::CleanUp, не виняток, тож жоден catch його не ловив.
 * Двигун тепер боронить себе сам: один замок на всі нативні виклики і
 * прапорець, після якого будь-який виклик стає нічим.
 */
class VoskSttEngine(model: Model) : SttEngine {

    override val displayName: String = "Vosk · UA"

    /** Модель тримаємо полем: як параметр конструктора вона ставала
     *  недосяжною одразу після створення розпізнавача, і збирач сміття міг
     *  забрати нативні дані з-під нього. */
    private val model: Model = model
    private val recognizer = Recognizer(model, 16000f)

    private val lock = Any()
    private var closed = false

    private val _partialResults = MutableSharedFlow<String>(extraBufferCapacity = 16)
    override val partialResults: SharedFlow<String> = _partialResults.asSharedFlow()

    private val _finalResults = MutableSharedFlow<String>(extraBufferCapacity = 16)
    override val finalResults: SharedFlow<String> = _finalResults.asSharedFlow()

    /**
     * Репліка цілком — закриті сегменти плюс те, що звучить зараз. Vosk
     * скидає себе на кожній внутрішній тиші, тож без цього назовні йшов би
     * тільки останній уламок; чому це видно оком, див. [UtteranceText].
     *
     * Накопичення живе ТУТ, під тим самим замком, що й розбір, — а не в
     * колекторі корутини поверх: між `tryEmit` і збором є вікно, і репліка,
     * закрита всередині нього, губила б слова.
     */
    private val utterance = UtteranceText()

    /** Партіал, який уже пішов назовні. Незмінений партіал новин не несе. */
    private var lastPartial: String = ""

    override fun feed(chunk: ByteArray) {
        val emit: Pair<Boolean, String>? = synchronized(lock) {
            if (closed) return
            if (recognizer.acceptWaveForm(chunk, chunk.size)) {
                val text = JSONObject(recognizer.result).optString("text", "")
                utterance.commit(text)
                true to text
            } else {
                val pending = JSONObject(recognizer.partialResult).optString("partial", "")
                val whole = utterance.withPending(pending)
                if (whole == lastPartial) null else {
                    lastPartial = whole
                    false to whole
                }
            }
        }
        val (isFinal, text) = emit ?: return
        if (text.isEmpty()) return
        if (isFinal) _finalResults.tryEmit(text) else _partialResults.tryEmit(text)
    }

    override suspend fun drain(): String? = synchronized(lock) {
        if (closed) return null
        // getFinalResult() віддає незакриту гіпотезу І скидає розпізнавач —
        // саме те, що треба наприкінці репліки.
        val tail = JSONObject(recognizer.finalResult).optString("text", "")
        lastPartial = ""
        utterance.drain(tail)
    }

    override suspend fun transcribe(audio: ByteArray): String? = synchronized(lock) {
        if (closed) return null
        recognizer.reset()
        recognizer.acceptWaveForm(audio, audio.size)
        // finalResult, не result: acceptWaveForm повертає результат лише тоді,
        // коли САМА знайшла кінець фрази, тож `result` після одного подання
        // цілого буфера — або порожньо, або текст до першої внутрішньої тиші.
        // Хвіст фрази просто зникав.
        JSONObject(recognizer.finalResult).optString("text", "")
    }

    override fun reset() {
        synchronized(lock) {
            if (closed) return
            recognizer.reset()
            utterance.reset()
            lastPartial = ""
        }
    }

    override fun release() {
        synchronized(lock) {
            if (closed) return
            closed = true
            recognizer.close()
            model.close()
        }
    }
}
