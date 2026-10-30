package local.phantom.companion.core.voice.turn

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ЖИВА РОЗМОВА — двоступеневий кінець репліки, оголошений процесу.
 *
 * Той самий підхід, що в [local.phantom.companion.core.voice.SpeechActivityHub]:
 * канал слуху живе на рівні застосунку й не має уявлення, який ViewModel
 * зараз змонтований. Розмова не має вмирати від того, що людина перейшла на
 * мапу.
 *
 * - [speculative] — фраза, найпевніше, закінчена: модель можна запускати
 *   НАПЕРЕД. Говорити ще ні. Транскрипт може ще змінитись; остаточний
 *   приходить, як і раніше, через `VoiceController.sttResults`.
 * - [patient] — довга тиша після обірваної фрази. Показати, що досі слухаємо,
 *   і НЕ говорити.
 * - [bargedIn] — людина справді перебила. Той, хто веде репліку, мусить
 *   запамʼятати, ДОКИ вона була почута, інакше наступний хід моделі
 *   посилатиметься на слова, яких ніхто не чув.
 */
object TurnHub {

    private val _speculative = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val speculative: SharedFlow<String> = _speculative.asSharedFlow()

    private val _bargedIn = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val bargedIn: SharedFlow<Unit> = _bargedIn.asSharedFlow()

    private val _patient = MutableStateFlow(false)
    val patient: StateFlow<Boolean> = _patient.asStateFlow()

    private val _askedQuestion = MutableStateFlow(false)

    /**
     * PHANTOM щойно поставив запитання. Людина відповідає на нього майже без
     * паузи, тож чекати на неї стільки ж, скільки на нову думку, — це і є те
     * відчуття тупості, яке ми прибираємо.
     */
    val askedQuestion: StateFlow<Boolean> = _askedQuestion.asStateFlow()

    fun setAskedQuestion(value: Boolean) {
        _askedQuestion.value = value
    }

    fun speculate(partial: String) {
        if (partial.isNotBlank()) _speculative.tryEmit(partial.trim())
    }

    fun bargeIn() {
        _bargedIn.tryEmit(Unit)
    }

    fun setPatient(value: Boolean) {
        _patient.value = value
    }

    fun reset() {
        _patient.value = false
    }
}
