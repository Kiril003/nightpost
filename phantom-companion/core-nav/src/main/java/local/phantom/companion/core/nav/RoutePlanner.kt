package local.phantom.companion.core.nav

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import local.phantom.companion.core.data.transit.GlobalTransitRouter.Outcome as GlobalTransitOutcome
import local.phantom.companion.core.net.map.OsrmClient
import local.phantom.companion.core.net.map.RouteIntel
import local.phantom.companion.core.net.map.RouteIntelReport
import local.phantom.companion.core.net.map.RouteProfile
import local.phantom.companion.core.net.map.RouteVariant

/**
 * Why a non-TRANSIT [RoutePlanner.plan] found no road, named at the only
 * layer that actually knows: [routeGraphFirst]'s two legs. The UI maps each
 * to its own sentence — this module has no strings resource of its own.
 */
enum class RoadRefusal {
    /** On-device mesh refused outright: corridor wider than it will ever load. */
    TOO_LONG,
    /** The mesh's own DB read/build threw. */
    GRAPH_UNREADABLE,
    /** Mesh built fine and holds nothing for this corridor, or found no path in it. */
    NO_DATA,
    /** A network router IS configured and answered — with no route. */
    NETWORK_NO_ROUTE,

    /**
     * Пошук на пристрої впрів у власну стелю розкриттів — дорога може
     * існувати, її просто не шукали далі (`RoadGraph.Refusal.EXHAUSTED`).
     *
     * Окреме значення, а не [NO_DATA], бо це різні хвороби з різними ліками:
     * [NO_DATA] лікується дорожнім пакетом, а це — коротшою поїздкою або
     * сервером маршрутів. Заміряно 16.09.2026: до підняття стелі чотири з
     * шести звичайних поїздок по Києву падали саме сюди й діставали пораду
     * «потрібен дорожній пакет» при вже встановленому пакеті на 54 МБ.
     */
    SEARCH_CEILING,

    /**
     * Старт або мета не лягли на жодну дорогу в радіусі
     * `LocalRouter.SNAP_RADIUS_M` — точка стоїть далі за 120 м від усього, що
     * пакет вважає дорогою. Дорожній пакет тут ні до чого, і радити його —
     * означає відправити людину качати те, що в неї вже є.
     */
    OFF_ROAD,

    /**
     * Ні доріг на пристрої, ні свого сервера маршрутів. Донедавна цього стану
     * не існувало: позаду налаштованої основи стояв публічний OSRM, тож
     * «мережі немає» не траплялось — траплявся витік, бо OSRM кладе обидві
     * точки, початок і ціль, у шлях адреси. Тепер відмова справжня, і вона
     * мусить вести до ліку: [NO_DATA] радить дорожній пакет, а це — і пакет,
     * і те, що мережевого шляху свідомо немає.
     */
    NO_PACK_NO_SERVER,
}

/**
 * Уточнює відмову графа тим, чого граф не знає: чи є взагалі куди піти далі.
 *
 * Окреме значення, а не перевантаження [RoadRefusal.NO_DATA]: «нема доріг на
 * пристрої» при налаштованому сервері й без нього — різні світи з різними
 * ліками. [RoadRefusal.OFF_ROAD], [RoadRefusal.SEARCH_CEILING] і
 * [RoadRefusal.TOO_LONG] лишаються собою — вони називають СВОЮ причину, і
 * відсутність сервера її не скасовує.
 */
fun refusalWithoutRouter(meshRefusal: RoadRefusal?, ownRouterConfigured: Boolean): RoadRefusal? =
    if (!ownRouterConfigured && (meshRefusal == null || meshRefusal == RoadRefusal.NO_DATA)) {
        RoadRefusal.NO_PACK_NO_SERVER
    } else {
        meshRefusal
    }

/**
 * NAV-1 — the route-plan session behind [MapViewModel]: fetches OSRM
 * variants (steps + alternatives + via-waypoints), keeps the operator's
 * selection, and enriches every variant with a [RouteIntel] corridor
 * report in the background (one at a time — Overpass and open-elevation
 * are shared public instances). The ViewModel stays the single publisher
 * of map polylines; the compare sheet reads [options] directly.
 */
class RoutePlanner(
    private val scope: CoroutineScope,
    private val osrm: OsrmClient = OsrmClient(),
    private val intel: RouteIntel = RouteIntel(),
    /** Lazy: the fused timetable build is heavy and belongs to a background
     *  pre-warm, not to whichever thread constructs the planner. */
    private val multimodalPlanner: Lazy<local.phantom.companion.core.data.transit.MultimodalPlanner?>? = null,
    /** Global transit router (MOTIS / Transitous open data) for navigation outside baked packs. */
    private val globalTransitRouter: local.phantom.companion.core.data.transit.GlobalTransitRouter? = null,
    private val worldScheduleAllowed: () -> Boolean = { local.phantom.companion.core.net.map.GeoEndpoints.config.worldTransitOptIn },
    /**
     * ATLAS-4 — the variant source behind the cache; injectable so tests can
     * count round-trips. Null = OSRM verbatim.
     */
    routeSource: (suspend (RouteProfile, List<Pair<Double, Double>>) -> List<RouteVariant>?)? = null,
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** Read right after [routeSource] refuses a non-TRANSIT plan — whoever
     *  built [routeSource] is the only one who knows which leg said no and
     *  why. Null when nobody wired one (falls through to the generic line). */
    private val routeRefusal: (() -> RoadRefusal?)? = null,
) {
    private val routeSource: suspend (RouteProfile, List<Pair<Double, Double>>) -> List<RouteVariant>? =
        routeSource ?: { profile, waypoints -> osrm.routeDetailed(profile, waypoints) }

    private val parkAndWalkPlanner: local.phantom.companion.core.nav.multimodal.ParkAndWalkPlanner =
        local.phantom.companion.core.nav.multimodal.ParkAndWalkPlanner(osrm)

    /** Corridor-analysis lifecycle of one option — honest tri-state. */
    sealed interface Intel {
        data object Loading : Intel
        data class Ready(val report: RouteIntelReport) : Intel
        data object Unavailable : Intel
    }

    data class Option(val variant: RouteVariant, val intel: Intel)

    private val _options = MutableStateFlow<List<Option>>(emptyList())
    val options: StateFlow<List<Option>> = _options.asStateFlow()

    private val _selectedIndex = MutableStateFlow(0)
    val selectedIndex: StateFlow<Int> = _selectedIndex.asStateFlow()

    /** start, via…, destination of the live plan (empty = no plan). */
    var waypoints: List<Pair<Double, Double>> = emptyList()
        private set
    var profile: RouteProfile = RouteProfile.FOOT
        private set

    /** Why the last non-TRANSIT [plan] found no road — the same idiom as
     *  [transitFailureReason], one profile family over. Lives after a
     *  successful plan too until the next attempt overwrites it. */
    var roadFailureReason: RoadRefusal? = null
        private set

    private var intelJob: Job? = null

    val selectedVariant: RouteVariant?
        get() = _options.value.getOrNull(_selectedIndex.value)?.variant

    // ── ATLAS-4: the route cache ──────────────────────────────────────────
    //
    // A re-plan over the same pins inside the TTL is 0 ms and 0 requests, and
    // `prefetch` lets the UI start the round-trip the moment a destination
    // pin drops — by the time the operator's finger reaches «маршрут», the
    // answer is already local. Keys quantize waypoints to ~11 m so a
    // hair-different long-press is still the same intent. Everything here is
    // confined to [scope]'s dispatcher (main in production) — no locks, same
    // single-writer doctrine as the rest of this class.

    private data class RouteKey(val profile: RouteProfile, val cells: List<Long>)
    private class CachedVariants(val variants: List<RouteVariant>, val atMs: Long)

    private val routeCache = LinkedHashMap<RouteKey, CachedVariants>()
    private val routeInFlight = HashMap<RouteKey, kotlinx.coroutines.Deferred<List<RouteVariant>?>>()

    private fun keyOf(waypoints: List<Pair<Double, Double>>, profile: RouteProfile) = RouteKey(
        profile,
        waypoints.map { (lat, lon) ->
            (Math.round(lat * 1e4) shl 32) xor (Math.round(lon * 1e4) and 0xFFFFFFFFL)
        },
    )

    /**
     * Cached / single-flighted variant fetch. A fresh cache entry returns
     * instantly; a request already in flight is joined, never duplicated
     * (the public OSRM instance is a guest resource, not a private server).
     */
    private suspend fun fetchVariants(
        waypoints: List<Pair<Double, Double>>,
        profile: RouteProfile,
    ): List<RouteVariant>? {
        val key = keyOf(waypoints, profile)
        val inFlight: kotlinx.coroutines.Deferred<List<RouteVariant>?> = synchronized(routeCache) {
            routeCache[key]?.let { hit ->
                if (nowMs() - hit.atMs <= CACHE_TTL_MS) return hit.variants
                routeCache.remove(key)
            }
            routeInFlight[key]?.let { return@synchronized it }
            val deferred = scope.async { routeSource(profile, waypoints) }
            routeInFlight[key] = deferred
            deferred
        }
        return try {
            val variants = inFlight.await()
            if (variants != null) {
                synchronized(routeCache) {
                    routeCache[key] = CachedVariants(variants, nowMs())
                    while (routeCache.size > CACHE_MAX) routeCache.remove(routeCache.keys.first())
                }
            }
            variants
        } finally {
            synchronized(routeCache) {
                routeInFlight.remove(key)
            }
        }
    }

    /**
     * ATLAS-4 — speculative warm-up: fire the fetch for a plan the operator
     * is *about* to ask for, so the eventual [plan] is a cache hit. Fire-and-
     * forget; failures stay silent (the honest notice belongs to the real
     * plan, not the speculation). TRANSIT plans are computed on-device and
     * need no warm-up.
     */
    fun prefetch(waypoints: List<Pair<Double, Double>>, profile: RouteProfile) {
        if (profile == RouteProfile.TRANSIT || waypoints.size < 2) return
        scope.launch { runCatching { fetchVariants(waypoints, profile) } }
    }

    /**
     * Fetch a fresh plan. On success replaces the session and returns the
     * selected (first/fastest) variant; on failure returns null and leaves
     * the previous session intact, so a failed «через цю точку» replan
     * doesn't wipe a perfectly good active route.
     */
    suspend fun plan(
        waypoints: List<Pair<Double, Double>>,
        profile: RouteProfile,
        /** TRANSIT: явний час виїзду, секунди від опівночі. Null — «зараз». */
        departSec: Int? = null,
    ): RouteVariant? {
        if (profile == RouteProfile.TRANSIT) {
            val from = waypoints.first()
            val to = waypoints.last()

            // Колишній нічний підмін на 07:30 знято: час виїзду тепер обирає
            // оператор ручкою, а не планувальник за нього.
            val now = java.util.Calendar.getInstance()
            val timeSec = departSec ?: (
                now.get(java.util.Calendar.HOUR_OF_DAY) * 3600 +
                    now.get(java.util.Calendar.MINUTE) * 60 +
                    now.get(java.util.Calendar.SECOND)
                )

            val mm = runCatching { multimodalPlanner?.value }.getOrNull()
            // Чип «транзит» не припускає авто (вирок власника: «якщо транспорт —
            // у людини немає авто»), тож park&ride-насіння вимкнено: інакше
            // перша нога відповіді могла бути «їдьте машиною до метро».
            var plan = mm?.plan(
                from.first, from.second, to.first, to.second,
                local.phantom.companion.core.data.transit.MultimodalPlanner.Options(departSec = timeSec, allowCar = false),
            )

            var itineraries = plan?.itineraries.orEmpty()
            val router = globalTransitRouter
            // Null — світового розкладу не питали взагалі (його тут нема, або
            // локальний пак уже відповів).
            var global: GlobalTransitOutcome? = null
            if (itineraries.isEmpty() && router != null && worldScheduleAllowed()) {
                global = runCatching {
                    router.planTransit(from.first, from.second, to.first, to.second, departSec = timeSec)
                }.getOrElse { GlobalTransitOutcome.Unreachable }
                itineraries = (global as? GlobalTransitOutcome.Planned)?.itineraries.orEmpty()
            }

            itineraries = local.phantom.companion.core.data.transit.ConnectionRisk.saferFirst(
                itineraries,
                local.phantom.companion.core.data.transit.TransitDelayMemory.shared,
            )
            this.waypoints = waypoints
            this.profile = RouteProfile.TRANSIT
            this.transitItineraries = itineraries

            if (itineraries.isNotEmpty()) {
                transitFailureReason = null
                val variants = itineraries.map(::transitVariant)
                _selectedIndex.value = 0
                _options.value = variants.map { Option(it, Intel.Unavailable) }
                return variants.first()
            }

            // Причина — ДО запасного плану: вона потрібна й тоді, коли пішки
            // прокладеться, щоб UI сказав, чому чип «транзит» показує не транзит.
            //
            // Порядок гілок і є полагодженою вадою. Раніше першою стояла
            // `globalTransitRouter != null`, а в продакшені він не-null ЗАВЖДИ,
            // тож чотири нижні гілки були мертвим кодом, і людина без мережі
            // чула «маршрутів не знайдено» — заяву про світ замість зізнання
            // про себе. Тепер першим говорить той, хто справді знає: локальний
            // пак, коли він покриває цей район, інакше світовий розклад.
            val nearKm = mm?.nearestStopMeters(from.first, from.second)?.div(1000.0)
            transitFailureReason = when {
                nearKm != null && nearKm > 0.9 -> TransitFailure.FarFromStop(nearKm)
                nearKm != null -> TransitFailure.NoTripsAt(timeSec)
                global == GlobalTransitOutcome.Unreachable -> TransitFailure.Unreachable
                global == GlobalTransitOutcome.NoService -> TransitFailure.NoTripsAt(timeSec)
                else -> TransitFailure.NoSchedule
            }

            // Запасний план — лише ПІШКИ. Раніше >2,5 км мовчки давав АВТО:
            // людина, що просила транспорт, діставала автомобільний маршрут,
            // якого не може виконати. Ноги є в кожного; авто — за чипом.
            // І лише для «зараз»: хто явно обрав час, спитав про рейси, і мовчазний
            // піший маршрут замість «о цій годині рейсів немає» відповідав би на інше питання.
            val fallbackVariants = if (departSec == null) fetchVariants(waypoints, RouteProfile.FOOT) else null
            if (!fallbackVariants.isNullOrEmpty()) {
                this.waypoints = waypoints
                this.profile = RouteProfile.FOOT
                this.transitItineraries = emptyList()
                _selectedIndex.value = 0
                _options.value = fallbackVariants.map { Option(it, Intel.Loading) }
                enrich(fallbackVariants, waypoints, RouteProfile.FOOT)
                return fallbackVariants.first()
            }

            _options.value = emptyList()
            _selectedIndex.value = 0
            return null
        }

        // ATLAS-4 — through the cache: a prefetched or recently-planned
        // corridor lands in 0 ms with 0 requests.
        val standardVariants = fetchVariants(waypoints, profile)
        if (standardVariants == null) {
            roadFailureReason = routeRefusal?.invoke()
            return null
        }

        roadFailureReason = null
        this.waypoints = waypoints
        this.profile = profile
        transitItineraries = emptyList()
        _selectedIndex.value = 0
        _options.value = standardVariants.map { Option(it, Intel.Loading) }

        // Non-blocking enrichment: analyze corridor and discover Park & Walk in background
        enrich(standardVariants, waypoints, profile)
        return standardVariants.first()
    }

    /** The transit legs behind option [index], or null off a transit plan. */
    fun itineraryFor(index: Int): local.phantom.companion.core.data.transit.Itinerary? =
        transitItineraries.getOrNull(index)

    /** Why the last TRANSIT plan found no transit. Живе й після вдалого
     *  ПІШОГО запасного плану — саме тоді UI мусить сказати, чому не транзит. */
    var transitFailureReason: TransitFailure? = null
        private set

    private var transitItineraries: List<local.phantom.companion.core.data.transit.Itinerary> = emptyList()

    /**
     * One itinerary as a drawable route: access legs contribute their road
     * geometry, rides their stop-chain path (a metro line that bends where
     * the tunnel does — not a straight slash across the city), and the
     * distance counts what is actually travelled, rides included.
     */
    private fun transitVariant(
        itin: local.phantom.companion.core.data.transit.Itinerary,
    ): RouteVariant {
        val points = mutableListOf<Pair<Double, Double>>()
        var totalDist = 0.0
        for (leg in itin.legs) {
            when (leg) {
                is local.phantom.companion.core.data.transit.Itinerary.Leg.Access -> {
                    if (leg.points.isNotEmpty()) {
                        points.addAll(leg.points)
                    } else {
                        points.add(leg.fromLat to leg.fromLon)
                        points.add(leg.toLat to leg.toLon)
                    }
                    totalDist += leg.meters
                }
                is local.phantom.companion.core.data.transit.Itinerary.Leg.Ride -> {
                    val ride = leg.points.ifEmpty {
                        listOf(leg.fromLat to leg.fromLon, leg.toLat to leg.toLon)
                    }
                    points.addAll(ride)
                    totalDist += ride.zipWithNext().sumOf { (a, b) -> haversineM(a, b) }
                }
                is local.phantom.companion.core.data.transit.Itinerary.Leg.Transfer -> Unit
            }
        }
        return RouteVariant(
            points = points,
            distanceMeters = totalDist,
            durationSeconds = itin.durationSec.toDouble(),
            steps = TransitSteps.of(itin),
            speedsMps = emptyList(),
            profile = RouteProfile.TRANSIT,
        )
    }

    fun select(index: Int): RouteVariant? {
        val opt = _options.value.getOrNull(index) ?: return null
        _selectedIndex.value = index
        return opt.variant
    }

    fun clear() {
        intelJob?.cancel()
        waypoints = emptyList()
        profile = RouteProfile.FOOT
        transitItineraries = emptyList()
        _options.value = emptyList()
        _selectedIndex.value = 0
    }

    private fun enrich(
        variants: List<RouteVariant>,
        waypoints: List<Pair<Double, Double>>,
        profile: RouteProfile,
    ) {
        intelJob?.cancel()
        intelJob = scope.launch {
            // Asynchronously discover Park & Walk for car routes without delaying initial route display!
            if (profile == RouteProfile.CAR && waypoints.size == 2) {
                val pawVariant = runCatching {
                    parkAndWalkPlanner.planParkAndWalk(waypoints.first(), waypoints.last())
                }.getOrNull()
                if (pawVariant != null) {
                    _options.update { current ->
                        if (this@RoutePlanner.profile == profile && this@RoutePlanner.waypoints == waypoints) {
                            listOf(Option(pawVariant, Intel.Unavailable)) + current.filter { it.variant.parkAndWalk == null }
                        } else current
                    }
                }
            }

            variants.forEachIndexed { i, v ->
                val report = runCatching { intel.analyze(v.points) }.getOrNull()
                _options.update { opts ->
                    opts.mapIndexed { j, o ->
                        if (j == i && o.variant === v) {
                            o.copy(intel = report?.let { Intel.Ready(it) } ?: Intel.Unavailable)
                        } else o
                    }
                }
            }
        }
    }

    private companion object {
        /** ATLAS-4 — OSRM's public instance carries no live traffic; three
         *  minutes of reuse changes nothing about the answer. */
        const val CACHE_TTL_MS = 3 * 60_000L
        const val CACHE_MAX = 12
    }
}

sealed interface TransitFailure {
    data class FarFromStop(val km: Double) : TransitFailure
    data class NoTripsAt(val departSec: Int) : TransitFailure
    data object Unreachable : TransitFailure
    data object NoSchedule : TransitFailure
}
