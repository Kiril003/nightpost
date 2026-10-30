package local.phantom.companion.feature.stream.ui.map

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import local.phantom.companion.core.nav.DeadlineWatch
import local.phantom.companion.core.nav.GuidanceController
import local.phantom.companion.core.nav.GuidanceState
import local.phantom.companion.core.nav.NavCommand
import local.phantom.companion.core.nav.NavigationEngine
import local.phantom.companion.core.nav.RoadRefusal
import local.phantom.companion.core.nav.RoutePlanner
import local.phantom.companion.core.net.map.RouteProfile
import local.phantom.companion.core.net.map.RouteVariant
import local.phantom.companion.feature.stream.R
import local.phantom.companion.feature.stream.ui.widget.MapPayload

/**
 * The drive half of the old MapViewModel monolith, extracted verbatim
 * (500-line rule): route planning over the process-scoped
 * [NavigationEngine], the published polylines/route-info, the NAV-Ω1
 * arrival deadline, and PHANTOM's nav.* verbs.
 *
 * HIGHWAY DOMINANCE — the drive is not this coordinator's to own either.
 * The engine is injected, process-scoped, and shared with the car; this
 * class is an *observer* of it. It still owns the polylines and drives the
 * plan — but it cannot end a drive by being destroyed.
 *
 * [start] must be called only after the whole ViewModel exists — its
 * collectors reach [syncThreatWatch], which reads sibling coordinators.
 * Same discipline, same reason as [MapLiveSession.start].
 */
internal class MapRouteCoordinator(
    private val scope: CoroutineScope,
    private val engine: NavigationEngine,
    private val postNotice: (String) -> Unit,
    /** `live.syncThreatWatch()` — NAV-5's watcher follows the guided corridor. */
    private val syncThreatWatch: () -> Unit,
    /** NAV-2 — nav.* verbs from PHANTOM's hand. replay=1 + reset-after-handle. */
    private val navCommands: MutableSharedFlow<NavCommand>?,
) {
    private val planner: RoutePlanner get() = engine.routePlanner
    internal val guidance: GuidanceController get() = engine.guidance
    private val indoorConnector = local.phantom.companion.core.geo.indoor.IndoorRouteConnector()

    /**
     * W-2 — the spoken queue (maneuvers, NAV-5 threats, NAV-Ω1 deadline
     * bands). The shell collects it at the Continuum, so the voice keeps
     * leading the drive from every stratum.
     */
    val voiceCues: SharedFlow<local.phantom.companion.core.nav.NavCue> = guidance.voiceCues

    /** ON-TIME VOICE — upcoming final-cue texts for TTS prewarming. */
    val voicePrewarms: SharedFlow<String> = guidance.prewarms

    /** W-4 — a live drive, for the shell's keep-screen-on and the depth gates. */
    val isGuiding: StateFlow<Boolean> = guidance.state
        .map { it is GuidanceState.Guiding }
        .stateIn(scope, SharingStarted.Eagerly, false)

    private val _routes = MutableStateFlow<List<MapPayload.PolylineData>>(emptyList())
    val routes: StateFlow<List<MapPayload.PolylineData>> = _routes.asStateFlow()

    private val _maneuvers = MutableStateFlow<List<ManeuverPins.Pin>>(emptyList())

    /** Where the operator will have to act, for the markers on the line. */
    val maneuvers: StateFlow<List<ManeuverPins.Pin>> = _maneuvers.asStateFlow()

    private val _laneBands = MutableStateFlow<List<local.phantom.companion.feature.stream.ui.map.nav.lanes.LaneBand>>(emptyList())

    /** PRESENCE — lane geometry, baked once per plan; empty where OSM is untagged. */
    val laneBands: StateFlow<List<local.phantom.companion.feature.stream.ui.map.nav.lanes.LaneBand>> =
        _laneBands.asStateFlow()

    private val _routeInfo = MutableStateFlow<MapViewModel.RouteInfo?>(null)
    val routeInfo: StateFlow<MapViewModel.RouteInfo?> = _routeInfo.asStateFlow()

    /**
     * Відмова у формі, придатній до показу.
     *
     * Досі провал маршруту йшов у [postNotice] — пігулкою, яка спливає і
     * зникає. Людина, що стоїть із телефоном і не має маршруту, лишалась ні з
     * чим: ані причини, ані напрямку. Смуга відмови була написана, але її
     * ніхто не будував; тепер її будують тут, і це єдине місце.
     */
    private val _routeFailure = MutableStateFlow<RouteFailure?>(null)
    val routeFailure: StateFlow<RouteFailure?> = _routeFailure.asStateFlow()

    private val _routing = MutableStateFlow(false)
    val routing: StateFlow<Boolean> = _routing.asStateFlow()

    /** Час виїзду для TRANSIT, секунди від опівночі. Null — «зараз». */
    private val _transitDepartSec = MutableStateFlow<Int?>(null)
    val transitDepartSec: StateFlow<Int?> = _transitDepartSec.asStateFlow()

    /**
     * Ручка «виїхати о…». Живе тут, а не в планувальнику: планувальник
     * читає її як параметр кожного plan(), тож стан не може розійтись із
     * тим, що на екрані. Перепланування лише коли транзитна сесія жива —
     * на профілі АВТО тиха зміна маршруту була б сюрпризом.
     */
    fun setTransitDeparture(sec: Int?) {
        if (_transitDepartSec.value == sec) return
        _transitDepartSec.value = sec
        val wp = planner.waypoints
        if (wp.size >= 2 && planner.profile == RouteProfile.TRANSIT) {
            planWaypoints(wp, RouteProfile.TRANSIT)
        }
    }

    /**
     * W-3 / NAV-Ω1 — the active corridor for `route.along`, published here
     * because «що по дорозі» is asked from the CHAT: a composition-scoped
     * publisher nulled this on ascent.
     */
    private val _activeRoutePoints = MutableStateFlow<List<Pair<Double, Double>>?>(null)
    val activeRoutePoints: StateFlow<List<Pair<Double, Double>>?> = _activeRoutePoints.asStateFlow()

    /** NAV-1 — route variants with corridor intel, for the compare sheet. */
    val routeOptions: StateFlow<List<RoutePlanner.Option>> = planner.options

    /** NAV-1 — which variant the operator picked (index into [routeOptions]). */
    val routeSelectedIndex: StateFlow<Int> = planner.selectedIndex

    /** Active route waypoints (origin, intermediate via stops, destination). */
    val waypoints: List<Pair<Double, Double>> get() = planner.waypoints

    /**
     * Wire the engine's revisions, guidance state and nav.* verbs. Called
     * from the ViewModel's init block, after every sibling exists.
     */
    fun start() {
        // NAV-5 — the watcher follows the guided corridor; NAV-Ω1 — every ETA
        // sample runs against the deadline, and a band transition speaks.
        scope.launch {
            guidance.state.collect { state ->
                syncThreatWatch()
                val remaining = (state as? GuidanceState.Guiding)
                    ?.progress?.remainingDurationS ?: return@collect
                onDeadlineEstimate(remaining)?.let { spoken ->
                    guidance.announce(spoken)
                    postNotice(spoken)
                }
            }
        }

        // HIGHWAY DOMINANCE — a plan can be replaced by a head this ViewModel
        // knows nothing about (the car's «start», a background reroute).
        // Redraw whenever the drive underneath moves, no matter who moved it.
        engine.planRevision.onEach { publishPlan() }.launchIn(scope)
        scope.launch { engine.notices.collect { postNotice(it) } }

        // NAV-2 — PHANTOM's nav.* verbs, handled off-composition so a command
        // spoken from the chat doesn't wait for a map to exist. Start/Stop
        // belong to the process-scoped engine (AppContainer drives it); the
        // one verb that is a map-surface concern is the arrival deadline.
        val commands = navCommands
        if (commands != null) {
            scope.launch {
                commands.collect { cmd ->
                    when (cmd) {
                        is NavCommand.Deadline -> {
                            val ms = cmd.epochMs
                            if (ms != null) setDeadline(ms) else clearDeadline()
                        }
                        is NavCommand.Start, NavCommand.Stop -> Unit // the engine's, not the map's
                    }
                    commands.resetReplayCache()
                }
            }
        }
    }

    /**
     * M-B01/NAV-1 — plan a route from → to via OSRM (with alternatives +
     * corridor intel) and publish the selected variant's distance/ETA.
     * On failure emits an honest notice and leaves the map route-free.
     */
    fun routeTo(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        profile: RouteProfile = RouteProfile.FOOT,
        toName: String? = null,
    ) {
        destName = toName
        planWaypoints(listOf(fromLat to fromLon, toLat to toLon), profile)
    }

    /**
     * Як зветься мета поточного маршруту. Живе тут, а не в параметрах кожної
     * перебудови: зміна режиму («пішки» → «авто») і вибір іншого варіанта не
     * змінюють мету, і змушувати кожен такий виклик знову нести назву означало б
     * втрачати її на першому ж, хто забув.
     */
    private var destName: String? = null

    /**
     * `planner.waypoints` only updates on a SUCCESSFUL [RoutePlanner.plan] —
     * a failed non-TRANSIT attempt must not overwrite a route the map still
     * shows (see the planner's own doc). But that means a route whose very
     * FIRST attempt fails leaves `planner.waypoints` empty forever, and
     * every profile chip on the failure band read that empty list and
     * quietly did nothing: «авто» tapped, «транзит» stayed selected. This
     * remembers the destination independently of whether the planner kept
     * it, so a retry always has somewhere to retry to.
     */
    private var lastAttempt: List<Pair<Double, Double>>? = null

    /**
     * The same silence one field over: `planner.profile` is written on the
     * same success, so after a first-attempt failure it still reads the
     * default «пішки» — and every edit below that replans «with the current
     * profile» would quietly turn the operator's «авто» into a walk.
     */
    private var lastAttemptProfile: RouteProfile? = null

    /**
     * The route an edit applies to. The planner's own wins when it kept one —
     * a live route on the map outranks a failed replan over it; that is the
     * planner's contract — else the last attempt. Null: nothing was ever
     * asked for, so there is nothing to edit.
     */
    private fun editableWaypoints(): List<Pair<Double, Double>>? =
        planner.waypoints.takeIf { it.size >= 2 } ?: lastAttempt

    /** The profile that goes with [editableWaypoints]: the planner's when it kept the route, else the attempted one. */
    private fun editableProfile(): RouteProfile =
        if (planner.waypoints.size >= 2) planner.profile else lastAttemptProfile ?: planner.profile

    /** The route is gone (cleared, or replaced by an evacuation): nothing may resurrect it. */
    private fun forgetAttempt() {
        lastAttempt = null
        lastAttemptProfile = null
    }

    /** Re-run the active route with a different travel profile (M-B02), keeping via-points. */
    fun reRouteProfile(fromLat: Double, fromLon: Double, profile: RouteProfile) {
        val wp = editableWaypoints() ?: return
        planWaypoints(listOf(fromLat to fromLon) + wp.drop(1), profile)
    }

    /**
     * NAV-1 (M-B05) — thread the active route through one more point: the
     * long-press sheet's «через цю точку». A failed replan keeps the
     * current route on the map (planner leaves the old session intact).
     */
    fun addViaPoint(lat: Double, lon: Double) {
        val wp = editableWaypoints() ?: return
        planWaypoints(wp.dropLast(1) + (lat to lon) + wp.takeLast(1), editableProfile())
    }

    /** Remove an intermediate waypoint by index (1 until size - 1) and replan. */
    fun removeViaPoint(index: Int) {
        val wp = editableWaypoints() ?: return
        if (wp.size <= 2 || index <= 0 || index >= wp.size - 1) return
        val filtered = wp.filterIndexed { i, _ -> i != index }
        planWaypoints(filtered, editableProfile())
    }

    /** Reorder waypoints preserving minimum 2 endpoints. */
    fun reorderWaypoints(newOrder: List<Pair<Double, Double>>) {
        if (newOrder.size < 2) return
        // Brings its own waypoints, so it never hit the empty-list guard —
        // but it did read the default profile after a failed first attempt.
        planWaypoints(newOrder, editableProfile())
    }

    /** Set new start point and replan. */
    fun setRouteOrigin(lat: Double, lon: Double) {
        val wp = editableWaypoints() ?: return
        planWaypoints(listOf(lat to lon) + wp.drop(1), editableProfile())
    }

    /** Set new destination and replan. */
    fun setRouteDestination(lat: Double, lon: Double, name: String? = null) {
        destName = name
        val wp = editableWaypoints() ?: return
        planWaypoints(wp.dropLast(1) + listOf(lat to lon), editableProfile())
    }

    /** NAV-1 — the operator picked another variant in the compare sheet. */
    fun selectRouteAlternative(index: Int) {
        // Through the engine, not the planner: the car is looking at this route
        // too, and must not be left rendering the one the operator just dropped.
        engine.selectVariant(index) ?: return
    }

    /** NAV-2 — nav.start / «старт»: plan, then begin guiding the moment the plan lands. */
    fun navigateTo(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        profile: RouteProfile,
        via: List<Pair<Double, Double>> = emptyList(),
    ) {
        guidance.startOnNextPlan()
        planWaypoints(listOf(fromLat to fromLon) + via + (toLat to toLon), profile)
    }

    /**
     * ATLAS-4 — speculative warm-up on pin-drop: the long-press sheet is
     * still animating open while the FOOT and CAR corridors are already on
     * the wire. Fire-and-forget by design.
     */
    fun prefetchRoute(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double) {
        val waypoints = listOf(fromLat to fromLon, toLat to toLon)
        planner.prefetch(waypoints, RouteProfile.FOOT)
        planner.prefetch(waypoints, RouteProfile.CAR)
    }

    private fun planWaypoints(waypoints: List<Pair<Double, Double>>, profile: RouteProfile) {
        if (_routing.value) return
        lastAttempt = waypoints
        lastAttemptProfile = profile
        // Not `planner.waypoints.isNotEmpty()`: that stays populated from the
        // last SUCCESSFUL plan even while this one is failing, so a route
        // whose first-ever attempt fails read as "had a plan" and posted the
        // false «лишив попередній маршрут» notice over an empty map.
        val hadPlan = planner.selectedVariant != null
        scope.launch {
            _routing.value = true
            // Нова спроба знімає стару відмову одразу: смуга, що пережила
            // початок наступного розрахунку, читається як відмова НА НЬОГО.
            _routeFailure.value = null
            try {
                val selected = runCatching {
                    engine.plan(
                        waypoints,
                        profile,
                        departSec = if (profile == RouteProfile.TRANSIT) _transitDepartSec.value else null,
                    )
                }.getOrNull()
                // A transit failure carries its own WHY («до метро задалеко») —
                // the generic line made a truthful empty answer look broken.
                // Non-transit is the same doctrine one layer down: the graph
                // that actually refused (on-device mesh, then network) knows
                // WHY, and «маршрутизатор недоступний» hid that behind one
                // sentence whether the cause was «нема сервера» or «нема доріг
                // на пристрої для цієї ділянки» — two different fixes.
                val roadReasonRes = if (profile != RouteProfile.TRANSIT) {
                    when (planner.roadFailureReason) {
                        RoadRefusal.TOO_LONG -> R.string.route_reason_too_long
                        RoadRefusal.GRAPH_UNREADABLE -> R.string.route_reason_graph_unreadable
                        RoadRefusal.NO_DATA -> R.string.route_reason_no_data
                        RoadRefusal.NETWORK_NO_ROUTE -> R.string.route_reason_network_no_route
                        RoadRefusal.SEARCH_CEILING -> R.string.route_reason_search_ceiling
                        RoadRefusal.OFF_ROAD -> R.string.route_reason_off_road
                        RoadRefusal.NO_PACK_NO_SERVER -> R.string.route_reason_no_pack_no_server
                        null -> null
                    }
                } else {
                    null
                }
                val failureLine = when {
                    profile == RouteProfile.TRANSIT -> planner.transitFailureReason?.let(::transitFailureLine)
                    roadReasonRes != null -> null
                    local.phantom.companion.core.net.map.GeoEndpoints.config.routingTemplate == null ->
                        MapText.s(R.string.mapl_f_routing_not_configured)
                    else -> null
                }
                val profileChanged = planner.profile != profile
                when {
                    selected != null -> if (profile == RouteProfile.TRANSIT && profileChanged) {
                        postNotice(
                            MapText.s(
                                R.string.mapl_f_shown_profile,
                                planner.transitFailureReason?.let(::transitFailureLine) ?: MapText.s(R.string.mapl_f_transit_not_found),
                                planner.profile.label,
                            ),
                        )
                    }
                    hadPlan && !profileChanged -> {
                        guidance.onPlanFailed()
                        postNotice(failureLine ?: MapText.s(R.string.mapl_f_replan_kept_previous))
                    }
                    else -> {
                        guidance.onPlanFailed()
                        _maneuvers.value = emptyList()
                        _laneBands.value = emptyList()
                        _routes.value = emptyList()
                        _routeInfo.value = null
                        // Смуга замість пігулки: пігулка спливає, а людина лишається
                        // стояти. Азимут і пряма — чиста математика по тих самих
                        // точках, і смуга сама підписує їх «це не маршрут».
                        val from = waypoints.first()
                        val to = waypoints.last()
                        _routeFailure.value = RouteFailure(
                            reason = roadReasonRes,
                            reasonText = failureLine,
                            bearingDeg = bearingDegrees(from, to),
                            straightMeters = straightMeters(from, to),
                            // Порожня транзитна відповідь найчастіше про час,
                            // тож ручка часу стоїть прямо у смузі відмови.
                            offerDepartAt = profile == RouteProfile.TRANSIT,
                            // Той профіль, який справді пробували — не «пішки»
                            // за замовчуванням: після невдалого авто-маршруту
                            // рядок вибору мусить показувати авто, не брехати.
                            profile = profile,
                        )
                    }
                }
            } finally {
                _routing.value = false
            }
        }
    }

    /** Draw every variant (alternatives dim, selection bright, on top) + banner info. */
    private fun publishPlan() {
        val opts = planner.options.value
        val sel = planner.selectedIndex.value
        val dest = planner.waypoints.lastOrNull() ?: return
        val variant = planner.selectedVariant ?: return
        val toLine = { v: RouteVariant, selected: Boolean ->
            val color = (if (selected) RouteRole.ROUTE else RouteRole.ALT).id
            val width = if (selected) {
                when (v.profile) {
                    RouteProfile.CAR -> 7f
                    RouteProfile.BIKE -> 6f
                    RouteProfile.FOOT -> 5f
                    RouteProfile.TRANSIT -> 6.5f
                }
            } else 3.5f
            val isDashed = selected && (v.profile == RouteProfile.FOOT)
            MapPayload.PolylineData(
                points = v.points.map { MapPayload.PointData(it.first, it.second) },
                color = color,
                width = width,
                profile = v.profile.name.lowercase(),
                isDashed = isDashed,
                opacity = if (selected) 1.0f else 0.65f,
                selected = selected,
            )
        }

        val unselectedRoutes = opts.filterIndexed { i, _ -> i != sel }.map { toLine(it.variant, false) }
        val selectedLines = mutableListOf<MapPayload.PolylineData>()

        val itin = planner.itineraryFor(sel)
        if (variant.profile == RouteProfile.TRANSIT && itin != null && itin.legs.isNotEmpty()) {
            for (leg in itin.legs) {
                when (leg) {
                    is local.phantom.companion.core.data.transit.Itinerary.Leg.Access -> {
                        val pts = if (leg.points.isNotEmpty()) leg.points else listOf(leg.fromLat to leg.fromLon, leg.toLat to leg.toLon)
                        selectedLines += MapPayload.PolylineData(
                            points = pts.map { MapPayload.PointData(it.first, it.second) },
                            color = RouteRole.ACCESS.id,
                            width = 5f,
                            profile = "foot",
                            isDashed = true,
                            opacity = 1.0f,
                            selected = true,
                        )
                    }
                    is local.phantom.companion.core.data.transit.Itinerary.Leg.Ride -> {
                        val pts = if (leg.points.isNotEmpty()) leg.points else listOf(leg.fromLat to leg.fromLon, leg.toLat to leg.toLon)
                        val colorHex = String.format("#%06X", leg.colour and 0xFFFFFF)
                        selectedLines += MapPayload.PolylineData(
                            points = pts.map { MapPayload.PointData(it.first, it.second) },
                            color = colorHex,
                            width = 7f,
                            profile = "transit",
                            isDashed = false,
                            opacity = 1.0f,
                            selected = true,
                        )
                    }
                    is local.phantom.companion.core.data.transit.Itinerary.Leg.Transfer -> Unit
                }
            }
        } else {
            selectedLines += toLine(variant, true)
        }

        val baseRoutes = unselectedRoutes + selectedLines

        // MULTIMODAL WALKING TRAIL & INDOOR PASSAGES
        val walkLines = mutableListOf<MapPayload.PolylineData>()
        val paw = variant.parkAndWalk
        if (paw != null && paw.walkPoints.isNotEmpty()) {
            walkLines += MapPayload.PolylineData(
                points = paw.walkPoints.map { MapPayload.PointData(it.first, it.second) },
                color = RouteRole.ACCESS.id,
                width = 5f,
                profile = "foot",
                isDashed = true,
            )
        } else if (variant.points.isNotEmpty()) {
            val lastRoadPt = variant.points.last()
            val dist = straightMeters(lastRoadPt, dest)
            if (dist > 18.0) {
                scope.launch {
                    val indoorConn = runCatching {
                        indoorConnector.connectDestination(lastRoadPt, dest)
                    }.getOrNull()
                    if (indoorConn != null && indoorConn.indoorPoints.isNotEmpty()) {
                        val indoorPoly = MapPayload.PolylineData(
                            points = indoorConn.indoorPoints.map { MapPayload.PointData(it.first, it.second) },
                            color = RouteRole.INDOOR.id,
                            width = 5.5f,
                            profile = "indoor",
                            isDashed = true,
                        )
                        _routes.value = _routes.value + indoorPoly
                        if (indoorConn.steps.isNotEmpty()) {
                            _maneuvers.value = _maneuvers.value + ManeuverPins.of(indoorConn.steps)
                        }
                    }
                }
                walkLines += MapPayload.PolylineData(
                    points = listOf(
                        MapPayload.PointData(lastRoadPt.first, lastRoadPt.second),
                        MapPayload.PointData(dest.first, dest.second),
                    ),
                    color = RouteRole.ACCESS.id,
                    width = 4.5f,
                    profile = "foot",
                    isDashed = true,
                )
            }
        }

        _routes.value = baseRoutes + walkLines
        _routeInfo.value = MapViewModel.RouteInfo(
            distanceMeters = variant.distanceMeters,
            durationSeconds = variant.durationSeconds,
            profile = variant.profile,
            destLat = dest.first,
            destLon = dest.second,
            viaCount = (planner.waypoints.size - 2).coerceAtLeast(0),
            itinerary = planner.itineraryFor(sel),
            destName = destName,
        )
        _maneuvers.value = ManeuverPins.of(variant.steps)
        // PRESENCE — the whole cost of the lane band is paid here, once: during
        // the drive the map only swaps a finished string into a source.
        _laneBands.value = local.phantom.companion.feature.stream.ui.map.nav.lanes.LaneBands.of(variant)
        // W-3 — the corridor `route.along` searches, published with the plan.
        _activeRoutePoints.value = variant.points
        // The session rebind (pending nav.start, reroute, variant pick) happens
        // in the engine, before this head is ever told to redraw — so a reroute
        // takes even on a drive where no Compose surface exists.
        syncThreatWatch()
    }

    fun clearRoute() {
        engine.stop()
        forgetAttempt()
        _maneuvers.value = emptyList()
        _laneBands.value = emptyList()
        _routes.value = emptyList()
        _routeInfo.value = null
        _routeFailure.value = null
        _activeRoutePoints.value = null
        syncThreatWatch()
        // The trip ended — a deadline over no trip is stale noise.
        clearDeadline()
        // Нова мета — новий намір: типовим часом виїзду знову стає «зараз».
        _transitDepartSec.value = null
    }

    // ── Hooks for the evacuation coordinator (its route replaces any plan) ──

    /** The evacuation route replaces any plan session. */
    fun stopPlanSession() {
        guidance.stop()
        planner.clear()
        forgetAttempt()
    }

    fun setRouting(active: Boolean) {
        _routing.value = active
    }

    /** Publish a route the planner did not produce (LEVIATHAN-1 evacuation). */
    fun publishExternalRoute(
        line: MapPayload.PolylineData,
        info: MapViewModel.RouteInfo,
        corridor: List<Pair<Double, Double>>,
    ) {
        _routes.value = listOf(line)
        _activeRoutePoints.value = corridor
        _routeInfo.value = info
    }

    /** The selected variant's geometry, for «по дорозі» searches. */
    fun selectedVariantPoints(): List<Pair<Double, Double>>? = planner.selectedVariant?.points

    /** ATLAS-2.5 — the corridor a coast may be constrained to (contract §1). */
    fun selectedVariant(): RouteVariant? = planner.selectedVariant

    /** ATLAS-2.5 — the rebind trigger: bumped on reroute, variant pick, new goal. */
    fun planRevision(): Int = engine.planRevision.value

    /** NAV-5 — corridor speed cameras from the selected variant's intel. */
    fun corridorCameras(): List<Pair<Double, Double>> =
        (planner.options.value.getOrNull(planner.selectedIndex.value)?.intel as? RoutePlanner.Intel.Ready)
            ?.report?.cameras?.map { it.lat to it.lon } ?: emptyList()

    /** NAV-5 — corridor barriers (checkpoints, gates, tolls) from the selected variant's intel. */
    fun corridorBarriers(): List<CorridorBarrier> =
        (planner.options.value.getOrNull(planner.selectedIndex.value)?.intel as? RoutePlanner.Intel.Ready)
            ?.report?.barriers?.map { CorridorBarrier(it.lat, it.lon, it.kind, it.bearingDeg) }
            ?: emptyList()

    // ── NAV-Ω1 — «відлік»: arrival deadline over the plan/guidance session ──

    private val _deadline = MutableStateFlow<DeadlineWatch?>(null)
    val deadline: StateFlow<DeadlineWatch?> = _deadline.asStateFlow()
    private val _deadlineStatus = MutableStateFlow<DeadlineWatch.Status?>(null)
    val deadlineStatus: StateFlow<DeadlineWatch.Status?> = _deadlineStatus.asStateFlow()

    fun setDeadline(epochMs: Long) {
        _deadline.value = DeadlineWatch(epochMs)
        _deadlineStatus.value = null
        val hhmm = SimpleDateFormat("HH:mm", Locale("uk", "UA")).format(Date(epochMs))
        postNotice(MapText.s(R.string.mapl_f_deadline_arrive_by, hhmm))
    }

    fun setDeadlineTo(text: String): Boolean {
        val parsed = DeadlineWatch.parseDeadline(text, System.currentTimeMillis()) ?: return false
        setDeadline(parsed)
        return true
    }

    fun clearDeadline() {
        if (_deadline.value != null) postNotice(MapText.s(R.string.mapl_f_deadline_cleared))
        _deadline.value = null
        _deadlineStatus.value = null
    }

    /**
     * One live ETA sample against the deadline (MapScreen calls this per
     * guidance progress). Returns the spoken cue for a band transition.
     */
    fun onDeadlineEstimate(remainingDurationS: Double): String? {
        val watch = _deadline.value ?: return null
        val (status, cue) = watch.onEstimate(remainingDurationS, System.currentTimeMillis())
        _deadlineStatus.value = status
        return cue?.spoken
    }
}

/**
 * Азимут і пряма між двома точками — єдине, що точно відоме, коли маршруту
 * немає. Обидві функції навмисно живуть тут, а не в смузі: смуга малює те, що
 * їй дали, і не має права нічого дорахувати сама.
 */
internal fun bearingDegrees(from: Pair<Double, Double>, to: Pair<Double, Double>): Int {
    val f1 = Math.toRadians(from.first)
    val f2 = Math.toRadians(to.first)
    val dl = Math.toRadians(to.second - from.second)
    val y = kotlin.math.sin(dl) * kotlin.math.cos(f2)
    val x = kotlin.math.cos(f1) * kotlin.math.sin(f2) -
        kotlin.math.sin(f1) * kotlin.math.cos(f2) * kotlin.math.cos(dl)
    // Округлення, не обрізання: 89.6° — це схід, а «89» на екрані читається як
    // намір, якого немає. Ціна помилки тут — людина, що бере азимут із екрана.
    return ((Math.toDegrees(kotlin.math.atan2(y, x)) + 360.0) % 360.0)
        .let { kotlin.math.round(it).toInt() % 360 }
}

internal fun straightMeters(from: Pair<Double, Double>, to: Pair<Double, Double>): Double {
    val r = 6_371_000.0
    val f1 = Math.toRadians(from.first)
    val f2 = Math.toRadians(to.first)
    val df = f2 - f1
    val dl = Math.toRadians(to.second - from.second)
    val a = kotlin.math.sin(df / 2).let { it * it } +
        kotlin.math.cos(f1) * kotlin.math.cos(f2) * kotlin.math.sin(dl / 2).let { it * it }
    return 2 * r * kotlin.math.asin(kotlin.math.sqrt(a).coerceAtMost(1.0))
}

internal fun transitFailureLine(f: local.phantom.companion.core.nav.TransitFailure): String = when (f) {
    is local.phantom.companion.core.nav.TransitFailure.FarFromStop -> MapText.s(R.string.map_r0_transit_far_stop, f.km)
    is local.phantom.companion.core.nav.TransitFailure.NoTripsAt -> MapText.s(R.string.map_r0_transit_no_trips_at, f.departSec / 3600, (f.departSec % 3600) / 60)
    local.phantom.companion.core.nav.TransitFailure.Unreachable -> MapText.s(R.string.map_r0_transit_unreachable)
    local.phantom.companion.core.nav.TransitFailure.NoSchedule -> MapText.s(R.string.map_r0_transit_no_schedule)
}
