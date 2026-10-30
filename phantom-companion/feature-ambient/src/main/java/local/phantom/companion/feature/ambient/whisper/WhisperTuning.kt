package local.phantom.companion.feature.ambient.whisper

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import local.phantom.companion.core.design.component.WhisperFreq

/**
 * Налаштування стрічки в тому вигляді, в якому їх дістає служба.
 *
 * Сховище налаштувань шифроване (EncryptedSharedPreferences) і живе в :app,
 * тож `WhisperLineService` не може прочитати його сам — ні за іменем файлу,
 * ні за ключем. Це той самий шов, що й [WhisperEventBus], лише зі станом:
 * :app дзеркалить сюди `voicePrefs`, служба читає.
 *
 * Поки шва не було, «частота» писалась у сховище, якого ніхто не читав, а
 * служба крутила зашитий [WhisperFreq.Medium] — три чипи давали один темп,
 * і кожен обіцяв власну кількість секунд на цикл.
 */
object WhisperTuning {

    private val _freq = MutableStateFlow(WhisperFreq.Medium)
    private val _soundEnabled = MutableStateFlow(false)

    val freq: StateFlow<WhisperFreq> = _freq.asStateFlow()
    val soundEnabled: StateFlow<Boolean> = _soundEnabled.asStateFlow()

    /** Порядковий номер зі сховища; поза діапазоном — Medium, а не виняток. */
    fun setFreqOrdinal(ordinal: Int) {
        _freq.value = WhisperFreq.values().getOrElse(ordinal) { WhisperFreq.Medium }
    }

    fun setSoundEnabled(value: Boolean) {
        _soundEnabled.value = value
    }
}
