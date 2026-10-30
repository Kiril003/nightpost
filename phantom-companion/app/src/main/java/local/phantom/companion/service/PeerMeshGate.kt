package local.phantom.companion.service

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import local.phantom.companion.core.net.peer.PeerIdentity
import local.phantom.companion.core.net.peer.PeerRoster

/**
 * Коли сітці є з ким говорити.
 *
 * [PeerMeshService] тримає MulticastLock, тобто знімає апаратний фільтр
 * Wi-Fi: процесор прокидається на кожен широкомовний кадр у мережі. На
 * спільній точці крила це безперервно. Поки в реєстрі нікого немає, цей
 * заряд купує рівно нічого.
 *
 * Перемикача в налаштуваннях тут немає навмисно: правило продукту — нуль
 * налаштувань, а вимикач, який треба знайти, гірший за сітку, що вмикається
 * сама, коли зʼявляється з ким говорити.
 */
object PeerMeshGate {

    /** Скільки екранів «Люди» зараз на видноті. Лічильник, а не прапорець:
     *  вхід нового екрана може випередити вихід старого. */
    private val open = MutableStateFlow(0)

    val peopleOnScreen: Flow<Boolean> = open.map { it > 0 }.distinctUntilChanged()

    fun peopleEntered() = open.update { it + 1 }

    fun peopleLeft() = open.update { (it - 1).coerceAtLeast(0) }

    private val front = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * Застосунок на видноті. Android 12+ відмовляє службі переднього плану, коли
     * процес розбудила фонова робота, і відмову ніхто не повторював: черга стояла
     * до смерті процесу. Звідси її можна підняти знову.
     */
    fun foregrounded() {
        front.tryEmit(Unit)
    }

    /**
     * Спарований ПК у реєстрі — не співрозмовник: у нього немає ключа обміну,
     * і сітка йому нічого не надішле. Рахуються тільки люди.
     */
    fun shouldRun(roster: List<PeerIdentity>, peopleOnScreen: Boolean): Boolean =
        peopleOnScreen || PeerRoster.people(roster).isNotEmpty()

    /**
     * Тримає службу в згоді з відповіддю [shouldRun]. Перше рішення надходить
     * одразу, тож порожній реєстр на свіжому встановленні глушить і ту службу,
     * яку система підняла сама через START_STICKY.
     */
    suspend fun keepInStep(
        roster: Flow<List<PeerIdentity>>,
        onScreen: Flow<Boolean> = peopleOnScreen,
        foreground: Flow<Unit> = front,
        running: () -> Boolean = { true },
        start: () -> Unit,
        stop: () -> Unit,
    ) {
        val wanted = combine(roster, onScreen) { peers, visible -> shouldRun(peers, visible) }
            .distinctUntilChanged()
        var want = false
        merge(wanted, foreground.map { null }).collect { step ->
            if (step == null) {
                if (want && !running()) start()
            } else {
                want = step
                if (step) start() else stop()
            }
        }
    }
}
