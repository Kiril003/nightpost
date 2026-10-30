package local.phantom.companion.feature.stream.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import local.phantom.companion.core.ai.context.LocationFix
import local.phantom.companion.core.data.evac.ShelterRepository
import local.phantom.companion.core.data.peers.PeerPoint
import local.phantom.companion.core.data.peers.PeerPointsRepository
import local.phantom.companion.core.data.places.DrawnLine
import local.phantom.companion.core.data.places.DrawnLineStore
import local.phantom.companion.core.data.places.SavedPlaceStore
import local.phantom.companion.core.data.poi.PoiEntity
import local.phantom.companion.core.data.poi.PoiRepository
import local.phantom.companion.core.data.spatial.FocusTarget
import local.phantom.companion.core.data.spatial.GeoPoint
import local.phantom.companion.core.data.spatial.SpatialLayer
import local.phantom.companion.core.data.spatial.SpatialLayerRepository
import local.phantom.companion.core.data.transit.TransitCoverage
import local.phantom.companion.core.data.transit.TransitCoverageResolver
import local.phantom.companion.core.data.transit.TransitVehicle
import local.phantom.companion.core.data.vault.VaultStore
import local.phantom.companion.core.geo.sky.ClockSkew
import local.phantom.companion.core.geo.sky.ClockWitness
import local.phantom.companion.core.nav.DeadlineWatch
import local.phantom.companion.core.nav.GuidanceController
import local.phantom.companion.core.nav.NavCommand
import local.phantom.companion.core.nav.NavigationEngine
import local.phantom.companion.core.nav.RoutePlanner
import local.phantom.companion.core.net.map.PlaceDossierClient
import local.phantom.companion.core.net.map.RouteProfile
import local.phantom.companion.core.sensor.nav.GnssIntegrityMap
import local.phantom.companion.core.sensor.nav.KinematicsEngine
import local.phantom.companion.core.sensor.nav.LocationTrust
import local.phantom.companion.core.sensor.nav.RoadWay
import local.phantom.companion.feature.stream.R
import local.phantom.companion.feature.stream.ui.map.dossier.DOSSIER_BRIEF_SYSTEM
import local.phantom.companion.feature.stream.ui.map.dossier.DossierBriefing
import local.phantom.companion.feature.stream.ui.map.dossier.DossierCache
import local.phantom.companion.feature.stream.ui.map.dossier.DossierSources
import local.phantom.companion.feature.stream.ui.map.dossier.DossierTarget
import local.phantom.companion.feature.stream.ui.map.dossier.OverpassDossierSources
import local.phantom.companion.feature.stream.ui.map.dossier.PlaceDossier
import local.phantom.companion.feature.stream.ui.map.dossier.dossierBriefPrompt
import local.phantom.companion.feature.stream.ui.map.dossier.dossierBriefQuestion
import local.phantom.companion.feature.stream.ui.map.dossier.dossierKey
import local.phantom.companion.feature.stream.ui.map.dossier.placeDossierFlow
import local.phantom.companion.feature.stream.ui.map.nav.EwTrail
import local.phantom.companion.feature.stream.ui.map.nav.RoadSnapFix
import local.phantom.companion.feature.stream.ui.map.nav.RoadTruth
import local.phantom.companion.feature.stream.ui.widget.MapPayload

/**
 * The map's ViewModel — a FAÇADE since the 500-line decomposition. Each
 * domain lives in its own coordinator ([MapRouteCoordinator],
 * [EvacuationCoordinator], [MapPoiSearch], [MapWorldLayers],
 * [MapLiveSession]); this class owns their construction order, the shared
 * feeds, and the public API every screen was already written against.
 */
class MapViewModel(
    private val poiRepository: PoiRepository,
    private val spatialLayers: SpatialLayerRepository? = null,
    private val worldSummoner: WorldLayerSummoner? = null,
    private val vaultStore: VaultStore? = null,
    /** Мітки оператора — те саме сховище, що бачить чат через `place.*`. */
    private val savedPlaceStore: SavedPlaceStore? = null,
    /** Намальовані ламані — довга памʼять ліній оператора. */
    private val drawnLineStore: DrawnLineStore? = null,
    val threatBriefRequests: SharedFlow<String>? = null,
    private val shelterRepository: ShelterRepository? = null,
    /** Null = build one over [roadTruth]'s graph (the offline-fallback wiring);
     *  tests inject their own. */
    private val spatialRoutingServiceOverride: SpatialRoutingService? = null,
    // NAV-4 — the dossier fetchers (Overpass detail / Wikipedia / building / stop lines).
    private val dossierClient: PlaceDossierClient = PlaceDossierClient(),
    /**
     * W-1 — the operator's position, read by the ViewModel itself. This used
     * to be a `LaunchedEffect` inside the map's composition, which meant that
     * racking the camera up to the Dialogue starved the guidance session:
     * no fixes, no maneuvers, no off-route replan, no arrival — while
     * [GuidanceState] still cheerfully reported `Guiding`. The drive does not
     * belong to a screen. Null in tests/previews (no pump).
     */
    private val locationSource: (() -> LocationFix?)? = null,
    /**
     * W-1 — offline oblast polygons: NAV-5's threat watch needs them to name a
     * crossing. A *loader*, not an instance: the asset is 708 KB of geojson and
     * the shell used to parse it inside `remember {}` on the UI thread, janking
     * the first frame 100–300 ms on a weak phone (brief 03 defect #1). It is
     * pulled on IO from [init] and attached to the live session when it lands;
     * the watch rebuilds itself then. Null in previews/tests.
     */
    private val oblastShapesLoad: (suspend () -> OblastShapes?)? = null,
    /**
     * Synchronous offline oblast lookup for [MapPoiSearch] — separate from
     * [oblastShapesLoad] above (that one feeds the async threat-crossing
     * watch) because a «поруч» tap needs an answer on the spot, not after an
     * IO round trip. [OblastShapes.load] is a cheap cache read once the boot
     * warm-up has parsed the polygons once; default is «unknown region»,
     * which just turns off the invincibility-point gap message.
     */
    private val oblastAt: (lat: Double, lon: Double) -> String? = { _, _ -> null },
    /** W-1 — nav.start/nav.stop/nav.deadline from PHANTOM's hand, handled off-composition. */
    private val navCommands: MutableSharedFlow<NavCommand>? = null,
    /**
     * HIGHWAY DOMINANCE — the process-scoped navigator, shared with the car.
     * Null only in previews/tests, which fall back to a private engine that is
     * never started. See [engine].
     */
    private val navEngine: NavigationEngine? = null,
    /**
     * ATLAS-3 — the kinematic witness (walk/drive/train/…), straight from
     * PhantomLocator. Null in previews/tests and on devices with no locator.
     */
    private val motionFeed: StateFlow<KinematicsEngine.MotionProfile>? = null,
    /**
     * ATLAS-2 — the road-tile fetch behind [RoadTruth]'s map-matcher:
     * (minLat, minLon, maxLat, maxLon) → highway ways. Null disables
     * matching entirely (previews/tests/offline builds).
     */
    private val roadWaysFetch: (suspend (Double, Double, Double, Double) -> List<RoadWay>?)? = null,
    /**
     * Транспорт із дорожнього пакета — зупинки й колії, які пристрій уже має
     * ОФЛАЙН. Окремо від [roadWaysFetch], бо той ходить у мережу першим і
     * лише потім падає у сховище; тут мережі немає в принципі, і саме це
     * робить шар придатним під РЕБ.
     */
    private val packTransitFetch:
        (suspend (Double, Double, Double, Double) -> PackTransitSlice?)? = null,
    /**
     * ATLAS-2b — the PRIMARY road-snap: a recent fix trace → OSRM `/match`
     * snap on the local geo stack. Overpass is unreachable exactly while
     * driving, so this leads and [roadWaysFetch]'s tile matcher is the
     * fallback. Null in previews/tests/offline builds.
     */
    private val roadSnapMatch: (suspend (List<LocationFix>) -> RoadSnapFix?)? = null,
    /**
     * LEVIATHAN-2 — the engine's live verdict on the sky, for the trust halo
     * and the EW trail. Null in previews/tests.
     */
    private val trustFeed: StateFlow<LocationTrust>? = null,
    /** УКРИТТЯ — building-cover grid fetch for tactical evacuation; null = time-only exposure. */
    private val coverFetch: (suspend (Double, Double, Double, Double) -> local.phantom.companion.core.sensor.nav.CoverGrid?)? = null,
    /**
     * HORIZON II.1 — прошарок «де небо ворожe» НА ВЕСЬ ПРОЦЕС, а не на цей
     * екран. Тут він народжувався, тут і вмирав — і читав його рівно один
     * споживач, евакуаційний маршрут. Тепер його тримає `AppContainer`, а
     * цей екран лишається тим, ким і був: тим, хто його ГОДУЄ вироками
     * довіри з кожного фікса. Null у превʼю й тестах — тоді екран заводить
     * власний, як і раніше.
     */
    sharedGnssIntegrity: GnssIntegrityMap? = null,
    /**
     * СИРЕНА-3 — the process-scoped sky. This ViewModel observes it and never
     * owns it: the operator must be warned in the chat and in his pocket, not
     * only while this screen happens to be composed. Null in previews/tests.
     */
    private val airAlertWatch: AirAlertWatch? = null,
    /**
     * H-02 — where the satellite clock check survives a restart. Null in
     * previews/tests, and on that path the witness still runs for the life of
     * the process; it simply starts every launch with no record, which is the
     * honest reading of «this build persists nothing» and shows the caveat face
     * rather than a clean one it cannot back up.
     */
    private val clockSkewStore: local.phantom.companion.core.geo.sky.ClockSkewStore? = null,
    /**
     * ARCH: PRECISION PIVOT — the app emitted ZERO position diagnostics (a full
     * logcat was 19 lines of framework noise). This sink logs every change of the
     * displayed-position SOURCE (raw / road-snap / guidance / frozen) with the
     * inputs behind the decision. Wired to a debug-gated `Log.i("PhantomNav", …)`
     * in `:app`; null in release / previews / tests, so the pure path stays quiet
     * and unit-testable.
     */
    private val positionLog: ((String) -> Unit)? = null,
    /**
     * ДОСЬЄ ВІДПОВІДАЄ В КАРТЦІ — вхід у ШІ. Раніше сюди не було підʼєднано
     * нічого, тож «Огляд від PHANTOM» міг лише кинути питання в діалог, а
     * відповідь моделі смикала мапний інструмент і жбурляла камеру назад у
     * мапу. Null у превʼю/тестах — картка тоді чесно каже, що ШІ нема.
     */
    private val aiComplete: ((local.phantom.companion.core.ai.AIIntent) -> kotlinx.coroutines.flow.Flow<local.phantom.companion.core.ai.AIChunk>)? = null,
    /**
     * Копія огляду в чат як історія. Саме СИНК, а не «відкрий діалог»:
     * камера лишається на мапі, оператор — теж.
     */
    private val chatEcho: ((question: String, answer: String) -> Unit)? = null,
    private val breadcrumbTracker: local.phantom.companion.core.nav.BreadcrumbTracker? = null,
    private val sentinelFenceEngine: local.phantom.companion.core.nav.SentinelFenceEngine? = null,
    private val surfaceFeedLoader: (() -> local.phantom.companion.core.data.transit.surface.SurfaceFeed?)? = null,
    private val metroFeedLoader: (() -> local.phantom.companion.core.data.transit.metro.MetroFeed?)? = null,
    /**
     * Опублікований розклад міста, коли він уже приїхав і саме на сьогодні.
     * Null — розкладу нема, і досьє показує НАЗВАНУ модель, не вигадані часи.
     */
    private val gtfsScheduleLoader: (() -> local.phantom.companion.core.data.transit.gtfs.GtfsSchedule?)? = null,
    /**
     * ПОЛЬОВА ПОМИЛКА, 23.08 — the body's own verdict on whether it is moving
     * (true/false/null = no evidence), for [StationaryLock]. Null in
     * previews/tests and on devices with no IMU lease.
     */
    private val bodyMoving: (() -> Boolean?)? = null,
    /**
     * СИМБІОЗ — спільна з месенджером поверхня обміну точками людей. Null там,
     * де месенджера немає (превʼю, тести, і весь цей вал: мапний бік приїхав
     * перший). Порожній потік — легітимний стан, а не заглушка.
     */
    private val peerPointsRepository: PeerPointsRepository? = null,
    /**
     * Сеанси «ділюсь положенням» — уже зібрані в речення. Мапа їх лише малює:
     * ані в реєстр, ані в меш вона за ними не ходить.
     */
    liveShareBanners: StateFlow<List<local.phantom.companion.core.net.peer.PeerLiveShare.Banner>>? = null,
    /** Зупинити сеанс просто з плашки. Порожня — превʼю без месенджера. */
    val onStopShare: (String) -> Unit = {},
    /** Кому можна віддати цей маршрут — і чи дійде до кожного. */
    routeShareTargets: StateFlow<List<local.phantom.companion.core.net.peer.PeerLiveShare.Target>>? = null,
    /** Готовий кадр — у меш. Порожня — превʼю без месенджера. */
    /**
     * Лист до людини БУДЬ-ЯКИМ кадром, який склав пристрій.
     *
     * Звався іменем маршруту, поки возив лише маршрут; місце їде тією самою
     * дорогою, і назва, що згадує один рід, стала б другою правдою про те, що
     * тут насправді летить.
     *
     * Свідомо повз [PeerFrames.refuseTyped]: той стереже НАБРАНЕ людиною, а
     * складене пристроєм він відмовив би його ж авторові.
     */
    private val sendPeerLetter: (peerId: String, body: String) -> Unit = { _, _ -> },
    /**
     * Чужі надіслані маршрути — ОДНЕ джерело на весь застосунок.
     *
     * Свідомо потік від скриньки, а не другий прохід по журналу розмови: два
     * читачі одного факту рано чи пізно розходяться, і тоді мапа малює те,
     * чого в розмові вже немає.
     */
    peerRouteMarks: StateFlow<
        List<local.phantom.companion.core.net.peer.PeerLiveShare.RouteMark>,
        >? = null,
    /** Такт мітли скриньки. Порожня — превʼю без месенджера. */
    private val sweepLive: () -> Unit = {},
    whoSeesMe: StateFlow<List<local.phantom.companion.core.data.peers.PeerSeeRow>>? = null,
    val onSetWhoSees: (String, local.phantom.companion.core.data.peers.PeerPrecision?) -> Boolean = { _, _ -> false },
    val onVanish: () -> Unit = {},
    val onWritePeer: (String) -> Unit = {},
    val onAddPerson: () -> Unit = {},
    val people: local.phantom.companion.feature.stream.ui.map.peers.MapPeoplePort =
        local.phantom.companion.feature.stream.ui.map.peers.MapPeoplePort.None,
    private val herePort: local.phantom.companion.feature.stream.ui.map.here.HereTruthPort? = null,
    private val aroundPort: local.phantom.companion.feature.stream.ui.map.around.MapAroundPort =
        local.phantom.companion.feature.stream.ui.map.around.MapAroundPort.None,
) : ViewModel() {

    private val _aroundPlaces =
        MutableStateFlow<local.phantom.companion.feature.stream.ui.map.around.AroundPlaces?>(null)

    /** Місця поруч для аркуша «Поруч»; null — ще не питали. */
    val aroundPlaces: StateFlow<local.phantom.companion.feature.stream.ui.map.around.AroundPlaces?> =
        _aroundPlaces.asStateFlow()

    /** Піни мапи: лише місця Nightpost із того самого запиту «поруч». */
    val venuePins: StateFlow<List<local.phantom.companion.feature.stream.ui.map.around.AroundPlace>> =
        _aroundPlaces.map { local.phantom.companion.feature.stream.ui.map.around.venuePinsOf(it) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private var aroundAsked: Triple<Double, Double, Long>? = null

    /** Питає знову лише коли людина відійшла на [AROUND_MOVE_M] або минуло [AROUND_TTL_MS]. */
    fun loadAround(lat: Double, lon: Double, nowMs: Long = System.currentTimeMillis()) {
        val last = aroundAsked
        if (last != null && nowMs - last.third < AROUND_TTL_MS &&
            local.phantom.companion.feature.stream.ui.map.dossier.haversineMeters(last.first, last.second, lat, lon) < AROUND_MOVE_M
        ) return
        aroundAsked = Triple(lat, lon, nowMs)
        viewModelScope.launch {
            _aroundPlaces.value = runCatching { aroundPort.places(lat, lon) }
                .getOrDefault(local.phantom.companion.feature.stream.ui.map.around.AroundPlaces.Unknown)
        }
    }

    fun rememberTilePlaces(places: List<PoiEntity>) = poi.rememberTilePlaces(places) {
        val last = aroundAsked ?: return@rememberTilePlaces
        aroundAsked = null
        loadAround(last.first, last.second)
    }

    val whoSeesMeRows: StateFlow<List<local.phantom.companion.core.data.peers.PeerSeeRow>> =
        whoSeesMe ?: MutableStateFlow(emptyList())

    /** Порожній потік — легітимний стан: маршруту може не бути жодного. */
    val peerRoutes: StateFlow<
        List<local.phantom.companion.core.net.peer.PeerLiveShare.RouteMark>,
        > = peerRouteMarks ?: MutableStateFlow(emptyList())

    /**
     * Гасить протухле і на склі, і в журналі — мітла скриньки робить обидва.
     * Такт дає мапа: власний годинник у скриньці цокав би при згорнутому екрані.
     */
    fun sweepPeerRoutes() = sweepLive()

    /**
     * «Їхати цим маршрутом» із самої мапи.
     *
     * У план ідуть ТОЧКИ відправника й СВІЙ початок; чужа ламана лишається
     * малюнком. Вона намальована з чужого місця, чужим профілем і в чужу
     * хвилину — поїхати нею означало б рушити звідти, де тебе немає.
     */
    fun takePeerRoute(
        fromLat: Double,
        fromLon: Double,
        route: local.phantom.companion.core.net.peer.PeerLive.Route,
    ) {
        val via = route.waypoints.drop(1)
        if (via.isEmpty()) return
        reorderWaypoints(listOf(fromLat to fromLon) + via)
    }

    val shareTargets: StateFlow<List<local.phantom.companion.core.net.peer.PeerLiveShare.Target>> =
        routeShareTargets ?: MutableStateFlow(emptyList())

    /**
     * Чим скінчився дотик «надіслати маршрут» — реченням, не прапорцем.
     *
     * Досі [shareRoute] віддавав Boolean, і жоден із двох викликачів його не
     * читав: людина тиснула, нічого не відбувалось, і це виглядало як зламаний
     * застосунок. Тепер результату НЕМА КУДИ подітись — він живе тут, і смуга
     * маршруту його малює.
     */
    sealed interface RouteHandoff {
        /** Плану немає — надсилати просто нічого. */
        data object NoRoute : RouteHandoff

        /**
         * План Є, і людина його бачить, але в лист він не влазить.
         *
         * Окремо від [NoRoute] навмисно: обидва стани раніше показували
         * «маршруту ще немає — спершу прокладіть його», і в цьому — єдиному
         * досяжному з чіпа — стані це була неправда. Маршрут на екрані, а
         * продукт радив його прокласти.
         *
         * [tooManyStops] розрізняє причину там, де це дешево: понад
         * `PeerLive.MAX_WAYPOINTS` точок — сказати про точки; решта
         * (координати поза межами, вироджена форма) — загальне речення.
         * Вигадана причина гірша за загальну.
         */
        data class TooBig(val tooManyStops: Boolean) : RouteHandoff

        /** Ключ зник між тим, як склали перелік, і дотиком. */
        data class NoKey(val name: String) : RouteHandoff

        data class Sent(
            val name: String,
            val reach: local.phantom.companion.core.net.peer.PeerLiveShare.Reach,
        ) : RouteHandoff
    }

    private val _routeHandoff = MutableStateFlow<RouteHandoff?>(null)
    val routeHandoff: StateFlow<RouteHandoff?> = _routeHandoff.asStateFlow()
    fun consumeRouteHandoff() { _routeHandoff.value = null }

    /**
     * Віддати ОБРАНОМУ той маршрут, що зараз на склі.
     *
     * Їде і ламана, і точки, за якими його клали, — але приймач планує від
     * СВОГО положення саме за точками: чужа геометрія почалась не там, де
     * стоїть той, хто її отримав.
     *
     * Unit навмисне: доки тут стояв Boolean, «нічого не сталось» можна було
     * мовчки впустити, і обидва викликачі його впускали.
     */
    fun shareRoute(peerId: String, nowMs: Long = System.currentTimeMillis()) {
        val target = shareTargets.value.firstOrNull { it.peerId == peerId }
        val name = target?.name.orEmpty()
        val reach = target?.reach
        if (reach == null || reach == local.phantom.companion.core.net.peer.PeerLiveShare.Reach.NO_KEY) {
            _routeHandoff.value = RouteHandoff.NoKey(name)
            return
        }
        val variant = nav.selectedVariant()
        val pins = nav.waypoints.takeIf { it.size >= 2 }
        if (variant == null || pins == null) {
            _routeHandoff.value = RouteHandoff.NoRoute
            return
        }
        val body = local.phantom.companion.core.net.peer.PeerLive.encodeRoute(
            local.phantom.companion.core.net.peer.PeerLive.Route(
                expiresAtMs = nowMs + local.phantom.companion.core.net.peer.PeerLive.ROUTE_LIFE_MS,
                distanceM = variant.distanceMeters,
                durationS = variant.durationSeconds.toLong(),
                profile = variant.profile.name,
                waypoints = pins,
                shape = variant.points,
            ),
        )
        if (body == null) {
            // Маршрут є — не спакувався. Причину називаємо лише ту, яку
            // справді знаємо: кількість точок видно тут-таки.
            _routeHandoff.value = RouteHandoff.TooBig(
                tooManyStops = pins.size > local.phantom.companion.core.net.peer.PeerLive.MAX_WAYPOINTS,
            )
            return
        }
        sendPeerLetter(peerId, body)
        _routeHandoff.value = RouteHandoff.Sent(name, reach)
    }

    /**
     * Надіслати МІСЦЕ людині — дзеркально до [shareRoute].
     *
     * Досі з мапи місце віддавалось лише назовні: три обробники `onShare`
     * складають текст із посиланням на openstreetmap.org і кидають андроїдів
     * `ACTION_SEND`. Тобто «поділитись місцем» несло точку власника в чужі
     * застосунки, а всередину меша — нікуди: [PeerPayload.encodePlace] не мав
     * ЖОДНОГО продуктового виклику, маючи трьох читачів і сім тестів.
     *
     * Ця дія `onShare` не заступає й не чіпає: віддача назовні — інша дія, і в
     * неї свій сенс. Тут — лист контактові, тим самим дротом, що й маршрут.
     */
    fun sharePlace(peerId: String, lat: Double, lon: Double, accuracyM: Int? = null) {
        val target = shareTargets.value.firstOrNull { it.peerId == peerId }
        val name = target?.name.orEmpty()
        val reach = target?.reach
        if (reach == null || reach == local.phantom.companion.core.net.peer.PeerLiveShare.Reach.NO_KEY) {
            _placeHandoff.value = PlaceHandoff.NoKey(name)
            return
        }
        val body = local.phantom.companion.core.net.peer.PeerPayload.encodePlace(lat, lon, accuracyM)
        // Складене звіряється ТИМ САМИМ розбирачем, яким кадр читатиме приймач:
        // encodePlace діапазону не судить, decodePlace судить, і другого суду
        // про те, що таке дійсне місце, у продукті бути не повинно.
        if (local.phantom.companion.core.net.peer.PeerPayload.decodePlace(body) == null) {
            _placeHandoff.value = PlaceHandoff.NoPlace
            return
        }
        sendPeerLetter(peerId, body)
        _placeHandoff.value = PlaceHandoff.Sent(name, reach)
    }

    /**
     * Чим скінчився дотик «надіслати місце» — реченням, не прапорцем.
     *
     * Той самий урок, що в [RouteHandoff]: дія, яка віддає Boolean, якого ніхто
     * не читає, виглядає для людини як зламаний застосунок.
     */
    sealed interface PlaceHandoff {
        /** Координати не склались у точку — надсилати нема чого. */
        data object NoPlace : PlaceHandoff

        /** Ключ зник між тим, як склали перелік, і дотиком. */
        data class NoKey(val name: String) : PlaceHandoff

        data class Sent(
            val name: String,
            val reach: local.phantom.companion.core.net.peer.PeerLiveShare.Reach,
        ) : PlaceHandoff
    }

    private val _placeHandoff = MutableStateFlow<PlaceHandoff?>(null)
    val placeHandoff: StateFlow<PlaceHandoff?> = _placeHandoff

    /** Смуга прочитана — прибрати, щоб не спливла вдруге на поверненні. */
    fun consumePlaceHandoff() { _placeHandoff.value = null }

    /** Порожній потік — легітимний стан: сеансу може не бути жодного. */
    val shareBanners: StateFlow<List<local.phantom.companion.core.net.peer.PeerLiveShare.Banner>> =
        liveShareBanners ?: MutableStateFlow(emptyList())

    val surfaceFeed: local.phantom.companion.core.data.transit.surface.SurfaceFeed? get() = surfaceFeedLoader?.invoke()
    val metroFeed: local.phantom.companion.core.data.transit.metro.MetroFeed? get() = metroFeedLoader?.invoke()
    val gtfsSchedule: local.phantom.companion.core.data.transit.gtfs.GtfsSchedule?
        get() = gtfsScheduleLoader?.invoke()

    /** LEVIATHAN-2 — live sky verdict (null = no locator wired). */
    val trust: StateFlow<LocationTrust?> = trustFeed ?: MutableStateFlow(null)

    /** LEVIATHAN-2 — the trail recorder; its GeoJSON feeds one map layer. */
    private val ewTrail = EwTrail()
    val ewTrailGeoJson: StateFlow<String> = ewTrail.geoJson

    /**
     * HORIZON II.1 — the clean-sky prior. Every pumped fix's trust verdict is
     * folded into a per-tile GNSS-integrity map that outlives the drive; the
     * offline router reads it to route around learned jammer shadows. Shared
     * with [SpatialRoutingService] below — і, коли екземпляр приходить ззовні
     * ([sharedGnssIntegrity]), з головним маршрутом теж: те саме вивчене небо
     * веде і того, хто тікає в укриття, і того, хто просто їде у справах.
     */
    val gnssIntegrity = sharedGnssIntegrity ?: GnssIntegrityMap()

    /** ATLAS-3 — how the operator is moving, for the chrome's motion pill. */
    val motion: StateFlow<KinematicsEngine.MotionProfile?> =
        motionFeed ?: MutableStateFlow(null)

    // Transient one-shot user notices (route failed, nothing nearby, saved…).
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()
    fun consumeNotice() { _notice.value = null }
    fun postNotice(msg: String) { _notice.value = msg }

    /** Distance/ETA/profile of the active route, or null when none. */
    data class RouteInfo(
        val distanceMeters: Double,
        val durationSeconds: Double,
        val profile: RouteProfile,
        val destLat: Double,
        val destLon: Double,
        /**
         * Куди. Порожньо — коли назви справді немає (маршрут у центр екрана),
         * і тоді панель каже координати, а не вигадує місце.
         *
         * Досі маршрут знав лише широту й довготу мети, тож панель показувала
         * «49 хв · 40,6 км · прибуття 19:04» і жодного разу — до ЧОГО. Число без
         * підмета: оператор бачить відповідь і не бачить питання.
         */
        val destName: String? = null,
        /** NAV-1 — via-waypoints between start and destination. */
        val viaCount: Int = 0,
        /** MULTIMODAL — the leg structure behind a TRANSIT plan (the ribbon). */
        val itinerary: local.phantom.companion.core.data.transit.Itinerary? = null,
    )

    /** Camera-fit request so the map can encapsulate a route's bounding box. */
    data class CameraBounds(val southLat: Double, val westLon: Double, val northLat: Double, val eastLon: Double)
    private val _cameraFlyToBounds = MutableSharedFlow<CameraBounds>(extraBufferCapacity = 2)
    val cameraFlyToBounds: SharedFlow<CameraBounds> = _cameraFlyToBounds.asSharedFlow()

    /**
     * HIGHWAY DOMINANCE — the drive is no longer this ViewModel's to own.
     *
     * NAV-2 put the route on `viewModelScope`, which was right when the map was
     * the only thing that could show one. It stops being right the moment a
     * second head exists: on Android Auto the phone's Activity is usually not
     * running, so a `viewModelScope` route is a route the car cannot see — and
     * a route that dies the moment the operator backgrounds the app mid-drive.
     *
     * So the engine is injected, process-scoped, and shared with the car. This
     * ViewModel is now an *observer* of it — it does not own the drive, and it
     * cannot end one by being destroyed.
     *
     * The fallback engine exists for previews and unit tests, which construct a
     * `MapViewModel` with no container behind them. It is never started, so it
     * never pumps a fix — a preview does not navigate.
     */
    private val engine: NavigationEngine by lazy {
        navEngine ?: NavigationEngine(
            scope = viewModelScope,
            locationSource = { null },
            planner = RoutePlanner(viewModelScope),
        )
    }

    internal val guidance: GuidanceController get() = engine.guidance

    /** ATLAS-2.5 — what a coast may be constrained to, and when to re-bind it. */
    internal fun selectedVariant() = nav.selectedVariant()
    internal fun planRevision() = nav.planRevision()

    /**
     * W-1 — the living half of the map: the fix pump, the air-raid sky and
     * NAV-5's threat watch, all of which must outlive the map's composition.
     * See [MapLiveSession] for why. Its lambdas reach the coordinators
     * declared below — safe, because nothing pumps until [start] runs from
     * the LAST init block of this class.
     */
    private val live: MapLiveSession = MapLiveSession(
        scope = viewModelScope,
        guidanceActive = { engine.guidance.isActive },
        announce = { engine.announce(it) },
        locationSource = locationSource,
        // Null on purpose — see [oblastShapesLoad]; the polygons arrive later.
        oblastShapes = null,
        alertWatch = airAlertWatch,
        activeRoute = { nav.activeRoutePoints.value },
        routeProfile = { nav.routeInfo.value?.profile },
        corridorCameras = { nav.corridorCameras() },
        corridorBarriers = { nav.corridorBarriers() },
        hazardMarks = {
            layers.value
                .filter { it.visible && it.spec.kind == local.phantom.companion.core.data.spatial.HAZARD_LAYER_KIND }
                .flatMap { it.spec.markers }
                // Провенанс їде далі разом із міткою: «спільнота» — це те, що
                // знак малює крапкою живого тону, і згорнути його тут означало
                // б видати чужий звіт за власний.
                .map { LayerMarkSighting(it.lat, it.lon, it.label ?: MapText.s(R.string.mapl_f_mark), it.source) }
        },
        guidanceProgress = { (engine.guidance.state.value as? local.phantom.companion.core.nav.GuidanceState.Guiding)?.progress },
        destinationName = { nav.selectedVariantPoints()?.lastOrNull()?.let { MapText.s(R.string.mapl_f_target) } },
        postNotice = { _notice.value = it },
        onFixForEvacuation = { lat, lon -> evac.updateGuidanceProgress(lat, lon) },
        clockWitness = ClockWitness(clockSkewStore),
        breadcrumbTracker = breadcrumbTracker,
        sentinelFenceEngine = sentinelFenceEngine,
    )

    /** W-1 — the operator's live fix, pumped by the session (never by a screen). */
    val operatorLocation: StateFlow<LocationFix?> = live.operatorLocation

    /** NAV-5 — live tactical safety hazards & route barriers (cameras, gates, checkpoints). */
    val activeHazards: StateFlow<List<local.phantom.companion.core.geo.cameras.SafetyHazard>> = live.safetyHazards

    /**
     * H-02 — the last satellite check of the device clock. The light countdown
     * is exactly as true as «now», and this is the only evidence the device has
     * about whether it knows what «now» is. Null record = never checked, which
     * the readout says out loud rather than assuming in the phone's favour.
     */
    val clockSkew: StateFlow<ClockSkew?> = live.clockSkew

    /** W-1 — the sky. Data, not chrome: NAV-5 warns about oblast crossings from it. */
    val alertStates: StateFlow<Map<String, AlertState>> = live.alertStates

    /** СИРЕНА — the session's observed alarm transitions (журнал), newest first. */
    internal val alertJournal: StateFlow<List<AlertTransition>> = live.alertJournal

    /** СИРЕНА — true while the token-gated detailed feed is actually answering. */
    val alertDetailLive: StateFlow<Boolean> = live.alertDetailLive

    /** СИРЕНА — wall time of the last successful alert fetch (null = never). */
    val alertFetchedAtMs: StateFlow<Long?> = live.alertFetchedAtMs

    /**
     * СИРЕНА-3 — знімок неба. Вирок з нього ЗНІМАЄ ЧИТАЧ, у мить малювання:
     * свіжість старіє від плину часу, і потік, порахований один раз, застигав
     * би свіжим у Doze разом із циклом опитування.
     */
    val sky: StateFlow<AlertSky> = live.sky

    val hereNow: local.phantom.companion.feature.stream.ui.map.here.HereNowDesk? = herePort?.let {
        local.phantom.companion.feature.stream.ui.map.here.HereNowDesk(it, sky, oblastAt, viewModelScope)
    }

    /**
     * ATLAS-2 — the third authority, display-side: HMM map-matching of the
     * pumped fix onto Overpass road tiles. Advisory by contract — the dot
     * uses [roadSnapped] when present and falls back to [operatorLocation],
     * so a quiet matcher can never blank the map.
     */
    /**
     * Дороги з намальованих векторних тайлів. Ставить власник полотна, коли
     * мапа готова — конструктором не передати, бо мапа народжується пізніше
     * за модель. Читається з [viewModelScope] (Main.immediate), тобто на тому
     * ж потоці, якого вимагає `queryRenderedFeatures`.
     */
    var renderedRoadSource: (() -> List<RoadWay>)? = null

    private val roadTruth = RoadTruth(
        scope = viewModelScope,
        operatorFix = live.operatorLocation,
        fetchRoads = roadWaysFetch,
        matchTrace = roadSnapMatch,
        renderedWays = { renderedRoadSource?.invoke().orEmpty() },
    )

    /** ATLAS-2 — the road's version of the dot (null = off-road/uncertain). */
    val roadSnapped: StateFlow<LocationFix?> = roadTruth.snapped

    /** ATLAS-2 — the matched street name, when the chrome wants to say it. */
    val roadName: StateFlow<String?> = roadTruth.wayName

    /**
     * APPLE-LOCK — the fix the pin actually renders, one authority, ranked:
     *
     *  1. During a live drive, on the corridor: the guidance session's
     *     route-snapped position + the route's own tangent as the bearing.
     *     This is the lock Apple Maps has and PHANTOM did not — the snap
     *     existed since NAV-2, but only the banner ever saw it, while the
     *     puck drew every metre of raw lateral multipath next to a route
     *     polyline that knew better. Works offline by construction (the
     *     corridor is already in memory), unlike [roadSnapped]'s Overpass
     *     tiles, which the field showed unreachable exactly when driving.
     *  2. [roadSnapped] — the display-side HMM matcher, free drive.
     *  3. The raw fused fix.
     *
     * The lock DISENGAGES honestly: off-route verdict, a blind engine, or
     * the raw fix drifting beyond [GUIDANCE_LOCK_MAX_OFF_M] of the snap all
     * fall back to the truth. Display-side only — guidance, reroute and the
     * EW ledger keep judging the raw fix; accuracy/dead ride along so the
     * trust halo keeps breathing with the real doubt.
     */
    /**
     * Free-drive snap hysteresis. Touched only by the [displayLocation] combine
     * (sequential by contract), like [roadLockEngaged].
     *
     * Declared ABOVE [displayLocation] and that is load-bearing: `stateIn` is
     * Eagerly-started on a `Main.immediate` scope, so the combine can run
     * during construction — below this line it would read null and take the
     * first fix down with it.
     */
    private val roadSnapCarry = RoadSnapCarry()

    /**
     * ARCH: PRECISION PIVOT — the absolute stationary lock. Declared ABOVE
     * [displayLocation] for the same load-bearing reason as [roadSnapCarry]: the
     * combine runs Eagerly during construction and would read a null lock below
     * this line. Touched only by that combine (sequential by contract).
     */
    private val stationaryLock = StationaryLock(imuMoving = bodyMoving)

    /** ARCH: PRECISION PIVOT — last logged display source, for transition-only
     *  diagnostics. Combine-private, like the lock. */
    private var lastPositionSource: String? = null

    val displayLocation: StateFlow<LocationFix?> = kotlinx.coroutines.flow.combine(
        live.operatorLocation,
        engine.state,
        roadTruth.snapped,
    ) { raw, nav, road ->
        val locked = guidanceLockedFix(
            raw, nav.guiding, nav.blind, nav.progress,
            wasLocked = roadLockEngaged,
        )
        roadLockEngaged = locked != null
        val chosen: LocationFix? = when {
            locked != null -> {
                // The route lock outranks the free-drive matcher; nothing to
                // carry while it holds.
                roadSnapCarry.reset()
                locked
            }
            raw == null -> raw
            else -> {
                // STATION-KEEPING — a parked body does not belong to the
                // nearest road. At a standstill the HMM match flaps
                // matched↔uncertain on scatter and the pin jumps
                // street↔courtyard once a second; road advice is for travel.
                val moving = (raw.speedMps ?: 0f) >= ROAD_SNAP_MIN_SPEED_MPS
                // One source, every tick — see [RoadSnapCarry]. Never
                // `snap ?: raw`: that alternation IS the camera lurch.
                roadSnapCarry.step(raw = raw, snap = road, moving = moving)
            }
        }
        // ARCH: PRECISION PIVOT (Load 4+5) — the OUTERMOST stage. Whatever source
        // won above (route lock, road snap, or the raw fused fix), a demonstrably
        // stationary body freezes HERE. The pin and the follow camera both read
        // this one flow (see MapScreen), so a frozen fix holds the puck AND stops
        // the camera rotating on GNSS noise. «Точка ковзає» ends at this line —
        // and there is no dead-reckoning left anywhere upstream to fabricate the
        // motion that used to feed it.
        val nowMs = System.currentTimeMillis()
        // Under an engaged route lock the fix is road-snapped and advances only
        // with real progress, so the stationary freeze must NOT hold it — that
        // was the frozen puck + route line while the distance still counted
        // down. Free-drive/free-follow keeps freezing to kill parked slide.
        val out = chosen?.let {
            if (locked != null) { stationaryLock.reset(); it } else stationaryLock.step(it, nowMs)
        }
        val source = when {
            out == null -> "none"
            stationaryLock.isFrozen -> "frozen"
            locked != null -> "guidance"
            roadSnapCarry.carrying -> "road-snap"
            else -> "raw"
        }
        logPositionSource(source, out, road)
        out
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** APPLE-LOCK wave 2 — the lock's memory, for its engage/release hysteresis.
     *  Touched only by the [displayLocation] combine (sequential by contract). */
    private var roadLockEngaged = false

    /** ARCH: PRECISION PIVOT — log a display-source change with the inputs behind
     *  it. Transition-only (no per-fix spam); no-op when [positionLog] is null. */
    private fun logPositionSource(source: String, out: LocationFix?, snap: LocationFix?) {
        val log = positionLog ?: return
        if (source == lastPositionSource) return
        val prev = lastPositionSource
        lastPositionSource = source
        log(
            buildString {
                append("pos ").append(prev ?: "—").append("→").append(source)
                append(" spd=")
                    .append(String.format(java.util.Locale.US, "%.1f", out?.speedMps ?: 0f))
                    .append("m/s")
                append(" acc=").append(out?.accuracyM?.let { "${it.toInt()}m" } ?: "—")
                append(" hdg=").append(out?.bearing?.let { "${it.toInt()}°" } ?: "—")
                append(" snap=").append(if (snap != null) "y" else "n")
                append(" frozen=").append(stationaryLock.isFrozen)
            },
        )
    }

    /**
     * LEVIATHAN-1 — evacuation routing with the offline fallback: when OSRM
     * is unreachable (the air-raid-plus-dead-network moment), shelter routes
     * come from the road tiles the matcher already cached.
     */
    private val spatialRoutingService: SpatialRoutingService =
        spatialRoutingServiceOverride
            ?: SpatialRoutingService(
                localGraph = { roadTruth.graphSnapshot() },
                integrity = { gnssIntegrity },
                coverFetch = coverFetch,
            )

    /** W-6 — is anyone actually standing on the ground right now? */
    fun setWorldVisible(visible: Boolean) {
        live.setWorldVisible(visible)
        // The matcher's Overpass pump obeys the same glass: nobody pays for
        // road tiles under an opaque conversation.
        roadTruth.setActive(visible)
    }

    /**
     * FIELD REPORT 2026-07-18 («екран стрибає, коли я слідкую за точкою») —
     * is the operator actively FOLLOWING the pin on a visible map? The shell
     * reads this to arm navigation-grade GPS: follow used to ride the
     * ambient rate (one balanced-power fix per 5 s, usually speed- and
     * bearing-less), FixFlux had nothing to bridge with, and the map moved
     * in one lurch per fix. A followed map deserves the same chip the drive
     * gets — and only while it is actually followed and visible.
     */
    private val _followActive = MutableStateFlow(false)
    val followActive: StateFlow<Boolean> = _followActive.asStateFlow()
    fun setFollowActive(active: Boolean) {
        _followActive.value = active
    }

    /**
     * R4.1 — the phantom's live layer stack. Every `map.show` / world-tool
     * push lands here and the map redraws. Empty flow when no store is wired
     * (keeps older tests + previews trivial).
     */
    val layers: StateFlow<List<SpatialLayer>> =
        (spatialLayers?.layers ?: MutableStateFlow(emptyList()))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** R4.1 — camera-move requests from `map.focus` and layer pushes. */
    val focusRequests: SharedFlow<FocusTarget>? = spatialLayers?.focusRequests

    /** Збережені ламані. Порожній потік без сховища — превʼю й тести не падають. */
    val drawnLines: StateFlow<List<DrawnLine>> =
        (drawnLineStore?.liveFlow() ?: MutableStateFlow(emptyList()))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Зберегти намальовану ламану. Відмова сховища переказується словом:
     * мовчазне «не збереглось» — саме той дефект, від якого брама імені й є.
     */
    fun saveDrawnLine(label: String?, points: List<GeoPoint>, savedMsg: (String) -> String, unnamedMsg: String) {
        val store = drawnLineStore ?: return
        viewModelScope.launch {
            when (val result = store.save(label, points)) {
                is DrawnLineStore.SaveResult.Saved -> postNotice(savedMsg(result.label))
                is DrawnLineStore.SaveResult.Unnamed -> postNotice(unnamedMsg)
                is DrawnLineStore.SaveResult.TooFew -> Unit
            }
        }
    }

    fun updateDrawnLine(id: String, points: List<GeoPoint>) {
        val store = drawnLineStore ?: return
        viewModelScope.launch { store.replaceVertices(id, points) }
    }

    fun deleteDrawnLine(id: String) {
        val store = drawnLineStore ?: return
        viewModelScope.launch { store.remove(id) }
    }

    /**
     * Маршрут уздовж намальованої лінії: її вершини стають точками маршруту.
     *
     * Через той самий планувальник, що й будь-який інший маршрут — лінія лише
     * називає, ДЕ пройти, а по дорогах веде вже він.
     */
    fun routeAlongLine(points: List<GeoPoint>) {
        if (points.size < 2) return
        reorderWaypoints(points.map { it.lat to it.lon })
    }

    /**
     * СИМБІОЗ, хвиля 1 — точки контактів, як їх кладе месенджер.
     *
     * [kotlinx.coroutines.flow.sample] — це і є домовлений 1 Гц: писати нам
     * можуть хоч раз на секунду на кожного з тридцяти двох, а перебудовувати
     * джерело шару частіше за раз на секунду немає ЩО показувати — людина за
     * секунду не встигає зрушити на піксель. Порожній репозиторій (месенджерний
     * бік ще не приїхав) дає порожній список і жодного такту.
     */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    val peerPoints: StateFlow<List<PeerPoint>> =
        (peerPointsRepository?.points ?: MutableStateFlow(emptyList()))
            .sample(PEER_RENDER_TICK_MS)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Мітла строків. Цокає споживач — core-data власного годинника не має. */
    suspend fun sweepPeers() {
        peerPointsRepository?.sweep()
    }

    private val _selectedVehicle = MutableStateFlow<TransitVehicle?>(null)
    val selectedVehicle: StateFlow<TransitVehicle?> = _selectedVehicle.asStateFlow()

    /** Модельна машина не має картки, бо не має й пігулки: закон 1 (D8). */
    fun selectVehicle(vehicle: TransitVehicle?) {
        _selectedVehicle.value = vehicle?.takeIf { it.isLiveGps }
    }

    /**
     * Що чесно можна сказати про транспорт у цій точці — одна відповідь для
     * хаба й досьє.
     *
     * Ярус Transitland тут завжди «немає»: його ключ живе в `:app` і в UI не
     * заходить, тож заявляти статичні розклади було б бейджем без даних.
     */
    internal fun transitCoverageAt(lat: Double, lon: Double): TransitCoverage {
        val surface = surfaceFeed?.bounds
        val metro = metroFeed?.bounds
        return TransitCoverageResolver.resolve(
            lat = lat,
            lon = lon,
            simulatedBounds = if (surface != null && metro != null) surface.union(metro) else surface ?: metro,
            hasLiveClient = local.phantom.companion.core.data.transit.GtfsFeedRegistry.feedFor(lat, lon) != null,
            transitlandAvailable = false,
        )
    }

    // ── The coordinators (each one is a chapter of the old monolith) ──────

    private val nav: MapRouteCoordinator = MapRouteCoordinator(
        scope = viewModelScope,
        engine = engine,
        postNotice = { _notice.value = it },
        syncThreatWatch = { live.syncThreatWatch() },
        navCommands = navCommands,
    )

    private val evac: EvacuationCoordinator = EvacuationCoordinator(
        scope = viewModelScope,
        shelterRepository = shelterRepository,
        poiRepository = poiRepository,
        nav = nav,
        routingService = spatialRoutingService,
        emitCameraBounds = { _cameraFlyToBounds.tryEmit(it) },
    )

    private val poi: MapPoiSearch = MapPoiSearch(
        scope = viewModelScope,
        poiRepository = poiRepository,
        vaultStore = vaultStore,
        savedPlaceStore = savedPlaceStore,
        postNotice = { _notice.value = it },
        activeCorridor = { nav.selectedVariantPoints() },
        fitCamera = { s, w, n, e -> _cameraFlyToBounds.tryEmit(CameraBounds(s, w, n, e)) },
        oblastAt = oblastAt,
    )

    private val transitousStops =
        local.phantom.companion.core.net.transit.TransitousStopClient()

    private val packTransit: MapPackTransit = MapPackTransit(
        scope = viewModelScope,
        fetch = packTransitFetch,
        // Рамка камери — це те, куди людина дивиться: назовні лише після явної згоди.
        liveStops = { s, w, n, e ->
            if (local.phantom.companion.core.net.map.GeoEndpoints.config.worldTransitOptIn) transitousStops.stopsIn(s, w, n, e) else null
        },
    )

    /** Мережа транспорту з пакета, для шару мапи. */
    val packStopsGeoJson: StateFlow<String> = packTransit.stopsGeoJson
    val packRailsGeoJson: StateFlow<String> = packTransit.railsGeoJson

    private val world: MapWorldLayers = MapWorldLayers(
        scope = viewModelScope,
        spatialLayers = spatialLayers,
        worldSummoner = worldSummoner,
        dossierClient = dossierClient,
        layersNow = { layers.value },
        graphSnapshot = { roadTruth.graphSnapshot() },
        postNotice = { _notice.value = it },
        emitCameraBounds = { _cameraFlyToBounds.tryEmit(it) },
    )

    // ── Route & drive (MapRouteCoordinator) ───────────────────────────────
    val voiceCues: SharedFlow<local.phantom.companion.core.nav.NavCue> get() = nav.voiceCues
    val voicePrewarms: SharedFlow<String> get() = nav.voicePrewarms
    val isGuiding: StateFlow<Boolean> get() = nav.isGuiding
    val routes: StateFlow<List<MapPayload.PolylineData>> get() = nav.routes
    internal val maneuvers: StateFlow<List<ManeuverPins.Pin>> get() = nav.maneuvers
    internal val laneBands: StateFlow<List<local.phantom.companion.feature.stream.ui.map.nav.lanes.LaneBand>>
        get() = nav.laneBands
    val routeInfo: StateFlow<RouteInfo?> get() = nav.routeInfo

    /** Відмова маршрутизатора у формі смуги, а не пігулки, що спливає. */
    internal val routeFailure: StateFlow<RouteFailure?> get() = nav.routeFailure
    val routing: StateFlow<Boolean> get() = nav.routing
    val activeRoutePoints: StateFlow<List<Pair<Double, Double>>?> get() = nav.activeRoutePoints
    internal val routeOptions: StateFlow<List<RoutePlanner.Option>> get() = nav.routeOptions

    /**
     * Geometry of the variants NOT selected. A comparison sheet with nothing on
     * the map is a choice made blind — this is what the alternatives layer draws.
     */
    /** One unpicked variant: its line, and what it costs. */
    internal data class AltRoute(
        val points: List<Pair<Double, Double>>,
        val label: String,
        val index: Int,
    )

    internal val altRoutes: StateFlow<List<AltRoute>> =
        kotlinx.coroutines.flow.combine(
            nav.routeOptions,
            nav.routeSelectedIndex,
        ) { options, sel ->
            if (options.size < 2) {
                emptyList()
            } else {
                options.mapIndexedNotNull { i, o ->
                    if (i == sel) {
                        null
                    } else {
                        AltRoute(
                            points = o.variant.points,
                            label = shortEta(o.variant.durationSeconds),
                            index = i,
                        )
                    }
                }
            }
        }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyList())

    internal val routeSelectedIndex: StateFlow<Int> get() = nav.routeSelectedIndex
    internal val deadline: StateFlow<DeadlineWatch?> get() = nav.deadline
    internal val deadlineStatus: StateFlow<DeadlineWatch.Status?> get() = nav.deadlineStatus
    fun setDeadlineTo(text: String): Boolean = nav.setDeadlineTo(text)
    internal val transitDepartSec: StateFlow<Int?> get() = nav.transitDepartSec
    fun setTransitDeparture(sec: Int?) = nav.setTransitDeparture(sec)
    fun routeTo(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        profile: RouteProfile = RouteProfile.FOOT,
        destName: String? = null,
    ) = nav.routeTo(fromLat, fromLon, toLat, toLon, profile, destName)
    fun reRouteProfile(fromLat: Double, fromLon: Double, profile: RouteProfile) =
        nav.reRouteProfile(fromLat, fromLon, profile)
    fun addViaPoint(lat: Double, lon: Double) = nav.addViaPoint(lat, lon)
    fun removeViaPoint(index: Int) = nav.removeViaPoint(index)
    fun reorderWaypoints(newOrder: List<Pair<Double, Double>>) = nav.reorderWaypoints(newOrder)
    fun setRouteOrigin(lat: Double, lon: Double) = nav.setRouteOrigin(lat, lon)
    fun setRouteDestination(lat: Double, lon: Double, name: String? = null) = nav.setRouteDestination(lat, lon, name)
    val waypoints: List<Pair<Double, Double>> get() = nav.waypoints
    fun selectRouteAlternative(index: Int) = nav.selectRouteAlternative(index)

    /**
     * Cycle to the next variant. A tap on the map cannot say WHICH alternative
     * it meant when two of them overlap, and asking is worse than advancing.
     */
    fun selectNextAlternative() {
        val n = nav.routeOptions.value.size
        if (n < 2) return
        nav.selectRouteAlternative((nav.routeSelectedIndex.value + 1) % n)
    }
    fun navigateTo(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, profile: RouteProfile, via: List<Pair<Double, Double>> = emptyList()) =
        nav.navigateTo(fromLat, fromLon, toLat, toLon, profile, via)
    fun prefetchRoute(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double) =
        nav.prefetchRoute(fromLat, fromLon, toLat, toLon)
    // Дві кнопки «скинути» мусять чистити ОБИДВА стани: під час евакуації ця
    // раніше стирала лише червону лінію (nav.clearRoute()), а картка евакуації
    // (MapToolsCapsule.EvacuationCard) далі рахувала відстань і час до
    // укриття, якого на мапі вже не було — застосунок лишався напівбрехливим.
    fun clearRoute() {
        nav.clearRoute()
        evac.clear()
    }
    fun setDeadline(epochMs: Long) = nav.setDeadline(epochMs)
    fun clearDeadline() = nav.clearDeadline()
    fun onDeadlineEstimate(remainingDurationS: Double): String? = nav.onDeadlineEstimate(remainingDurationS)

    // ── Evacuation (EvacuationCoordinator) ────────────────────────────────
    val evacuationState: StateFlow<EvacuationState> get() = evac.state
    fun routeToNearestShelter(fromLat: Double, fromLon: Double, radiusMeters: Int = 3000) =
        evac.routeToNearestShelter(fromLat, fromLon, radiusMeters)
    fun reportEvacuationBlocked(reason: String) = evac.reportBlocked(reason)
    fun updateGuidanceProgress(lat: Double, lon: Double) = evac.updateGuidanceProgress(lat, lon)
    fun clearEvacuation() = evac.clear()

    // ── POI discovery (MapPoiSearch) ──────────────────────────────────────
    val pois: StateFlow<List<PoiEntity>> get() = poi.pois
    val savedPois: StateFlow<List<PoiEntity>> get() = poi.savedPois
    val loading: StateFlow<Boolean> get() = poi.loading
    val nearbyActive: StateFlow<String?> get() = poi.nearbyActive
    internal val searchGap: StateFlow<SearchGap?> get() = poi.searchGap
    /** Search overhaul — recent queries (Vault-persisted, newest first). */
    val recentSearches: StateFlow<List<String>> get() = poi.recentSearches
    fun rememberSearch(query: String) = poi.rememberSearch(query)
    fun clearRecentSearches() = poi.clearRecent()
    /** Дім/робота з першого екрана пошуку — єдиний дотик, що пише ці категорії. */
    fun setPlaceAnchor(category: String, label: String, lat: Double, lon: Double) =
        poi.setAnchor(category, label, lat, lon)
    suspend fun searchOffline(query: String, lat: Double, lon: Double) = poi.offlineSearch(query, lat, lon)

    /**
     * Переклад підказки про заслін масштабу — з композиції сюди.
     *
     * Та сама дорога, що вже носить `map_shelter_no_fix`: рядок розвʼязує той,
     * у кого є ресурси, а не той, хто вирішує, показувати чи ні.
     */
    fun setPoiZoomHint(text: String) = poi.setZoomHint(text)

    fun setPoiOfflineKindHint(template: String) = poi.setOfflineKindHint(template)

    fun updateCameraBounds(bounds: OfflineBounds, zoom: Double = 0.0) {
        poi.updateCameraBounds(bounds, zoom)
        // Той самий кадр камери годує й транспорт із пакета: другий слухач
        // простою камери означав би два різні уявлення про те, де ми зараз.
        packTransit.onCamera(bounds, zoom)
    }
    fun showNearby(selector: String, label: String, lat: Double, lon: Double, radiusMeters: Int = 3000) =
        poi.showNearby(selector, label, lat, lon, radiusMeters)
    fun showNearbyByName(query: String, lat: Double, lon: Double, radiusMeters: Int = 5000) =
        poi.showNearbyByName(query, lat, lon, radiusMeters)
    fun showNearbyAlongRoute(selector: String, label: String) = poi.showNearbyAlongRoute(selector, label)
    fun clearNearby() = poi.clearNearby()

    // ── World layers (MapWorldLayers) ─────────────────────────────────────
    val summoning: StateFlow<String?> get() = world.summoning
    val worldMenuEnabled: Boolean get() = world.worldMenuEnabled
    fun summonWorld(kind: String, lat: Double, lon: Double) = world.summonWorld(kind, lat, lon)
    internal fun applyLensSummons(kinds: Set<String>, lat: Double, lon: Double) =
        world.applyLensSummons(kinds, lat, lon)
    fun toggleLayer(layerId: String, visible: Boolean) = world.toggleLayer(layerId, visible)
    fun clearLayer(layerId: String) = world.clearLayer(layerId)
    fun showTransitLine(line: PlaceDossierClient.RouteLineRef) = world.showTransitLine(line)
    internal fun showLocalTransitLine(line: local.phantom.companion.feature.stream.ui.map.dossier.DossierTransitLine) = world.showLocalTransitLine(line)
    fun reportHazard(kind: String, lat: Double, lon: Double) = world.reportHazard(kind, lat, lon)
    fun showShelterIsochrone(fromLat: Double, fromLon: Double) = world.showShelterIsochrone(fromLat, fromLon)
    fun showDangerField() = world.showDangerField()
    /** Map-symbiosis — a PC layer pack the operator already pulled onto the
     *  phone (PcLayerPacksSection), pushed into the SAME renderer every other
     *  world layer uses (SpatialLayerRepository → MapCanvas), so it stops
     *  being data that only the "on device" caption knows about. */
    fun pushPcLayer(spec: local.phantom.companion.core.data.spatial.SpatialLayerSpec) =
        world.pushPcLayer(spec)

    /**
     * P1 — the dossier's LRU cache, ViewModel-scoped on purpose: it must
     * outlive the sheet (which is destroyed on every dismiss) and die with
     * the map (a stale «Відчинено · до 18:00» must not survive the session).
     */
    private val dossierCache = DossierCache()

    private fun dossierSources(
        reverseGeocode: suspend (Double, Double) -> String?,
    ): DossierSources = OverpassDossierSources(
        client = dossierClient,
        poiRepository = poiRepository,
        reverseGeocoder = reverseGeocode,
        // ЛОКАЛЬНЕ ПЕРШЕ — те, що мапа вже намалювала, і сітка доріг мапчера.
        viewportPois = { poi.pois.value },
        roadGraph = { roadTruth.graphSnapshot() },
    )

    /**
     * Огляди від PHANTOM, по одному на місце (ключ — [dossierKey]). Живуть у
     * ViewModel, бо лист вмирає на кожному закритті, а вже отриману відповідь
     * не можна втрачати разом із ним.
     */
    private val _briefings = MutableStateFlow<Map<String, DossierBriefing>>(emptyMap())
    internal val briefings: StateFlow<Map<String, DossierBriefing>> = _briefings.asStateFlow()

    private val briefJobs = mutableMapOf<String, kotlinx.coroutines.Job>()

    private fun putBriefing(key: String, briefing: DossierBriefing) {
        _briefings.value = _briefings.value + (key to briefing)
    }

    /**
     * «Огляд від PHANTOM» — токени течуть у картку, не в чат.
     *
     * Жодного інструмента в намірі: виклик `map.*` перекинув би камеру назад
     * у мапу, тобто повернув би пінг-понг мапа→чат→мапа. Копія відповіді
     * лягає в чат через [chatEcho] уже після завершення, як історія.
     */
    internal fun askDossierBriefing(
        dossier: PlaceDossier,
        origin: Pair<Double, Double>? = null,
    ) {
        val key = dossierKey(dossier.target)
        val current = _briefings.value[key]
        if (current != null && (current.isBusy || current.phase == DossierBriefing.Phase.DONE)) return

        val ai = aiComplete
        if (ai == null) {
            val localSummary = buildLocalSummary(dossier)
            if (localSummary != null) {
                putBriefing(key, DossierBriefing(localSummary, DossierBriefing.Phase.DONE))
            } else {
                putBriefing(key, DossierBriefing(phase = DossierBriefing.Phase.IDLE))
            }
            return
        }

        briefJobs[key]?.cancel()
        briefJobs[key] = viewModelScope.launch {
            putBriefing(key, DossierBriefing(phase = DossierBriefing.Phase.THINKING))
            val acc = StringBuilder()
            var failure: String? = null
            runCatching {
                ai(
                    local.phantom.companion.core.ai.AIIntent.Text(
                        prompt = dossierBriefPrompt(dossier, origin),
                        systemPrompt = DOSSIER_BRIEF_SYSTEM,
                        maxTokens = BRIEF_MAX_TOKENS,
                        temperature = BRIEF_TEMPERATURE,
                    ),
                ).collect { chunk ->
                    when (chunk) {
                        is local.phantom.companion.core.ai.AIChunk.Token -> {
                            acc.append(chunk.text)
                            putBriefing(
                                key,
                                DossierBriefing(acc.toString(), DossierBriefing.Phase.STREAMING),
                            )
                        }
                        is local.phantom.companion.core.ai.AIChunk.Final -> {
                            if (acc.isEmpty()) acc.append(chunk.text)
                        }
                        is local.phantom.companion.core.ai.AIChunk.ErrorChunk -> {
                            if (acc.isEmpty()) acc.append(chunk.partialText)
                            failure = chunk.cause.messageOverride ?: AI_FAILED
                        }
                        else -> Unit
                    }
                }
            }.onFailure { t ->
                if (t is kotlinx.coroutines.CancellationException) throw t
                failure = t.message ?: AI_FAILED
            }

            val text = acc.toString().trim()
            if (text.isEmpty()) {
                val localSummary = buildLocalSummary(dossier)
                if (localSummary != null) {
                    putBriefing(key, DossierBriefing(localSummary, DossierBriefing.Phase.DONE))
                } else {
                    putBriefing(
                        key,
                        DossierBriefing(phase = DossierBriefing.Phase.FAILED, error = failure ?: AI_EMPTY),
                    )
                }
                return@launch
            }
            putBriefing(key, DossierBriefing(text, DossierBriefing.Phase.DONE))
            // Історія в чаті — БЕЗ перекидання камери.
            runCatching { chatEcho?.invoke(dossierBriefQuestion(dossier), text) }
        }
    }

    private fun buildLocalSummary(dossier: local.phantom.companion.feature.stream.ui.map.dossier.PlaceDossier): String? {
        val facts = mutableListOf<String>()
        dossier.kindLabel?.let { facts.add(MapText.s(R.string.mapl_f_summary_type, it)) }
        dossier.openStatus?.let { status ->
            when (status) {
                is local.phantom.companion.core.net.map.OpeningHours.Status.Open ->
                    facts.add(MapText.s(R.string.mapl_f_summary_open))
                is local.phantom.companion.core.net.map.OpeningHours.Status.Closed ->
                    facts.add(MapText.s(R.string.mapl_f_summary_closed))
                else -> Unit
            }
        }
        dossier.address?.let { facts.add(MapText.s(R.string.mapl_f_summary_address, it)) }
        dossier.phone?.let { facts.add(MapText.s(R.string.mapl_f_summary_phone, it)) }
        if (facts.isEmpty()) return null
        return "${dossier.title} — ${facts.joinToString(" · ")}"
    }

    /* ── Питання з мапи ────────────────────────────────────────────────── */

    private val _mapAnswer = MutableStateFlow(MapAnswer())
    internal val mapAnswer: StateFlow<MapAnswer> = _mapAnswer.asStateFlow()
    private var askJob: kotlinx.coroutines.Job? = null

    /**
     * Питання, поставлене з мапи, і відповідь, що лишається на мапі.
     *
     * Той самий шлях, що в [askDossierBriefing], і з тієї ж причини: рядок
     * «Запитати PHANTOM про «х»» досі кидав оператора в розмову, тобто забирав
     * у нього те, про що він питав. Копія лягає в чат [chatEcho] уже після
     * завершення — як історія, без перекидання камери.
     */
    internal fun askOnMap(
        question: String,
        here: Pair<Double, Double>? = null,
        locality: String? = null,
    ) {
        val q = question.trim()
        if (q.isBlank()) return

        val ai = aiComplete
        if (ai == null) {
            _mapAnswer.value = MapAnswer(question = q, phase = MapAnswer.Phase.FAILED, error = AI_OFFLINE)
            return
        }

        askJob?.cancel()
        askJob = viewModelScope.launch {
            _mapAnswer.value = MapAnswer(question = q, phase = MapAnswer.Phase.THINKING)
            val acc = StringBuilder()
            var failure: String? = null
            runCatching {
                ai(
                    local.phantom.companion.core.ai.AIIntent.Text(
                        prompt = local.phantom.companion.feature.stream.domain.MapAskPrompt
                            .of(q, here, locality),
                        systemPrompt = local.phantom.companion.feature.stream.domain.MapAskPrompt.SYSTEM,
                        maxTokens = BRIEF_MAX_TOKENS,
                        temperature = BRIEF_TEMPERATURE,
                    ),
                ).collect { chunk ->
                    when (chunk) {
                        is local.phantom.companion.core.ai.AIChunk.Token -> {
                            acc.append(chunk.text)
                            _mapAnswer.value = MapAnswer(q, acc.toString(), MapAnswer.Phase.STREAMING)
                        }
                        is local.phantom.companion.core.ai.AIChunk.Final -> {
                            if (acc.isEmpty()) acc.append(chunk.text)
                        }
                        is local.phantom.companion.core.ai.AIChunk.ErrorChunk -> {
                            if (acc.isEmpty()) acc.append(chunk.partialText)
                            failure = chunk.cause.messageOverride ?: AI_FAILED
                        }
                        else -> Unit
                    }
                }
            }.onFailure { t ->
                if (t is kotlinx.coroutines.CancellationException) throw t
                failure = t.message ?: AI_FAILED
            }

            val text = acc.toString().trim()
            if (text.isEmpty()) {
                _mapAnswer.value = MapAnswer(
                    question = q,
                    phase = MapAnswer.Phase.FAILED,
                    error = failure ?: AI_EMPTY,
                )
                return@launch
            }
            _mapAnswer.value = MapAnswer(q, text, MapAnswer.Phase.DONE)
            runCatching {
                chatEcho?.invoke(
                    local.phantom.companion.feature.stream.domain.MapAskPrompt.echoTitle(q),
                    text,
                )
            }
        }
    }

    /** Закриває аркуш відповіді і глушить потік, якщо він ще тече. */
    internal fun clearMapAnswer() {
        askJob?.cancel()
        askJob = null
        _mapAnswer.value = MapAnswer()
    }

    /** Повторний запит після невдачі — скидає стан і питає ще раз. */
    internal fun retryDossierBriefing(dossier: PlaceDossier) {
        val key = dossierKey(dossier.target)
        briefJobs.remove(key)?.cancel()
        _briefings.value = _briefings.value - key
        askDossierBriefing(dossier)
    }

    /**
     * P1 — «досьє місця» як ПОТІК знімків, а не одна відповідь наприкінці.
     *
     * Перший знімок приходить без жодного мережевого запиту (назва,
     * категорія, координата — усе це вже було в руках у момент тапу), тож
     * лист малює пік і робочу кнопку «Маршрут» одразу; далі кожен слот
     * доливається окремо і жоден не блокує ані інший, ані картку.
     *
     * Це і є заміна 15-секундному спінеру.
     */
    internal fun dossierFlow(
        target: DossierTarget,
        reverseGeocode: suspend (Double, Double) -> String?,
    ): kotlinx.coroutines.flow.Flow<PlaceDossier> =
        placeDossierFlow(
            target = target,
            sources = dossierSources(reverseGeocode),
            cache = dossierCache,
            transitSource = local.phantom.companion.feature.stream.ui.map.dossier.CompositeTransitSource(
                local.phantom.companion.feature.stream.ui.map.dossier.LocalTransitSource(
                    surface = { surfaceFeed },
                    metro = { metroFeed },
                    schedule = { gtfsSchedule },
                ),
            ),
        )

    init {
        // The route coordinator's collectors reach live.syncThreatWatch(),
        // which reads siblings — so they start here, after every property
        // above exists, never in its constructor.
        nav.start()

        // LEVIATHAN-2 — every pumped fix leaves a crumb, colored by the
        // engine's trust at that instant: the EW topology of the track.
        // HORIZON II.1 — and the same verdict trains the clean-sky prior.
        // Trail follows the puck; the integrity map below stays on the raw fix.
        viewModelScope.launch {
            displayLocation.collect { fix ->
                if (fix != null) {
                    ewTrail.onFix(
                        lat = fix.lat,
                        lon = fix.lon,
                        dead = fix.dead,
                        trust = trustFeed?.value,
                        nowMs = System.currentTimeMillis(),
                        // Без цих двох слід писав крихту на КОЖЕН зсув ≥2 м,
                        // а при точності ±85 м нерухомий телефон дає такий
                        // зсув щосекунди: на екрані виростала різнокольорова
                        // подряпина замість шляху.
                        speedMps = fix.speedMps,
                        accuracyM = fix.accuracyM,
                    )
                }
            }
        }
        viewModelScope.launch {
            operatorLocation.collect { fix ->
                if (fix != null) {
                    val trust = trustFeed?.value
                    gnssIntegrity.observe(
                        fix.lat, fix.lon,
                        GnssIntegrityMap.riskOf(trust, fix.dead),
                    )
                }
            }
        }

        // NAV-5 — corridor cameras arrive when the async intel lands; marks
        // change when a hazard layer is written.
        layers.onEach { live.refreshThreats() }.launchIn(viewModelScope)
        nav.routeOptions.onEach { live.refreshThreats() }.launchIn(viewModelScope)
    }

    /**
     * W-1 — the pump starts LAST, and this placement is load-bearing, not style.
     *
     * Kotlin runs property initialisers and init blocks in declaration order, and
     * [MapLiveSession.start] seeds a fix *synchronously* — `viewModelScope` is
     * `Main.immediate`, so its collector runs before `start()` has even returned.
     * Started from the init block at the top of this class, that very first fix
     * reached the evacuation state — declared far below it — while the field was
     * still null, and the map died on the first frame of the first drive.
     * Nothing may pump until the whole ViewModel exists.
     */
    init {
        live.start()

        // Defect #1 — the oblast polygons are pulled AFTER the pump, on IO, and
        // never on the thread that built this ViewModel. The first suspension
        // is `withContext`, so nothing here runs during construction; the
        // session simply watches no oblast for the handful of frames until the
        // parse lands, then rebuilds its threat watch.
        val load = oblastShapesLoad
        if (load != null) {
            viewModelScope.launch {
                val shapes = withContext(Dispatchers.IO) { runCatching { load() }.getOrNull() }
                live.attachOblastShapes(shapes)
            }
        }
    }
}

/** Огляд місця — короткий за задумом: це абзац у картці, а не есе в чаті. */
private const val BRIEF_MAX_TOKENS = 320
private const val BRIEF_TEMPERATURE = 0.4f

private val AI_OFFLINE: String get() = MapText.s(R.string.mapl_f_ai_offline)
private val AI_FAILED: String get() = MapText.s(R.string.mapl_f_ai_failed)
private val AI_EMPTY: String get() = MapText.s(R.string.mapl_f_ai_empty)

/** STATION-KEEPING — below this speed the HMM road-match may not own the
 *  pin: a parked dot flapping onto the nearest street is the jump itself. */
internal const val ROAD_SNAP_MIN_SPEED_MPS = 1.5f

/** How many consecutive snap MISSES the carry survives before it gives the
 *  pin back to the raw fix. ~4 s at a 1 Hz fix rate: long enough to ride out
 *  a timed-out `/match` round-trip, short enough that a genuinely wrong
 *  correction cannot persist. */
internal const val ROAD_SNAP_GRACE_MISSES = 4

/** A snap farther than this from the raw fix is not a correction, it is a
 *  different road. Refused outright rather than carried. */
internal const val ROAD_SNAP_MAX_OFFSET_M = 35.0

/** Once the grace window expires the correction is stale — but it is released
 *  over this many fixes rather than dropped in one, so letting go is a glide. */
internal const val ROAD_SNAP_RELEASE_STEPS = 3

/**
 * FIELD REPORT (owner, 2026-07-21) — «карта по осі пиздує»: in follow mode the
 * camera lurched sideways once per fix.
 *
 * The cause was a source that ALTERNATED. `displayLocation` fell back
 * `snap ?: raw`, and a free-drive snap is intermittent by nature — OSRM
 * `/match` answers some ticks and times out or lands under the confidence bar
 * on others. Success and failure are two DIFFERENT positions several metres
 * apart, so the display fix hopped between them at the fix rate and the camera
 * dutifully chased the hop. Before `33d39e2` the free-drive snap rode Overpass
 * tiles that the field showed unreachable while driving — it essentially never
 * fired, so the fallback was *consistently* raw. Consistently wrong is smooth;
 * intermittently right is jitter. Making the snap work is what exposed the
 * missing hysteresis, the same one [guidanceLockedFix] already has.
 *
 * The fix is to stop treating the snap as a POSITION and treat it as an
 * OFFSET. While the matcher answers, the correction is remembered; when it
 * misses, the correction keeps being applied to the *current* raw fix and
 * decays toward zero over [ROAD_SNAP_GRACE_MISSES]. The pin therefore keeps
 * moving with the body (it never freezes on a stale anchor) and the release is
 * a glide, not a step.
 *
 * Sequential by contract — driven only from the `displayLocation` combine.
 */
internal class RoadSnapCarry(
    private val graceMisses: Int = ROAD_SNAP_GRACE_MISSES,
    private val maxOffsetM: Double = ROAD_SNAP_MAX_OFFSET_M,
    private val releaseSteps: Int = ROAD_SNAP_RELEASE_STEPS,
) {
    private var offLat = 0.0
    private var offLon = 0.0
    /** Consecutive misses since the last accepted snap; MAX = nothing carried. */
    private var misses = Int.MAX_VALUE

    val carrying: Boolean get() = misses != Int.MAX_VALUE

    fun reset() {
        offLat = 0.0
        offLon = 0.0
        misses = Int.MAX_VALUE
    }

    /**
     * [raw] the fused truth, [snap] the matcher's verdict this tick (null =
     * miss), [moving] the station-keeping gate. Returns what the pin and the
     * camera should both read — one source, every tick.
     */
    fun step(raw: LocationFix, snap: LocationFix?, moving: Boolean): LocationFix {
        if (!moving) {
            // A parked body owns its own position; drop any carried correction
            // so re-arming later starts from the truth rather than a memory.
            reset()
            return raw
        }
        if (snap != null) {
            val offM = local.phantom.companion.core.nav.haversineM(
                raw.lat to raw.lon,
                snap.lat to snap.lon,
            )
            if (offM > maxOffsetM) {
                // Not a correction — a disagreement. Trust the fused fix.
                reset()
                return raw
            }
            offLat = snap.lat - raw.lat
            offLon = snap.lon - raw.lon
            misses = 0
            return snap
        }
        if (misses == Int.MAX_VALUE) return raw // nothing carried yet
        misses++
        // HOLD, do not decay. A miss is MISSING DATA, not evidence that the
        // body returned to the raw fix — if the matcher said "8 m east" on the
        // ticks it answered, 8 m east is still the best estimate on the ticks
        // it did not. Decaying here is what makes an alternating hit/miss
        // pattern into a square wave, which is the lurch itself.
        if (misses <= graceMisses) {
            return raw.copy(lat = raw.lat + offLat, lon = raw.lon + offLon)
        }
        // Past the grace window the correction IS stale. Let go over a short
        // ramp rather than stepping, so the release is a glide.
        val k = 1.0 - (misses - graceMisses).toDouble() / (releaseSteps + 1).toDouble()
        if (k <= 0.0) {
            reset()
            return raw
        }
        return raw.copy(lat = raw.lat + offLat * k, lon = raw.lon + offLon * k)
    }
}

internal const val STILL_ENTER_MPS = 0.7f
// Release at a slow walk (≈3.2 km/h): a real pedestrian fix reads ~1.2–1.3 m/s.
// Instant release on automotive roll (500ms / 3m) eliminates the 10-20m turn lag.
internal const val STILL_EXIT_MPS = 0.9f

/** Below this a bearing is noise, not travel. */
internal const val HEADING_FLOOR_MPS = 0.9f

/** Low speed must persist this long before the puck freezes — long enough not
 *  to freeze on a rolling slowdown or yielding at turns. */
internal const val STILL_SUSTAIN_MS = 3_500L

/** While frozen and ALREADY moving (speed ≥ exit), a raw fix this far from the
 *  anchor confirms the two signals agree — real departure, release at once. */
internal const val STILL_TRAVEL_DISP_M = 3.0

/** Release when speed holds above [STILL_EXIT_MPS] for this long. */
internal const val STILL_RELEASE_CONFIRM_MS = 500L

/**
 * ARCH: PRECISION PIVOT (owner, 2026-07-21 — «головне щоб точка не ковзала і
 * була неймовірно точною, напрям теж»).
 *
 * The absolute stationary lock. A body that is demonstrably parked — speed under
 * [enterSpeedMps] sustained for [sustainMs] — has its displayed fix FROZEN at the
 * anchor: zero speed, a held heading, the same lat/lon every tick.
 *
 * ПОЛЬОВА ПОМИЛКА, 23.08 — release used to accept the fix's own Doppler speed as
 * proof of departure, and a spoofed fix carries speed like any other (the capture
 * claimed 6,5 m/s onto a parked desk). Speed alone can now only release the lock
 * when the body has no opinion; a body that is demonstrably STILL vetoes it. The
 * A56 doctrine is unchanged — the release still never rests on displacement alone,
 * because indoor multipath produces displacement without travel.
 */
internal class StationaryLock(
    private val enterSpeedMps: Float = STILL_ENTER_MPS,
    private val exitSpeedMps: Float = STILL_EXIT_MPS,
    private val headingFloorMps: Float = HEADING_FLOOR_MPS,
    private val sustainMs: Long = STILL_SUSTAIN_MS,
    private val travelDispM: Double = STILL_TRAVEL_DISP_M,
    private val releaseConfirmMs: Long = STILL_RELEASE_CONFIRM_MS,
    /**
     * true = the IMU says the platform is really moving, false = really still,
     * null = no evidence (no lease, no sensor, in between). Null keeps the
     * pre-23.08 behaviour exactly, so a device with no IMU is never frozen shut.
     */
    private val imuMoving: (() -> Boolean?)? = null,
) {
    private var frozen = false
    private var anchorLat = 0.0
    private var anchorLon = 0.0
    private var heldBearing: Float? = null

    /** When the current run of low speed began (0 = not currently low). */
    private var lowSince = 0L

    /** When the current run of clearly-moving speed began while frozen. */
    private var highSince = 0L

    val isFrozen: Boolean get() = frozen

    fun reset() {
        frozen = false
        lowSince = 0L
        highSince = 0L
        heldBearing = null
    }

    fun step(fix: LocationFix, nowMs: Long): LocationFix {
        val speed = fix.speedMps
        if (speed == null) return stepWithoutDoppler(fix, nowMs)

        if (frozen) {
            val wander = local.phantom.companion.core.nav.haversineM(
                anchorLat to anchorLon,
                fix.lat to fix.lon,
            )
            val movingFast = speed >= exitSpeedMps
            val leftTheSpot = wander > travelDispM
            if (movingFast) {
                if (highSince == 0L) highSince = nowMs
            } else {
                highSince = 0L
            }
            // The body's veto. Only a CONFIDENT stillness blocks a release; no
            // reading and no opinion both leave the old rules untouched.
            val bodyStill = imuMoving?.invoke() == false
            val travelNow = movingFast && leftTheSpot && !bodyStill
            val speedConfirmed = highSince != 0L &&
                (nowMs - highSince) >= releaseConfirmMs && !bodyStill
            if (!travelNow && !speedConfirmed) {
                return fix.copy(
                    lat = anchorLat,
                    lon = anchorLon,
                    speedMps = 0f,
                    bearing = heldBearing ?: fix.bearing,
                )
            }
            frozen = false
            lowSince = 0L
            highSince = 0L
        }

        if (speed < enterSpeedMps) {
            if (lowSince == 0L) lowSince = nowMs
            if (nowMs - lowSince >= sustainMs) {
                frozen = true
                anchorLat = fix.lat
                anchorLon = fix.lon
                return fix.copy(
                    lat = anchorLat,
                    lon = anchorLon,
                    speedMps = 0f,
                    bearing = heldBearing ?: fix.bearing,
                )
            }
        } else {
            lowSince = 0L
        }

        // Heading gate — above the walking floor the fix's own bearing flows
        // (FixFlux eases it) and becomes the new held heading; below it, hold the
        // last travel heading so a slow-walk or standstill never rotates the map.
        return if (speed >= headingFloorMps && fix.bearing != null) {
            heldBearing = fix.bearing
            fix
        } else {
            fix.copy(bearing = heldBearing ?: fix.bearing)
        }
    }

    /**
     * Гілка без Doppler. Швидкості немає — судити про стоянку нема чим, тож:
     *
     *  • незамерзлий стан НЕ замерзає (лічильник низької швидкості скидаємо,
     *    щоб пропуск кількох фіксів посеред руху не накопичував «стоянку»);
     *  • замерзлий стан звільняється по ЗМІЩЕННЮ — тут це єдиний доступний
     *    сигнал. Поріг навмисно грубий: скасована колись «сітка на 40 м»
     *    зривалася на кімнатному багатопроменні, але там швидкість приходила
     *    рівно 0.0, і сюди така епоха не потрапляє взагалі.
     */
    private fun stepWithoutDoppler(fix: LocationFix, nowMs: Long): LocationFix {
        lowSince = 0L
        if (!frozen) return fix.copy(bearing = heldBearing ?: fix.bearing)

        val wander = local.phantom.companion.core.nav.haversineM(
            anchorLat to anchorLon,
            fix.lat to fix.lon,
        )
        if (wander <= BLIND_RELEASE_DISP_M || imuMoving?.invoke() == false) {
            if (highSince == 0L) highSince = nowMs
            return fix.copy(
                lat = anchorLat,
                lon = anchorLon,
                speedMps = 0f,
                bearing = heldBearing ?: fix.bearing,
            )
        }
        frozen = false
        highSince = 0L
        return fix.copy(bearing = heldBearing ?: fix.bearing)
    }
}

/** Звільнення замку, коли швидкості немає взагалі. Втричі більше за
 *  [STILL_TRAVEL_DISP_M]: без Doppler єдиний доказ руху — відстань, і вона
 *  має бути завелика для будь-якого розкиду стоянки. */
internal const val BLIND_RELEASE_DISP_M = 45.0

/** APPLE-LOCK — a NEW lock engages only this close to the snap… */
internal const val GUIDANCE_LOCK_ENGAGE_M = 45.0

/** …and an ENGAGED lock holds until the gap opens past this. Two thresholds
 *  on purpose: one boundary made a pin hovering at the corridor's edge flap
 *  raw↔snap on every fix — the exact wobble the lock exists to kill. */
internal const val GUIDANCE_LOCK_RELEASE_M = 60.0

/** Нижче цього напрямок — шум, а не курс: пішохідний крок і тремтіння GPS
 *  дають «напрямок» нерухомому телефону (див. памʼять дому про слід). */
internal const val GUIDANCE_LOCK_MIN_SPEED_MPS = 1.5f

/** Розбіжність курсу з коридором, за якою замок відпускає.
 *
 *  Свідомо ТОЙ САМИЙ поріг, що й у наведення ([OFF_ROUTE_HEADING_DEG]): пін і
 *  сесія відповідають на одне питання — «чи я взагалі на цій дорозі», — і два
 *  різні числа тут означали б, що пін і банер сперечаються про факт. */
internal const val GUIDANCE_LOCK_HEADING_RELEASE_DEG =
    local.phantom.companion.core.nav.OFF_ROUTE_HEADING_DEG

/**
 * APPLE-LOCK — the pure lock decision (see [MapViewModel.displayLocation]).
 * Returns the route-snapped display fix, or null when the lock must let go:
 * no drive, a blind engine, an off-route verdict, or a raw fix farther from
 * the snap than the hysteresis allows (a held progress while the raw
 * estimate runs away must not glue the pin to a corridor the operator left).
 * [wasLocked] is the previous frame's verdict — it picks which threshold
 * applies.
 */
internal fun guidanceLockedFix(
    raw: LocationFix?,
    guiding: Boolean,
    blind: Boolean,
    progress: local.phantom.companion.core.nav.GuidanceProgress?,
    wasLocked: Boolean = false,
): LocationFix? {
    if (raw == null || !guiding || blind) return null
    val p = progress ?: return null
    if (p.offRoute) return null
    val offM = local.phantom.companion.core.nav.haversineM(
        raw.lat to raw.lon,
        p.snappedLat to p.snappedLon,
    )
    if (offM > (if (wasLocked) GUIDANCE_LOCK_RELEASE_M else GUIDANCE_LOCK_ENGAGE_M)) return null

    // Скарга власника, дослівно: «їде іншою дорогою — притягує на ту, де
    // маршрут», і «каже "поверніться" та показує, що я їду в той бік, хоча це
    // не так». Відстань цього не ловила: сусідня вулиця за 25 м від коридору
    // ближча за поріг, тож замок тримав людину на дорозі, якою вона не їде.
    // Напрямок ловить: той, хто їде ПОПЕРЕК коридору, на ньому не перебуває.
    val moving = (raw.speedMps ?: 0f) >= GUIDANCE_LOCK_MIN_SPEED_MPS
    val motionBearing = raw.bearing?.toDouble()?.takeIf { moving }
    if (motionBearing != null &&
        headingGapDeg(motionBearing, p.bearingDeg) > GUIDANCE_LOCK_HEADING_RELEASE_DEG
    ) {
        return null
    }
    // Позиція лишається зшитою — це чесна умовність мапи, пін на дорозі.
    // А напрямок віддаємо РУХОВІ: показувати напрямок дороги замість власного
    // означає розповідати людині, що вона їде туди, куди вона не їде. Коли рух
    // надто повільний, щоб мати напрямок, коридор лишається кращим за шум.
    return raw.copy(
        lat = p.snappedLat,
        lon = p.snappedLon,
        bearing = (motionBearing ?: p.bearingDeg).toFloat(),
    )
}

/** Той самий кут, що й у наведення — з `core-nav`, не власна копія: дві копії
 *  однієї математики розходяться тихо. */
internal fun headingGapDeg(a: Double, b: Double): Double =
    local.phantom.companion.core.nav.headingGapDeg(a, b)

/** Стеля перебудови шару контактів: раз на секунду, хоч би як часто писали. */
private const val PEER_RENDER_TICK_MS = 1_000L

internal fun shortEta(seconds: Double): String {
    val m = (seconds / 60.0).toInt().coerceAtLeast(1)
    return if (m < 60) {
        MapText.s(R.string.mapl_f_eta_min, m)
    } else {
        MapText.s(R.string.mapl_f_eta_hr_min, m / 60, m % 60)
    }
}

/** «Поруч» не питає сервер знову, доки людина не відійшла на стільки… */
private const val AROUND_MOVE_M = 300.0

/** …або доки не минуло стільки. */
private const val AROUND_TTL_MS = 5 * 60_000L
