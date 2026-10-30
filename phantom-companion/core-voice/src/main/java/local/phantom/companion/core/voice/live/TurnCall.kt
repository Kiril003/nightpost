package local.phantom.companion.core.voice.live

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Покроковий дзвінок — без живого голосу (немає токена, немає мережі до Google).
 *
 * Утримав кнопку — говориш (Moonshine на телефоні), відпустив — розум
 * (ПК по LAN або через relay, далі хмара, далі чесне «розуму немає»),
 * відповідь — голосом Supertonic, коли він є, інакше текстом на екрані.
 * Вузький режим ([narrow]) — завжди текстом: голос у текст, відповідь текстом.
 *
 * Стенограму пише сама розмова з PHANTOM (її [brain] кладе обидві репліки
 * в чат) — тут нічого не дублюється.
 */
class TurnCall(
    private val scope: CoroutineScope,
    private val ear: Ear,
    private val brain: suspend (String) -> Answer,
    private val voice: Voice,
    private val narrow: () -> Boolean = { false },
) {
    interface Ear {
        fun start(): Boolean
        suspend fun stop(): String?
    }

    interface Voice {
        suspend fun ready(): Boolean
        suspend fun speak(text: String): Boolean
        fun stop()
    }

    sealed interface Answer {
        data class Said(val text: String) : Answer

        /** Розуму немає або він відмовив — словами, чому. */
        data class None(val why: String) : Answer
    }

    enum class Phase { IDLE, RECORDING, THINKING, SPEAKING, ENDED }

    data class State(
        val phase: Phase = Phase.IDLE,
        val owner: String = "",
        val phantom: String = "",
        /** Відповідь лише текстом — і чому (немає голосу / вузький режим). */
        val textOnly: Boolean = false,
        val note: String = "",
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var job: Job? = null

    fun press() {
        val s = _state.value
        if (s.phase == Phase.ENDED || s.phase == Phase.RECORDING || s.phase == Phase.THINKING) return
        if (s.phase == Phase.SPEAKING) {
            // Перебив: голос гасне, людина говорить далі.
            job?.cancel()
            voice.stop()
        }
        if (!ear.start()) {
            _state.value = s.copy(phase = Phase.IDLE, note = MIC_BUSY)
            return
        }
        _state.value = _state.value.copy(phase = Phase.RECORDING, note = "")
    }

    fun release() {
        if (_state.value.phase != Phase.RECORDING) return
        _state.value = _state.value.copy(phase = Phase.THINKING)
        job = scope.launch {
            try {
                turn()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                set(Phase.IDLE, note = "збій: ${e.javaClass.simpleName}")
            }
        }
    }

    fun end() {
        job?.cancel()
        voice.stop()
        _state.value = _state.value.copy(phase = Phase.ENDED)
    }

    private suspend fun turn() {
        val heard = ear.stop()?.trim().orEmpty()
        if (heard.isEmpty()) {
            set(Phase.IDLE, note = NOT_HEARD)
            return
        }
        _state.value = _state.value.copy(owner = heard, phantom = "")
        when (val a = brain(heard)) {
            is Answer.None -> set(Phase.IDLE, phantom = a.why, note = "")
            is Answer.Said -> {
                val narrowNow = narrow()
                val canSpeak = !narrowNow && voice.ready()
                if (!canSpeak) {
                    set(Phase.IDLE, phantom = a.text, textOnly = true, note = if (narrowNow) NARROW else NO_VOICE)
                    return
                }
                set(Phase.SPEAKING, phantom = a.text, textOnly = false)
                val spoke = voice.speak(a.text)
                set(Phase.IDLE, textOnly = !spoke, note = if (spoke) "" else NO_VOICE)
            }
        }
    }

    private fun set(
        phase: Phase,
        phantom: String? = null,
        textOnly: Boolean? = null,
        note: String? = null,
    ) {
        if (_state.value.phase == Phase.ENDED) return
        val s = _state.value
        _state.value = s.copy(
            phase = phase,
            phantom = phantom ?: s.phantom,
            textOnly = textOnly ?: s.textOnly,
            note = note ?: s.note,
        )
    }

    companion object {
        const val NOT_HEARD = "Не розчув — утримайте кнопку й скажіть ще раз."
        const val NO_VOICE = "Голосу на телефоні немає — відповідь текстом."
        const val NARROW = "Вузький канал — відповідь текстом."
        const val MIC_BUSY = "Мікрофон зайнятий іншим застосунком."
    }
}
