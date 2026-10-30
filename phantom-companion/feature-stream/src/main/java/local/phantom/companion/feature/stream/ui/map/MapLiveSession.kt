package local.phantom.companion.feature.stream.ui.map

import local.phantom.companion.core.data.spatial.isRefusedMarkKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import local.phantom.companion.core.ai.context.LocationFix
import local.phantom.companion.core.geo.cameras.HazardType
import local.phantom.companion.core.geo.cameras.SafetyHazard
import local.phantom.companion.core.geo.cameras.SafetyRadarEngine
import local.phantom.companion.core.geo.sky.ClockSkew
import local.phantom.companion.core.geo.sky.ClockWitness
import local.phantom.companion.core.net.map.RouteProfile
import local.phantom.companion.core.nav.ThreatWatch
import local.phantom.companion.feature.stream.R

/**
 * W-1 — the living half of the map: the things that must keep running when
 * the operator looks up from the ground.
 *
 * Owns:
 *  - the operator's fix pump (1 Hz while it matters, a slow pulse when it
 *    does not — §1.3: a live route forbids hibernation, an idle map allows it);
 *  - NAV-5's [ThreatWatch] over the guided corridor;
 *  - Proximity safety radar via [SafetyRadarEngine].
 *
 * СИРЕНА-3 — the sky is no longer owned here. A ViewModel-scoped alarm is an
 * alarm the operator misses the moment he opens the chat, so [AirAlertWatch]
 * lives on the process scope and this session merely mirrors it.
 */
internal class MapLiveSession(
    private val scope: CoroutineScope,
    private val guidanceActive: () -> Boolean,
    private val announce: (String) -> Unit,
    private val locationSource: (() -> LocationFix?)?,
    oblastShapes: OblastShapes?,
    private val alertWatch: AirAlertWatch? = null,
    private val activeRoute: () -> List<Pair<Double, Double>>?,
    private val routeProfile: () -> RouteProfile?,
    private val corridorCameras: () -> List<Pair<Double, Double>>,
    private val corridorBarriers: () -> List<CorridorBarrier> = { emptyList() },
    private val hazardMarks: () -> List<LayerMarkSighting>,
    private val guidanceProgress: () -> local.phantom.companion.core.nav.GuidanceProgress? = { null },
    private val destinationName: () -> String? = { null },
    private val postNotice: (String) -> Unit,
    private val onFixForEvacuation: (Double, Double) -> Unit,
    private val clockWitness: ClockWitness? = null,
    private val breadcrumbTracker: local.phantom.companion.core.nav.BreadcrumbTracker? = null,
    private val sentinelFenceEngine: local.phantom.companion.core.nav.SentinelFenceEngine? = null,
) {
    private var shapes: OblastShapes? = oblastShapes

    private var safetyRadarEngine = SafetyRadarEngine()
    private val overwatchNarrator = local.phantom.companion.core.nav.OverwatchNarrator()

    fun attachOblastShapes(loaded: OblastShapes?) {
        if (loaded == null || shapes != null) return
        shapes = loaded
        threatRoute = null
        syncThreatWatch()
    }

    private val _operatorLocation = MutableStateFlow<LocationFix?>(null)
    val operatorLocation: StateFlow<LocationFix?> = _operatorLocation.asStateFlow()

    /**
     * H-02 — the last satellite verdict on the device clock, or null if none has
     * ever been recorded. Published as the RECORD rather than as a finished
     * [ClockTruth] on purpose: whether a record still counts depends on how old
     * it is, and a state that goes stale by the passage of time cannot be
     * computed once and cached — the readout re-judges it against the clock it
     * is already redrawing on.
     */
    val clockSkew: StateFlow<ClockSkew?> =
        clockWitness?.record ?: MutableStateFlow<ClockSkew?>(null).asStateFlow()

    private val _alertStates = MutableStateFlow<Map<String, AlertState>>(emptyMap())
    val alertStates: StateFlow<Map<String, AlertState>> = _alertStates.asStateFlow()

    private val _alertJournal = MutableStateFlow<List<AlertTransition>>(emptyList())
    val alertJournal: StateFlow<List<AlertTransition>> = _alertJournal.asStateFlow()

    private val _alertDetailLive = MutableStateFlow(false)
    val alertDetailLive: StateFlow<Boolean> = _alertDetailLive.asStateFlow()

    private val _alertFetchedAtMs = MutableStateFlow<Long?>(null)
    val alertFetchedAtMs: StateFlow<Long?> = _alertFetchedAtMs.asStateFlow()

    /**
     * СИРЕНА-3 — цілий знімок неба, а не три його друзки.
     *
     * Мапа читала самі `alertStates` і тому казала «почалась 7 год тому» про
     * стрічку, яка мовчала сім годин: свіжість дзеркалилась поруч окремим
     * потоком, який на скло не йшов НІКОЛИ. Вирок береться з одного знімка —
     * так само, як його бере шторка.
     */
    val sky: StateFlow<AlertSky> = alertWatch?.sky ?: MutableStateFlow(AlertSky()).asStateFlow()

    private val worldVisible = MutableStateFlow(false)
    fun setWorldVisible(visible: Boolean) { worldVisible.value = visible }

    private var threatWatch: ThreatWatch? = null
    private var threatRoute: List<Pair<Double, Double>>? = null

    fun start() {
        val source = locationSource ?: return
        _operatorLocation.value = runCatching { source() }.getOrNull()

        scope.launch {
            while (isActive) {
                _operatorLocation.value = runCatching { source() }.getOrNull()
                val hot = guidanceActive() || worldVisible.value
                delay(if (hot) FIX_INTERVAL_HOT_MS else FIX_INTERVAL_IDLE_MS)
            }
        }
        scope.launch {
            _operatorLocation.collect { fix ->
                if (fix == null) return@collect
                // H-02 — BEFORE the navigation-grade gate below, not inside it.
                // A satellite's clock is right whatever the gate thinks of its
                // position: a fix held coarse for being stale, dead-reckoned or
                // spoof-suspect still carries honest UTC, and refusing to read
                // the time off it would blind the check in exactly the degraded
                // conditions it was built for.
                clockWitness?.observe(fix.gpsTimeMs, System.currentTimeMillis())
                onFix(fix)
            }
        }
        val watch = alertWatch ?: return
        watch.start()
        scope.launch {
            watch.sky.collect { s ->
                _alertStates.value = s.oblasts
                _alertFetchedAtMs.value = s.fetchedAtMs
                _alertDetailLive.value = s.detailLive
            }
        }
        scope.launch { watch.journal.collect { _alertJournal.value = it } }
        // Плашка — тільки для області оператора; воротар уже відсіяв решту 26
        // областей і повтори того самого переходу. Голос тривоги живе в
        // AirAlertService: цей екран помирає в кишені, служба — ні.
        scope.launch {
            watch.notices.collect { n -> postNotice(n.phrase) }
        }
    }

    private fun onFix(fix: LocationFix) {
        if (!fix.isNavigationGrade) return

        if (guidanceActive()) {
            threatWatch?.onFix(fix.lat, fix.lon)?.forEach { cue ->
                announce(cue.spoken)
                postNotice(cue.spoken)
            }
        }

        // Evaluate proximity safety radar alerts
        val alerts = safetyRadarEngine.evaluate(fix.lat, fix.lon)
        alerts.forEach { alert ->
            announce(alert.audioPrompt)
            postNotice(alert.audioPrompt)
        }

        // NAV-Ω2 — Overwatch situational commentary (relevant oblasts only)
        if (guidanceActive()) {
            val progress = guidanceProgress()
            val destName = destinationName()
            val allActiveAlarms = alertWatch?.sky?.value?.oblasts
                ?.filter { it.value.active }
                ?.keys ?: emptySet()
            val relevantOblasts = mutableSetOf<String>()
            shapes?.oblastAt(fix.lat, fix.lon)?.let { relevantOblasts.add(it) }
            val currentRoute = activeRoute()
            if (!currentRoute.isNullOrEmpty()) {
                val step = (currentRoute.size / 20).coerceAtLeast(1)
                for (i in currentRoute.indices step step) {
                    val pt = currentRoute[i]
                    shapes?.oblastAt(pt.first, pt.second)?.let { relevantOblasts.add(it) }
                }
            }
            val filteredAlarms = if (relevantOblasts.isNotEmpty()) {
                allActiveAlarms.filter { it in relevantOblasts }.toSet()
            } else {
                allActiveAlarms.filter { shapes?.oblastAt(fix.lat, fix.lon) == it }.toSet()
            }

            val isNearCheckpoint = corridorBarriers().any { b ->
                local.phantom.companion.core.nav.haversineM(fix.lat to fix.lon, b.lat to b.lon) <= 600.0
            }

            val frame = local.phantom.companion.core.nav.OverwatchNarrator.SituationFrame(
                destinationName = destName,
                remainingDistanceM = progress?.remainingDistanceM ?: 0.0,
                remainingDurationS = progress?.remainingDurationS ?: 0.0,
                speedKmh = fix.speedMps?.let { (it * 3.6f).toInt() },
                speedLimitKmh = progress?.limitKmh,
                activeAlarms = filteredAlarms,
                approachingCheckpoint = isNearCheckpoint,
            )
            overwatchNarrator.evaluate(frame)?.let { cue ->
                announce(cue.text)
                postNotice(cue.text)
            }
        }

        // NAV-Ω2 — record accepted breadcrumbs for offline footprint retrace
        breadcrumbTracker?.record(
            lat = fix.lat,
            lon = fix.lon,
            timeMs = System.currentTimeMillis(),
            accuracyM = fix.accuracyM,
        )

        // NAV-Ω2 — evaluate Sentinel geofence boundary events
        sentinelFenceEngine?.evaluate(fix.lat, fix.lon)?.forEach { event ->
            announce(event.messageUk)
            postNotice(event.messageUk)
        }

        onFixForEvacuation(fix.lat, fix.lon)
    }

    /** NAV-5 — rebuild the watcher when the guided geometry changes (start / reroute / variant pick). */
    fun syncThreatWatch() {
        val polys = shapes
        val route = if (guidanceActive()) activeRoute() else null
        if (route == threatRoute) return
        threatRoute = route
        threatWatch = if (polys != null && route != null && route.size >= 2) {
            ThreatWatch(
                routePoints = route,
                warnAheadM = ThreatWatch.warnDistanceFor(routeProfile() ?: RouteProfile.FOOT),
                oblastAt = polys::oblastAt,
                alertActive = { _alertStates.value[it]?.active == true },
            )
        } else {
            null
        }
        refreshThreats()
    }

    private val _safetyHazards = MutableStateFlow<List<SafetyHazard>>(emptyList())
    val safetyHazards: StateFlow<List<SafetyHazard>> = _safetyHazards.asStateFlow()

    /**
     * NAV-5 — (re)project the live threat set. Corridor cameras arrive when
     * the async intel lands; marks change when a hazard layer is written.
     * Announced ids survive the refresh, so nothing re-fires.
     */
    fun refreshThreats() {
        val marks = hazardMarks().filterNot { isRefusedMarkKind(it.kind) }
        threatWatch?.setThreats(
            corridorCameras(),
            marks.map { Triple(it.lat, it.lon, it.kind) },
            corridorBarriers().map { Triple(it.lat, it.lon, it.kind) },
        )
        val cameras = corridorCameras().mapIndexed { i, (lat, lon) ->
            SafetyHazard(
                id = "cam_$i",
                lat = lat,
                lon = lon,
                // Заголовок порожній свідомо: клас і Є назвою, а називає його
                // шар зі strings.xml — хардкод тут лишався українським і на
                // англійському апараті. Швидкості немає теж: 50 було
                // вигаданим числом, а не тегом maxspeed.
                title = "",
                type = HazardType.SPEED_CAMERA,
            )
        }
        val hazards = marks.mapIndexed { i, m ->
            SafetyHazard(
                id = "haz_$i",
                lat = m.lat,
                lon = m.lon,
                title = m.kind,
                type = markHazardType(m.kind),
                source = m.source,
            )
        }
        val barriers = corridorBarriers().mapIndexed { i, b ->
            SafetyHazard(
                id = "bar_$i",
                lat = b.lat,
                lon = b.lon,
                // Пункт оплати ділить силует будки з пунктом пропуску, тож лише
                // він лишає собі власне імʼя.
                title = if (b.kind.lowercase() == "toll_booth") MapText.s(R.string.mapl_i_toll_booth) else "",
                type = barrierHazardType(b.kind),
                bearingDeg = b.bearingDeg,
            )
        }
        val allHazards = cameras + hazards + barriers
        _safetyHazards.value = allHazards
        safetyRadarEngine = SafetyRadarEngine(allHazards)
    }

    private companion object {
        /**
         * The drive's heartbeat while it matters. APPLE-LOCK — faster than
         * the chip's fix rate on purpose: a 1 s poll over a 1 s fix stream
         * added up to a second of phase lag to everything downstream (dot,
         * threat watch, evacuation check). `_operatorLocation` is a
         * StateFlow, and an unchanged fix re-read compares equal — the
         * extra polls emit nothing.
         */
        const val FIX_INTERVAL_HOT_MS = 250L

        /** …and a slow pulse when nothing is guiding and nobody is looking. */
        const val FIX_INTERVAL_IDLE_MS = 10_000L

    }
}
