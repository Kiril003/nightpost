package local.phantom.companion.feature.stream.ui.map.peers

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import local.phantom.companion.core.data.peers.MyWorldSummary
import local.phantom.companion.core.data.peers.PeerSelfShow

/**
 * Що мапа може зробити з людьми, крім показу: сховатись, помахати, покликати,
 * зустрітись. Слова приходять з мапи — вона знає мову людини; дріт — з месенджера.
 * Результат дії — чи лист пішов у чергу; «не пішов» мапа каже словом.
 */
interface MapPeoplePort {
    val self: StateFlow<PeerSelfShow>
    val hidden: StateFlow<Map<String, Long>>
    val world: StateFlow<MyWorldSummary>

    /** [peerId] null — від усіх; [untilMs] нуль — повернутися. */
    fun hide(peerId: String?, untilMs: Long)
    fun shareLowBattery(on: Boolean)
    fun say(peerId: String, words: String): Boolean

    /** Точка в розмову — карткою місця, і слова поруч. */
    fun sendPlace(peerId: String, lat: Double, lon: Double, words: String): Boolean

    /** Місце в аркуш розмов месенджера (люди, групи, «Збережене»); false — аркуша немає. */
    fun sendPlaceToChat(lat: Double, lon: Double, name: String): Boolean = false

    /** Коли я доїду до [lat],[lon], кожен із [peerIds] отримає [words] сам, без дотику. */
    fun tellOnArrival(peerIds: List<String>, lat: Double, lon: Double, words: String)
    fun forgetWorld()

    object None : MapPeoplePort {
        override val self = MutableStateFlow(PeerSelfShow())
        override val hidden = MutableStateFlow<Map<String, Long>>(emptyMap())
        override val world = MutableStateFlow(MyWorldSummary())
        override fun hide(peerId: String?, untilMs: Long) = Unit
        override fun shareLowBattery(on: Boolean) = Unit
        override fun say(peerId: String, words: String) = false
        override fun sendPlace(peerId: String, lat: Double, lon: Double, words: String) = false
        override fun tellOnArrival(peerIds: List<String>, lat: Double, lon: Double, words: String) = Unit
        override fun forgetWorld() = Unit
    }
}
