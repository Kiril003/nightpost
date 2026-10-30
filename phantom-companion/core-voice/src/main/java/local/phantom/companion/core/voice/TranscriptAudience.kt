package local.phantom.companion.core.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Чи є кому діяти за розпізнаною реплікою.
 *
 * Мікрофон у живому діалозі тримає служба переднього плану — вона для того й
 * існує, щоб слух пережив згортання застосунку. А розпізнану репліку в хід
 * перетворює рівно одне місце: збирач `sttResults` у StreamViewModel, який
 * живе у ViewModelStore запису навігації. Змахни застосунок із нещодавніх —
 * служба лишається, мікрофон лишається відкритим, Vosk далі розпізнає, а
 * `sttResults` віддає слова в SharedFlow без жодного передплатника і без
 * replay. Слова зникають без сліду, і шторка при цьому пише «слухаю».
 *
 * Цей лічильник не лагодить архітектуру — він не дає їй брехати. Поки нікого
 * не приєднано, служба каже правду, а не «слухаю».
 */
object TranscriptAudience {

    private val _attached = MutableStateFlow(0)

    /** Скільки збирачів справді перетворять транскрипт на хід. */
    val attached: StateFlow<Int> = _attached.asStateFlow()

    private val _unheard = MutableStateFlow(0)

    /** Скільки реплік розпізналось у порожнечу від старту процесу. */
    val unheard: StateFlow<Int> = _unheard.asStateFlow()

    fun attach() {
        _attached.value = _attached.value + 1
    }

    fun detach() {
        _attached.value = (_attached.value - 1).coerceAtLeast(0)
    }

    fun anyone(): Boolean = _attached.value > 0

    fun noteUnheard() {
        _unheard.value = _unheard.value + 1
    }

    /** Лише для тестів: процес-глобальний стан не має протікати між ними. */
    fun reset() {
        _attached.value = 0
        _unheard.value = 0
    }
}
