package local.phantom.companion.core.nav

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import local.phantom.companion.core.net.map.RouteVariant

import kotlin.math.abs

/**
 * NAV-2 — `nav.start` / `nav.stop` requests from PHANTOM's hand, delivered
 * through AppContainer to the open map (Law 2 — dual control: the same
 * flow the operator's «старт» tap drives). Public: the `:app` wiring
 * constructs these.
 */
sealed interface NavCommand {
    data class Start(
        val toLat: Double,
        val toLon: Double,
        /** Raw profile word («авто»/«пішки»/«вело»/en synonyms), resolved map-side. */
        val profile: String? = null,
        val via: List<Pair<Double, Double>> = emptyList(),
    ) : NavCommand

    data object Stop : NavCommand

    /** NAV-Ω1 — arrival deadline («маю бути о 18:00»); null clears it. */
    data class Deadline(val epochMs: Long?) : NavCommand
}

/** NAV-2 — the guidance lifecycle. Preview is derived: plan exists + Idle. */
sealed interface GuidanceState {
    data object Idle : GuidanceState

    /** progress == null until the first GPS fix lands («чекаю GPS…»). */
    data class Guiding(val progress: GuidanceProgress?) : GuidanceState

    data object Arrived : GuidanceState
}

/**
 * NAV-2 — the live-guidance side of the map surface:
 * owns the [GuidanceSession] over the planner's selected variant, feeds it
 * operator fixes, republishes progress, forwards voice cues, and runs the
 * off-route auto-replan (throttled — OSRM is a shared public instance).
 * The ViewModel stays the single publisher of map polylines; a reroute
 * lands through the same plan-session + publish path as any other plan.
 */
class GuidanceController(
    private val scope: CoroutineScope,
    private val planner: RoutePlanner,
    /** Re-draws polylines + banner from the planner session (MapViewModel::publishPlan). */
    private val onPlanReplaced: () -> Unit,
    private val postNotice: (String) -> Unit,
    private val clockMs: () -> Long = System::currentTimeMillis,
    /**
     * Слід супроводу, або null — і тоді про маршрут не пишеться нічого, як
     * було досі. Стоїть тут, а не на мапі: через це місце проходить КОЖЕН
     * фікс обох голів (телефон і авто), а журнал мапи живе лише поки
     * відкритий екран і лише в debug-збірці.
     */
    private val trace: GuidanceTrace? = null,
    private val landmarks: LandmarkSource? = null,
    private val darkAt: (lat: Double, lon: Double, epochMs: Long) -> Boolean = { _, _, _ -> false },
) {
    private val _state = MutableStateFlow<GuidanceState>(GuidanceState.Idle)
    val state: StateFlow<GuidanceState> = _state.asStateFlow()

    /** Every write mirrors to [GuidanceHub] so the car head can read it. */
    private var published: GuidanceState
        get() = _state.value
        set(value) {
            _state.value = value
            GuidanceHub.publish(value)
        }

    /** ON-TIME VOICE — typed Ukrainian cues; the speaker downstream owns
     *  staleness, supersession and preemption (see [NavCueSpeaker]). */
    private val _voiceCues = MutableSharedFlow<NavCue>(extraBufferCapacity = 16)

    /** Every cue also reaches [GuidanceHub], so a screenless speaker hears it. */
    private fun emitCue(cue: NavCue) {
        _voiceCues.tryEmit(cue)
        GuidanceHub.emitCue(cue)
    }
    val voiceCues: SharedFlow<NavCue> = _voiceCues.asSharedFlow()

    /** ON-TIME VOICE — final-tier phrases of upcoming maneuvers, for the
     *  premium engine's synthesis cache. Advisory; dropped = still spoken,
     *  just by whichever engine starts fastest. */
    private val _prewarms = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val prewarms: SharedFlow<String> = _prewarms.asSharedFlow()

    /**
     * NAV-5 — ThreatWatch (and any future in-motion watcher) speaks through
     * the SAME queue as maneuver cues, so warnings never talk over turns —
     * and, typed INFO, never preempt one either.
     */
    fun announce(text: String) {
        emitCue(
            NavCue(
                text = text,
                urgency = NavCue.Urgency.INFO,
                bornMs = clockMs(),
                ttlMs = ANNOUNCE_TTL_MS,
            ),
        )
    }

    private var session: GuidanceSession? = null
    private var lastSpeedMps: Float? = null

    /** The route the live session is bound to — what a replan is judged against. */
    private var boundRoute: RouteVariant? = null
    private var pendingStart = false
    private var rerouting = false
    private var lastRerouteMs = 0L

    /** Consecutive reroutes without a clean on-route fix between them. */
    private var rerouteStrikes = 0
    private var lastRerouteSpokenMs = 0L

    /** The operator (or PHANTOM) started guidance over the already-planned route. */
    fun start() {
        val variant = planner.selectedVariant ?: return
        bind(variant, announceStart = true)
    }

    /** nav.start flow: begin guiding as soon as the requested plan lands. */
    fun startOnNextPlan() {
        pendingStart = true
    }

    fun onPlanFailed() {
        pendingStart = false
    }

    /**
     * Called by the ViewModel after every publishPlan: consumes a pending
     * nav.start, and rebinds a live session when the plan under it changed
     * (a reroute or the operator picking another variant mid-drive).
     *
     * A rebind is not free: it drops progress to null, which blanks the banner
     * and the notification back to «чекаю GPS» until the next fix. So a plan
     * that came back as the road already under the wheels must NOT rebind.
     */
    fun onPlanPublished() {
        val variant = planner.selectedVariant ?: return
        when {
            pendingStart -> {
                pendingStart = false
                bind(variant, announceStart = true)
            }
            _state.value is GuidanceState.Guiding && !sameRoute(boundRoute, variant) ->
                bind(variant, announceStart = false)
        }
    }

    private val visitedWaypoints: MutableSet<Pair<Double, Double>> =
        java.util.concurrent.ConcurrentHashMap.newKeySet<Pair<Double, Double>>()

    fun stop() {
        session = null
        boundRoute = null
        pendingStart = false
        rerouteStrikes = 0
        visitedWaypoints.clear()
        GuidanceHub.setRoute(emptyList())
        published = GuidanceState.Idle
    }

    val isActive: Boolean
        get() = _state.value is GuidanceState.Guiding

    /** One operator GPS fix (1 Hz from the map surface). [trusted] gates
     *  off-route/reroute/arrival — see [NavFix.trusted]. */
    fun onFix(
        lat: Double,
        lon: Double,
        speedMps: Float?,
        trusted: Boolean = true,
        accuracyM: Float? = null,
        gait: NavGait = NavGait.UNKNOWN,
        /**
         * Напрямок РЕАЛЬНОГО руху з фікса; null — невідомий. Наведення його не
         * вживає (поки що) — воно пишеться у слід поруч із напрямком дороги,
         * бо саме розбіжність між ними і є скаргою «показує, що я їду в той
         * бік». Типове значення лишає всі наявні виклики чинними.
         */
        bearingDeg: Float? = null,
    ) {
        val s = session ?: return
        if (_state.value !is GuidanceState.Guiding) return
        lastSpeedMps = speedMps
        val update = s.onFix(
            lat, lon, speedMps, clockMs(), trusted, accuracyM, gait,
            motionBearingDeg = bearingDeg,
        )
        trace?.onFix(
            guidanceTraceLine(
                update = update,
                lat = lat,
                lon = lon,
                speedMps = speedMps,
                trusted = trusted,
                accuracyM = accuracyM,
                gait = gait,
                motionBearingDeg = bearingDeg,
            ),
        )
        update.cues.forEach { emitCue(it) }
        update.prewarm?.let { _prewarms.tryEmit(it) }

        // Track visited via waypoints so rerouting never orders a U-turn back to past stops
        val currentVias = planner.waypoints.drop(1).dropLast(1)
        currentVias.forEach { via ->
            if (via !in visitedWaypoints && haversineM(update.progress.snappedLat to update.progress.snappedLon, via) <= 80.0) {
                visitedWaypoints.add(via)
            }
        }

        if (update.arrived) {
            val isCarOrTransit = planner.profile == local.phantom.companion.core.net.map.RouteProfile.CAR ||
                planner.profile == local.phantom.companion.core.net.map.RouteProfile.TRANSIT
            val dest = planner.waypoints.lastOrNull()
            val remainingToTarget = if (dest != null) haversineM(lat to lon, dest) else 0.0

            if (isCarOrTransit && remainingToTarget > 35.0 && dest != null) {
                // MULTI-MODAL HANDOFF: Vehicle parked / transit ended, seamlessly switch to WALKING last-mile to entrance!
                emitCue(
                    NavCue(
                        text = "Транспортний етап завершено. Веду пішки до входу.",
                        urgency = NavCue.Urgency.NOW,
                        key = "handoff_walk",
                        bornMs = clockMs(),
                    ),
                )
                scope.launch {
                    planner.plan(listOf(lat to lon, dest), local.phantom.companion.core.net.map.RouteProfile.FOOT)
                }
                return
            }

            session = null
            published = GuidanceState.Arrived
            return
        }
        published = GuidanceState.Guiding(update.progress)
        if (update.requestReroute) {
            maybeReroute(lat, lon, trusted)
        } else if (!update.progress.offRoute) {
            // Back on the corridor — the escalation earned by the last run of
            // reroutes is spent, and the next genuine departure starts fresh.
            rerouteStrikes = 0
        }
    }

    private fun bind(
        variant: local.phantom.companion.core.net.map.RouteVariant,
        announceStart: Boolean,
    ) {
        visitedWaypoints.clear()
        val fresh = GuidanceSession.of(variant, darkAt, ::limitFor)
        if (fresh == null) {
            postNotice("Маршрут без геометрії — навігацію не запустив")
            return
        }
        session = fresh
        boundRoute = variant
        val path = RoutePath.of(variant)
        if (landmarks != null && path != null) {
            scope.launch(kotlinx.coroutines.Dispatchers.Default) {
                val hints = runCatching {
                    val field = landmarks.near(TurnLandmarks.anchors(variant, path), TurnLandmarks.QUERY_RADIUS_M)
                    TurnLandmarks.plan(variant, path, field)
                }.getOrNull()
                if (!hints.isNullOrEmpty() && session === fresh) fresh.attachLandmarks(hints)
            }
        }
        val points = registerPointsFor(variant, path)
        GuidanceHub.setRoute(points)
        published = GuidanceState.Guiding(null)
        if (announceStart) {
            emitCue(
                NavCue(
                    text = "Навігацію розпочато. ${routeDigestUk(variant)}",
                    urgency = NavCue.Urgency.INFO,
                    key = "start",
                    bornMs = clockMs(),
                ),
            )
        }
        // design-critic §S5 — worst-register-wins belongs ONLY to this one
        // summary line, spoken once per bind. registerPointsFor above is the
        // OTHER rule (per-segment), for the drawn line; conflating the two
        // would make this fire on every drive (~85% of the country is
        // GUESSED per map-realism.md's census) and the line above render
        // dashed-and-faint always — a signal that is always on is none.
        if (path != null) {
            registerConfidenceText(path, points)?.let { text ->
                emitCue(
                    NavCue(
                        text = text,
                        urgency = NavCue.Urgency.INFO,
                        key = "register",
                        bornMs = clockMs(),
                    ),
                )
            }
        }
    }

    /**
     * Posted limit at a fraction along the selected variant, from the
     * RouteIntel corridor report — appears once the async analysis lands,
     * stays null (honest) where OSM carries no maxspeed.
     */
    private fun limitFor(fraction: Double): Int? {
        val opt = planner.options.value.getOrNull(planner.selectedIndex.value) ?: return null
        val samples = (opt.intel as? RoutePlanner.Intel.Ready)?.report?.limitSamples ?: return null
        val nearest = samples.minByOrNull { abs(it.first - fraction) } ?: return null
        return nearest.takeIf { abs(it.first - fraction) <= LIMIT_SAMPLE_TOLERANCE }?.second
    }

    /**
     * Off-route confirmed: replan from the operator's position straight to
     * the destination preserving only unvisited via waypoints.
     */
    private fun maybeReroute(lat: Double, lon: Double, trusted: Boolean) {
        if (!trusted || rerouting) return
        val currentWaypoints = planner.waypoints
        val dest = currentWaypoints.lastOrNull() ?: return
        val profile = planner.profile
        // У вагоні GPS гуляє від колії; перепланування посеред поїздки кинуло б людину на інший рейс.
        if (profile == local.phantom.companion.core.net.map.RouteProfile.TRANSIT && (lastSpeedMps ?: 0f) > IN_VEHICLE_MPS) return
        val now = clockMs()
        val cooldownMs = rerouteCooldownMs(rerouteStrikes)
        if (now - lastRerouteMs < cooldownMs) return
        lastRerouteMs = now
        rerouteStrikes++
        
        // The announcement belongs to the ACT, not the verdict: it is spoken
        // only when a replan actually launches, and never more often than
        // once per window — the session used to speak it on the verdict, a
        // fresh session forgot it had, and a stationary operator heard
        // «перепрокладаю» in a loop for as long as he stood at home.
        if (now - lastRerouteSpokenMs >= REROUTE_ANNOUNCE_MIN_MS) {
            lastRerouteSpokenMs = now
            emitCue(
                NavCue(
                    text = "Зійшли з маршруту — перепрокладаю.",
                    urgency = NavCue.Urgency.SOON,
                    key = "reroute",
                    bornMs = now,
                    ttlMs = REROUTE_CUE_TTL_MS,
                ),
            )
        }
        rerouting = true
        scope.launch {
            try {
                // Preserve only future, unvisited via waypoints if multi-stop route was active
                val remainingWaypoints = if (currentWaypoints.size > 2) {
                    val viasAhead = currentWaypoints.drop(1).dropLast(1).filter { via ->
                        via !in visitedWaypoints && haversineM(lat to lon, via) > 80.0
                    }
                    viasAhead + listOf(dest)
                } else {
                    listOf(dest)
                }
                val planned = planner.plan(listOf(lat to lon) + remainingWaypoints, profile)
                when {
                    planned == null ->
                        postNotice("Не вдалось перепрокласти — веду за старим маршрутом")
                    // The planner handed back the road we are already driving.
                    // At walking pace that is the common case, not the rare one:
                    // the route cache quantizes to ~11 m, so a standing operator
                    // gets the SAME variant every cooldown. Publishing it would
                    // rebind the session, blank both heads and re-post the
                    // notification — a visible loop that changes nothing.
                    sameRoute(boundRoute, planned) -> Unit
                    else -> onPlanReplaced()
                }
            } finally {
                rerouting = false
            }
        }
    }

    private fun routeDigestUk(v: local.phantom.companion.core.net.map.RouteVariant): String {
        val km = v.distanceMeters / 1000.0
        val dist = if (km >= 1.0) "%.1f кілометра".format(java.util.Locale.US, km).replace('.', ',')
        else "${v.distanceMeters.toInt()} метрів"
        val min = (v.durationSeconds / 60.0).toInt().coerceAtLeast(1)
        return "Попереду $dist, орієнтовно $min хвилин."
    }

    internal companion object {
        const val IN_VEHICLE_MPS = 3.5f

        /**
         * Two plans are the same road when the geometry and the profile match.
         * Deliberately exact rather than tolerant: the case that drives the
         * loop is the route cache handing back the identical variant, and an
         * exact test can never suppress a reroute that genuinely moved the
         * operator onto different tarmac. Near-misses are bounded by the
         * backoff below, not by a tolerance nobody can calibrate.
         */
        internal fun sameRoute(a: RouteVariant?, b: RouteVariant): Boolean =
            a != null && a.profile == b.profile && a.points == b.points

        /**
         * 2 s, 4 s, 6 s, 10 s, then doubling to a one-minute ceiling. The fast
         * opening keeps a highway driver from sitting in silence off-route for
         * 15 s (the schedule this replaced); the ceiling is what that older
         * schedule got right and this one used to forget past the fourth
         * strike — a corridor that keeps producing an off-route verdict is
         * usually a bad corridor (a canyon, a garage, indoor scatter near the
         * very shelter the route ends at), not a bad route, and hammering the
         * shared OSRM instance every 10 s until the battery dies answers
         * nothing. Reset by one clean on-route fix.
         */
        internal fun rerouteCooldownMs(strikes: Int): Long {
            val table = REROUTE_COOLDOWN_TABLE_MS
            return table.getOrElse(strikes) { table.last() }
        }

        internal val REROUTE_COOLDOWN_TABLE_MS =
            listOf(2_000L, 4_000L, 6_000L, 10_000L, 20_000L, 40_000L, 60_000L)

        /** «перепрокладаю» is said at most this often, however often the
         *  replan itself legitimately fires. */
        const val REROUTE_ANNOUNCE_MIN_MS = 45_000L

        /** RouteIntel samples sit ~1/48 apart; anything past this is a data gap. */
        const val LIMIT_SAMPLE_TOLERANCE = 0.03

        /** Watch warnings and alarms stay worth speaking this long. */
        const val ANNOUNCE_TTL_MS = 45_000L

        /** A stale «перепрокладаю» after the new route landed is noise. */
        const val REROUTE_CUE_TTL_MS = 20_000L

        /**
         * design-critic §S5 — per-segment register for the drawn line;
         * nothing about the pack-baked `reg` map-register-schema.md §1
         * defines. Nothing in this pipeline ever measures a road: an OSRM
         * step whose road carries a name is DERIVED (a real OSM tag backs
         * it), an unnamed one is GUESSED — the cheapest honest signal
         * available before a baked-pack classifier reaches routing.
         * MEASURED/REMEMBERED are never produced here (see [RouteRegister]).
         *
         * ATLAS-2.5 §11 — a name is only ever the SECOND question. The first
         * is which router drew the line: an offline mesh route knows no turn
         * restrictions, so a named road on it is no more legally certain than
         * an unnamed one ([RouteVariant.legalityCeiling]).
         */
        internal fun registerPointsFor(
            variant: RouteVariant,
            path: RoutePath? = RoutePath.of(variant),
        ): List<RoutePoint> {
            val ceiling = variant.legalityCeiling()
            if (path == null || ceiling == RouteRegister.GUESSED) {
                return variant.points.map { (lat, lon) -> RoutePoint(lat, lon, RouteRegister.GUESSED) }
            }
            val steps = variant.steps
            var stepIdx = 0
            return path.points.mapIndexed { i, (lat, lon) ->
                val alongM = path.cumDistM[i]
                while (stepIdx + 1 < steps.size && path.stepAlongM[stepIdx + 1] <= alongM) stepIdx++
                val named = steps.getOrNull(stepIdx)?.roadName?.isNotBlank() == true
                RoutePoint(lat, lon, if (named) ceiling else RouteRegister.GUESSED)
            }
        }

        /**
         * design-critic §S5 — the OTHER rule: worst-register-wins, but only
         * for this one summary sentence, never for the drawn line above. A
         * single unnamed alley must not narrate every drive, so this fires
         * only past a genuinely long uncertain stretch — the android-auto-
         * expansion.md §3.3 case of a road "only ever traced from a single
         * old satellite pass," not routine gaps in a village grid.
         */
        internal fun registerConfidenceText(path: RoutePath, points: List<RoutePoint>): String? {
            var longestRunM = 0.0
            var runStartM: Double? = null
            for (i in points.indices) {
                val alongM = path.cumDistM.getOrNull(i) ?: continue
                if (points[i].register.isUncertain()) {
                    val start = runStartM ?: alongM.also { runStartM = it }
                    longestRunM = maxOf(longestRunM, alongM - start)
                } else {
                    runStartM = null
                }
            }
            return if (longestRunM >= REGISTER_CUE_MIN_RUN_M) REGISTER_CONFIDENCE_TEXT else null
        }

        /** Below this a low-register stretch is routine (an unnamed yard,
         *  one alley) — above it, it is the kind of gap worth a sentence. */
        const val REGISTER_CUE_MIN_RUN_M = 1_500.0

        const val REGISTER_CONFIDENCE_TEXT = "Ділянка маршруту не підтверджена картою."
    }
}
