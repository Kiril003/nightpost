package local.phantom.companion.core.voice.turn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Хто зараз веде голосову розмову. Розпізнану репліку в процесі слухають
 * кілька збирачів; без цього одна фраза в розмові з PHANTOM у месенджері
 * ставала б ще й ходом у чаті «Стрічки» — дві відповіді на одне слово.
 */
object VoiceFloor {

    const val PHANTOM_CHAT = "phantom_chat"

    private val _owner = MutableStateFlow<String?>(null)
    val owner: StateFlow<String?> = _owner.asStateFlow()

    fun claim(who: String) {
        _owner.value = who
    }

    fun release(who: String) {
        _owner.compareAndSet(who, null)
    }

    /** Репліку забрав хтось інший — цьому збирачеві мовчати. */
    fun heldByOther(me: String?): Boolean {
        val o = _owner.value
        return o != null && o != me
    }
}
