package local.phantom.companion.feature.stream.ui.map

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import local.phantom.companion.core.data.places.DrawnLine
import local.phantom.companion.core.data.spatial.GeoPoint
import local.phantom.companion.feature.stream.ui.map.atlas.SceneMode
import org.maplibre.android.maps.MapLibreMap

/**
 * NAV-0 «Сцени» жили тут як `MapScene` (CALM/EXPLORE/GUIDE) з власною
 * деривацією зі СТАНУ ЕКРАНА: що відкрито, що обрано, що розгорнуто.
 *
 * ATLAS-2 їх зняв. Не тому, що ідея була погана — вона лишилась і виросла в
 * [SceneMode], — а тому, що поруч зʼявилась ДРУГА сцена, з іншої осі: Лінза
 * рахує густоту з того, що людина РОБИТЬ. Дві сцени з перекритих сигналів
 * означають дві правди, і розходились вони саме там, де ATLAS-2 важить:
 * пів хвилини без дотику з відкритою панеллю давали спокій одній і огляд
 * другій. Тепер вісь одна, виводить її `rememberSceneMode` у полотні (там
 * живе Лінза), а хром читає ту саму відповідь із [MapUiState.sceneMode].
 */

/**
 * ATLAS-2 — режими, вхід у які варто оголосити словом.
 *
 * Двоє, і обидва змінюють екран так, що людина інакше здивується: за кермом з
 * мапи зникають вивіски, під сиреною — усе, крім стійкого. Решта переходів
 * мовчазні: спокій і огляд нічого не забирають, тож і сказати їм нема чого.
 */
internal val ANNOUNCED_MODES = setOf(SceneMode.DRIVE, SceneMode.THREAT)

/**
 * Памʼять про вже сказане — той самий механізм, що в чипа довіри
 * ([TrustVoiceState]), і свідомо ті самі числа: одна дисципліна гучності на
 * весь хром, а не дві схожі.
 *
 * Двічі за один вхід оголошення не буває структурно (вікно відкриває лише
 * ЗМІНА режиму), а [REANNOUNCE_FLOOR_MS] закриває другий випадок: режим, що
 * мигає туди-сюди на межі витримки, не має права клацати плашкою щохвилини.
 * Перша побачена сцена не оголошується — те, що мапу відкрили, не новина.
 */
internal data class SceneAnnounceState(
    val mode: SceneMode = SceneMode.EXPLORE,
    val untilMs: Long = 0L,
    val lastMs: Map<SceneMode, Long> = emptyMap(),
    val seeded: Boolean = false,
) {
    fun announcing(now: Long): Boolean = now < untilMs

    fun observe(next: SceneMode, now: Long): SceneAnnounceState {
        if (seeded && next == mode) return this
        val last = lastMs[next]
        val fire = seeded && next in ANNOUNCED_MODES &&
            (last == null || now - last >= REANNOUNCE_FLOOR_MS)
        return SceneAnnounceState(
            mode = next,
            untilMs = if (fire) now + ANNOUNCE_MS else 0L,
            lastMs = if (fire) lastMs + (next to now) else lastMs,
            seeded = true,
        )
    }
}

/**
 * Shared mutable UI state of the map surface. One holder instead of ~20
 * loose `remember { mutableStateOf(...) }` locals so the canvas (which
 * writes selections from tap handlers), the chrome (which renders and
 * clears them) and the sheet host (which presents them) all see the same
 * truth without threading two dozen callbacks through each other.
 */
@Stable
internal class MapUiState {
    // canvas
    var mapRef by mutableStateOf<MapLibreMap?>(null)

    /**
     * ATLAS-0b (D4) — the one camera owner, published here because the chrome,
     * the sheets and the search results all need to STATE an intent and this
     * holder is already where they look for the map. Null until the map
     * arrives; a submit into nothing is the same no-op the old `mapRef?.` was.
     */
    var camera by mutableStateOf<local.phantom.companion.feature.stream.ui.map.atlas.CameraService?>(null)

    var cameraInitialized by mutableStateOf(false)

    /** The operator's INTENT to be followed. Whether the camera is actually
     *  writing is the service's business; this is the record the orb reads. */
    var followMode by mutableStateOf(false)

    // NAV-2 / APPLE-LOCK — is a drive holding followMode armed? The pose
    // itself is written by the ONE per-frame director in MapCanvas (guidance
    // no longer runs a camera of its own); this flag only tells
    // GuidanceCameraEffect whether the drive that just ended was the thing
    // holding the camera, so it can hand it back.
    var guidanceChase by mutableStateOf(false)

    /** Uptime ms of the last touch on the map. Camera authority is no longer
     *  asked here — `CameraService.gestureFresh()` owns that — but the chrome
     *  still reads it: a hand on the map collapses the command sheet. */
    var cameraGestureAtMs by mutableStateOf(0L)

    /** Pending re-ask of the idle pitch rule; cancelled by the next idle. */
    /** Last idle that wanted the pitch rule re-evaluated. */
    var pitchAskedAtMs by mutableStateOf(0L)

    /** MAP-10 — the DEVICE's azimuth (rotation-vector sensor); drives compass-follow. */
    var bearing by mutableStateOf(0f)

    /** ATLAS-2.5 — who owns the puck right now. Written by the display pump on
     *  CHANGE only (a per-frame write here would recompose the chrome 30×/s),
     *  read by the trust chip so the word matches what is actually drawn. */
    var coastBadge by mutableStateOf(local.phantom.companion.core.nav.CoastBadge.LIVE)

    /** ATLAS-2 — режим сцени. Виводить його полотно (там живе Лінза), читає
     *  хром: гучність довіри, оголошення переходу, стишення в спокої. Пишеться
     *  лише на ЗМІНУ — режим міняється рази за поїздку, не рази за секунду. */
    var sceneMode by mutableStateOf(SceneMode.EXPLORE)

    /** Наскільки пристрій вірить власному компасу — звідси ширина конуса.
     *  MEDIUM — не «невідомо»: платформа має право не озватися жодного разу,
     *  і це рівно той сектор, який мапа малювала завжди. */
    var compassFidelity by mutableStateOf(CompassFidelity.MEDIUM)

    /** W-9 — the MAP's own bearing. A compass that cannot say this is a costume.
     *  Без боксингу: сюди пишуть щокадру повороту, і кожен Float у коробці —
     *  це сміття, яке потім збирає той самий головний потік. */
    /**
     * Під цим виглядом землі нема — і вона не завантажена, а не «її не існує».
     *
     * Штрихування, що це малює, існувало давно, але воно **без підпису**:
     * візерунок без жодного слова. Людина за кордоном, яка бачить порожнє
     * тло в діагональну смужку, читає це як «застосунок зламався», а не як
     * «я не зберіг цей край наперед». Речення, що пояснює різницю, лежало в
     * ресурсах написаним і не викликаним нізвідки.
     */
    var groundNotSavedHere by mutableStateOf(false)

    var basemapStyleFailed by mutableStateOf(false)

    /** ОФЛАЙН-ОСНОВА — оператор за межами внесеного файла тайлів: та сама
     *  порожнеча, що й [groundNotSavedHere], інше джерело. */
    var offlineBasemapOutOfBounds by mutableStateOf(false)

    var mapBearing by mutableFloatStateOf(0f)
    var viewportCenter by mutableStateOf<Triple<Double, Double, Double>?>(null)
    var viewportLocality by mutableStateOf<String?>(null)
    var basemapOverride by mutableStateOf<MapBasemap?>(null)

    /** ux-map-design.md §5.1 — what fraction of the on-screen crystal-city
     *  buildings is measured/derived/guessed/remembered. Null (not EMPTY)
     *  while no register-bearing data is loaded for this viewport at all —
     *  the readout must disappear then, not claim a 0/0/0/0 verdict. */
    var registerComposition by mutableStateOf<RegisterComposition?>(null)

    /** §5.2 — the active route's worst KNOWN register, for the guidance
     *  chip. Null when the route carries no register data yet, which is
     *  the common case — see RouteRegisterProfile's own doc. */
    var routeWorstRegister by mutableStateOf<GroundRegister?>(null)

    // live overlays
    var alertStates by mutableStateOf<Map<String, AlertState>>(emptyMap())
    var alertsVisible by mutableStateOf(true)
    var radarVisible by mutableStateOf(false)
    var radarOverlay by mutableStateOf<RadarOverlay?>(null)
    /** LEVIATHAN-4 — the animation reel (past→nowcast, oldest first). */
    var radarFrames by mutableStateOf<List<RadarOverlay>>(emptyList())

    // selections (each implies EXPLORE; presented by the sheet host)
    // NAV-4 — a tapped place opens its dossier (POI pin, base-style icon,
    // long-press «що тут?» all land on this one target).
    private val heldPlace = mutableStateOf<local.phantom.companion.feature.stream.ui.map.dossier.DossierTarget?>(null)
    // Повторний дотик у те саме місце тримає відкрите досьє: новий обʼєкт перезапускав завантаження до скелета.
    var dossierTarget: local.phantom.companion.feature.stream.ui.map.dossier.DossierTarget?
        get() = heldPlace.value
        set(value) { heldPlace.value = PlaceTap.next(heldPlace.value, value) }
    var selectedLayerMarker by mutableStateOf<TappedLayerMarker?>(null)

    /** СИМБІОЗ — людина під пальцем. Копія фактів на мить тапу, не посилання. */
    var selectedPeer by mutableStateOf<local.phantom.companion.feature.stream.ui.map.peers.TappedPeer?>(null)
    /** Кілька друзів в одному місці: nodeId кожного, аркуш зі списком. */
    var peerGroup by mutableStateOf<List<String>?>(null)
    var myWorldOpen by mutableStateOf(false)
    var myWorldOnMap by mutableStateOf(false)
    var meeting by mutableStateOf<local.phantom.companion.feature.stream.ui.map.peers.Meeting?>(null)
    var meetPickOpen by mutableStateOf(false)
    var meetOpen by mutableStateOf(false)
    var whoSeesOpen by mutableStateOf(false)

    /** Чужий маршрут під пальцем — чий він і що з ним можна зробити. */
    var selectedPeerRoute by
        mutableStateOf<local.phantom.companion.core.net.peer.PeerLiveShare.RouteMark?>(null)
    var selectedThreatOblast by mutableStateOf<String?>(null)
    /** СИРЕНА — the «Небо» alarm command sheet (opened from the alert pill). */
    var alarmSheetOpen by mutableStateOf(false)
    /** ТРАНСПОРТНИЙ ХАБ — departures radar, routes explorer & metro network. */
    var transitHubOpen by mutableStateOf(false)
    var droppedPoint by mutableStateOf<Pair<Double, Double>?>(null)
    var droppedAddress by mutableStateOf<String?>(null)

    // «Звідси маршрут»: обраний початок чекає, доки палець назве кінець.
    // Наступна дія «маршрут сюди» бере ЦЕЙ початок замість власної позиції.
    var routeOriginPick by mutableStateOf<RouteOriginPick?>(null)

    // Незаданий якір, який оператор саме ставить: спершу аркуш із двома
    // шляхами, тоді — земля під пальцем.
    var anchorPick by mutableStateOf<AnchorPick?>(null)

    // Ламана, яку саме малюють дотиками. Не null — мапа збирає вершини і не
    // відкриває нічого іншого по дотику.
    var drawPick by mutableStateOf<DrawPick?>(null)

    /** Ламана, яку домалювали й тепер називають; без імені вона не зберігається. */
    var drawNaming by mutableStateOf<List<GeoPoint>?>(null)

    /** Збережена лінія, по якій влучив дотик, — її картка. */
    var selectedLine by mutableStateOf<DrawnLine?>(null)

    // A tapped SAVED place. It gets its own light card — route / rename /
    // delete / share — instead of the heavy dossier a random POI opens: the
    // operator already knows what his own star is, he wants to act on it.
    var selectedSavedPlace by mutableStateOf<local.phantom.companion.core.data.poi.PoiEntity?>(null)

    // command pill + search
    var searchOpen by mutableStateOf(false)
    var searchQuery by mutableStateOf("")
    var searchResults by mutableStateOf<List<NominatimResult>>(emptyList())
    var searchInFlight by mutableStateOf(false)
    var searchSubmitted by mutableStateOf(false)
    var searchPin by mutableStateOf<Pair<Double, Double>?>(null)
    /** Offline gazetteer fallback, shown only when the network came back empty. */
    var searchOfflineHits by mutableStateOf<List<local.phantom.companion.core.data.poi.PoiHit>>(emptyList())

    // chrome
    var toolsExpanded by mutableStateOf(false)
    /** Аркуш «Поруч» — головний аркуш мапи; шари тепер за малою кнопкою. */
    var aroundOpen by mutableStateOf(false)
    /** Місце з досьє, що чекає на людину: «У чат». */
    var placeSend by mutableStateOf<local.phantom.companion.feature.stream.ui.map.dossier.DossierTarget?>(null)

    // NAV-2 — step-by-step turn itinerary list
    var maneuverListOpen by mutableStateOf(false)

    // INDOOR — Building indoor floor plan & level switcher
    var indoorPlan by mutableStateOf<local.phantom.companion.core.geo.indoor.BuildingIndoorPlan?>(null)
    var selectedIndoorLevel by mutableStateOf(0)

    var carCockpitOpen by mutableStateOf(false)

    var offlineOpen by mutableStateOf(false)
    var offlineSelectedPack by mutableStateOf<Long?>(null)
    var offlinePacksGeoJson by mutableStateOf(EMPTY_FC)

    /** Телефон у кріпленні на лобовому склі: замість мапи — три факти.
     *  Поки true, MapCanvas зупинено (`active`) — під непрозорим екраном
     *  MapLibre малював би цілу поїздку в нікуди, з увімкненим keepScreenOn. */
    var mountMode by mutableStateOf(false)

    /** Closes the pill and drops transient search chrome (keeps the pin). */
    fun collapseSearch() {
        searchOpen = false
        searchResults = emptyList()
        searchOfflineHits = emptyList()
        searchSubmitted = false
    }

}

/** Початок майбутнього маршруту, обраний пальцем. [label] — лише для ока в пілюлі. */
internal data class RouteOriginPick(val lat: Double, val lon: Double, val label: String?)

/**
 * Якір (дім чи робота), який зараз ставлять.
 *
 * Доти дотик по «Задати дім» мовчки писав центр вікна — під шторкою пошуку,
 * що займає пів екрана, тобто наосліп і без жодного слова про те, що сталося.
 * Тепер шлях названо вголос: [placing] хибне — аркуш питає, ЯК ставити;
 * істинне — аркуш відпустив, і пілюля чекає на довгий дотик по землі.
 *
 * [label] локалізований і йде в сховище як імʼя рядка; впізнає якір саме
 * [category], тож англомовний «Home» лишається домом.
 */
internal data class AnchorPick(
    val category: String,
    val label: String,
    val placing: Boolean = false,
)

/**
 * Ламана в роботі: вершини лягають дотиками, доки режим триває.
 *
 * [editingId] не null — правлять уже збережену лінію, і «Готово» перепише
 * саме її. Той самий режим служить обом шляхам: окремий «режим правки» був
 * би другою реалізацією одного жесту.
 */
internal data class DrawPick(
    val points: List<GeoPoint> = emptyList(),
    val editingId: String? = null,
    /**
     * Обрана вершина — лише в правці. Під час першого малювання дотик завжди
     * КЛАДЕ вершину: там палець домальовує, а не переставляє вже покладене.
     */
    val selected: Int? = null,
) {
    /** Обрана, доки вона в межах списку: видалення зсуває решту індексів. */
    val selectedIndex: Int? get() = selected?.takeIf { editingId != null && it in points.indices }
}
