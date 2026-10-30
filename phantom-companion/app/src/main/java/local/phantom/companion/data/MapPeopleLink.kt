package local.phantom.companion.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import local.phantom.companion.core.data.geocoder.ForgeGeocoder
import local.phantom.companion.core.data.geocoder.ForgeGeocoderStore
import local.phantom.companion.core.data.peers.MyWorldSummary
import local.phantom.companion.core.data.peers.PeerSelfShow
import local.phantom.companion.core.net.peer.PeerFrames
import local.phantom.companion.core.net.peer.PeerPayload
import local.phantom.companion.feature.stream.ui.map.peers.MapPeoplePort
import local.phantom.companion.share.ShareInbox

/**
 * Мапа ↔ люди на цьому телефоні: привид, «помахати», «покликати», «я на місці»
 * і «мій світ». Будять його лише виміри, що й так приходять із чипа.
 */
class MapPeopleLink(private val container: AppContainer) : MapPeoplePort {

    private val hub get() = container.peopleWhere
    private val goals = MutableStateFlow<List<ArrivalWatch.Goal>>(emptyList())

    val recorder = MyWorldRecorder(
        file = MyWorldEncryptedFile(container.applicationContext),
        scope = container.scope,
        cityOf = { lat, lon -> geocoders().firstNotNullOfOrNull { runCatching { it.reverse(lat, lon)?.city }.getOrNull() } },
    )

    private val opened = HashMap<String, ForgeGeocoder>()

    // Лише пакет кузні на цьому телефоні: місто «мого світу» не питається в мережі.
    @Synchronized
    private fun geocoders(): List<ForgeGeocoder> =
        ForgeGeocoderStore(container.applicationContext).installedPaths().mapNotNull { path ->
            opened[path] ?: ForgeGeocoder.open(path)?.also { opened[path] = it }
        }

    override val self: StateFlow<PeerSelfShow> get() = hub.self
    override val hidden: StateFlow<Map<String, Long>> get() = hub.hidden
    override val world: StateFlow<MyWorldSummary> get() = recorder.summary

    fun attach() = recorder.attach()

    fun onFix() {
        val f = container.locationProvider.current()?.takeIf { it.isNavigationGrade && !it.dead && it.locatesTheDevice } ?: return
        recorder.onFix(f.lat, f.lon)
        val held = goals.value
        if (held.isEmpty()) return
        val step = ArrivalWatch.onFix(held, f.lat, f.lon, f.accuracyM, System.currentTimeMillis())
        goals.value = step.goals
        step.fire.forEach { g -> g.peerIds.forEach { say(it, g.words) } }
    }

    override fun hide(peerId: String?, untilMs: Long) = hub.hide(peerId, untilMs)

    override fun shareLowBattery(on: Boolean) = hub.shareLowBattery(on)

    override fun say(peerId: String, words: String): Boolean =
        (container.mesh.sendTyped(peerId, words) as? PeerFrames.Typed.Sent)?.messages?.isNotEmpty() == true

    override fun sendPlace(peerId: String, lat: Double, lon: Double, words: String): Boolean {
        container.mesh.send(peerId, PeerPayload.encodePlace(lat, lon)) ?: return false
        say(peerId, words)
        return true
    }

    override fun sendPlaceToChat(lat: Double, lon: Double, name: String): Boolean =
        ShareInbox.offerPlace(container.applicationContext, lat, lon, name)

    override fun tellOnArrival(peerIds: List<String>, lat: Double, lon: Double, words: String) {
        goals.value = goals.value + ArrivalWatch.Goal(lat, lon, peerIds, words, System.currentTimeMillis())
    }

    override fun forgetWorld() = recorder.forget()
}

private val mapPeopleLock = Any()
@Volatile private var mapPeopleOne: MapPeopleLink? = null

/** Один на процес, як і сам контейнер; живе поза ним, щоб контейнер лишався незмінним. */
internal val AppContainer.mapPeople: MapPeopleLink
    get() = mapPeopleOne ?: synchronized(mapPeopleLock) {
        mapPeopleOne ?: MapPeopleLink(this).also {
            it.attach()
            mapPeopleOne = it
        }
    }
