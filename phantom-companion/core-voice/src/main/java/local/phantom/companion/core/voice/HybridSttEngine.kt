package local.phantom.companion.core.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/**
 * Orchestrates Vosk for instant streaming partials and closes an utterance
 * by *draining* the running recognizer.
 *
 * ## Чому злив, а не переслуховування
 *
 * Раніше кінець репліки означав `transcribe(увесь буфер)` — розпізнавач
 * слухав усе сказане ще раз, з нуля, синхронно, поки людина чекала. Ціна
 * лінійна від довжини репліки і платиться на кожному ході, при тому що
 * Vosk уже розібрав ці самі слова, поки вони звучали.
 *
 * Тепер закриті сегменти накопичуються по ходу, а наприкінці забирається
 * незакритий хвіст ([SttEngine.drain]). Транскрипт уже в руках у мить,
 * коли репліка закривається, — і саме це робить розмову живою.
 *
 * Точніший двигун ([accurate], нині Moonshine) лишається доступним через
 * [transcribe], але поза гарячим шляхом: тримати його попереду потокового
 * означало платити затримкою на кожному ході за те, що Vosk уже розібрав.
 *
 * 12.09: слот звався `whisper` — і саме так мертвий рушій виглядав живим
 * місцем у конструкторі. Імʼя тепер каже роль, а не зниклого мешканця.
 */
class HybridSttEngine(
    private val vosk: SttEngine,
    private val accurate: SttEngine? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) : SttEngine {

    override val displayName: String = "Hybrid · Vosk stream"

    private val audioBuffer = ByteArrayOutputStream()

    private val _partialResults = MutableSharedFlow<String>(extraBufferCapacity = 16)
    override val partialResults: SharedFlow<String> = _partialResults.asSharedFlow()

    private val _finalResults = MutableSharedFlow<String>(extraBufferCapacity = 16)
    override val finalResults: SharedFlow<String> = _finalResults.asSharedFlow()

    private val _notices = MutableSharedFlow<SttNotice>(extraBufferCapacity = 8)
    override val notices: SharedFlow<SttNotice> = _notices.asSharedFlow()

    private val jobs = mutableListOf<Job>()

    init {
        jobs += scope.launch {
            vosk.partialResults.collect { _partialResults.emit(it) }
        }
        // Втрачену частину репліки каже той двигун, який її втратив, — але
        // почути це мусить той, хто слухає Hybrid, а не сам двигун.
        jobs += scope.launch { vosk.notices.collect { _notices.emit(it) } }
        accurate?.let { jobs += scope.launch { it.notices.collect { n -> _notices.emit(n) } } }
        // NB: vosk.finalResults is deliberately NOT forwarded. Every consumer
        // of [finalResults] submits an AI turn per emission, so forwarding the
        // silence-detected fragments meant double bubbles and double spend.
        // finalize() stays the single emission point; the fragments themselves
        // are accumulated inside the recognizer, under its own lock.
    }

    override fun feed(chunk: ByteArray) {
        vosk.feed(chunk)
        audioBuffer.write(chunk)
    }

    override suspend fun transcribe(audio: ByteArray): String? {
        return accurate?.transcribe(audio) ?: vosk.transcribe(audio)
    }

    /**
     * Closes the utterance and emits its transcript. Cheap by construction —
     * the recognition already happened while the operator was speaking.
     */
    suspend fun finalize(): String? {
        // Двигун не потоковий (тест-дублер, FakeSttEngine) — чесно
        // переслуховуємо буфер, як раніше.
        val result = vosk.drain() ?: transcribe(audioBuffer.toByteArray())
        if (!result.isNullOrBlank()) _finalResults.emit(result)
        reset()
        return result
    }

    override fun reset() {
        vosk.reset()
        accurate?.reset()
        audioBuffer.reset()
    }

    override fun release() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        vosk.release()
        accurate?.release()
    }
}
