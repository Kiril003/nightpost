package local.phantom.companion.core.nav

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import local.phantom.companion.core.net.map.RouteProfile
import local.phantom.companion.core.net.map.RouteVariant

/**
 * HIGHWAY DOMINANCE, mandate 1 — the one navigator, with no head of its own.
 *
 * A drive does not belong to a screen. W-1 already learned half of that lesson:
 * guidance was hoisted out of `MapScreen`'s `LaunchedEffect`s into the
 * ViewModel, because racking the camera to the Dialogue was starving the
 * session. This is the other half. A ViewModel is still a *head* — it is born
 * with an Activity and it dies with one — and on Android Auto there is usually
 * no Activity at all. A route owned by `viewModelScope` is a route the car
 * cannot see.
 *
 * So the engine sits below every head, on a scope that lives as long as the
 * process. Both surfaces — the Continuum's map and the car's NavigationTemplate
 * — are *observers*. Neither owns the drive; neither can end it by being
 * backgrounded; and because there is exactly one engine, they cannot disagree
 * about where the operator is going, which is the failure mode that makes
 * two-screen navigators feel haunted.
 *
 * The engine is deliberately ignorant of both. It knows nothing about Compose,
 * nothing about `androidx.car.app`, and nothing about air-raid polygons. It
 * knows a route, a fix, and a voice.
 */
class NavigationEngine(
    private val scope: CoroutineScope,
    /**
     * The operator's position, polled by the engine's own pump. Nullable
     * because the sky is not always there — and the engine must be able to say
     * so rather than invent a fix.
     */
    private val locationSource: () -> NavFix?,
    private val planner: RoutePlanner,
    private val clockMs: () -> Long = System::currentTimeMillis,
    /** Слід супроводу для реєстратора; null — не писати нічого (див. [GuidanceTrace]). */
    private val trace: GuidanceTrace? = null,
    landmarks: LandmarkSource? = null,
    darkAt: (lat: Double, lon: Double, epochMs: Long) -> Boolean = { _, _, _ -> false },
) {
    /** Where the drive is, as one snapshot both heads render from. */
    data class NavState(
        val phase: Phase,
        /** null in [Phase.GUIDING] until the first navigation-grade fix lands. */
        val progress: GuidanceProgress?,
        /** The selected variant's geometry — the car draws it, the map draws it. */
        val routePoints: List<Pair<Double, Double>>,
        val profile: RouteProfile?,
        val destination: Pair<Double, Double>?,
        /**
         * ZERO-FAIL — the engine is guiding but has no fix it is willing to
         * steer on (cold GNSS, a garage, a jammer). The heads must show this,
         * not a stale arrow: a navigator that hides its blindness is worse than
         * one that has none.
         */
        val blind: Boolean,
    ) {
        val guiding: Boolean get() = phase == Phase.GUIDING
    }

    enum class Phase { IDLE, GUIDING, ARRIVED }

    private val _state = MutableStateFlow(
        NavState(Phase.IDLE, null, emptyList(), null, null, blind = false),
    )
    val state: StateFlow<NavState> = _state.asStateFlow()

    /** Typed Ukrainian cues. Spoken by whichever head owns the voice. */
    private val _voiceCues = MutableSharedFlow<NavCue>(extraBufferCapacity = 16)
    val voiceCues: SharedFlow<NavCue> = _voiceCues.asSharedFlow()

    /** ON-TIME VOICE — upcoming final-cue texts for TTS prewarming. */
    val prewarms: SharedFlow<String> get() = guidance.prewarms

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    /**
     * Bumped only when the plan *under* the drive is replaced — a reroute, a
     * variant pick, a new destination. Emphatically NOT on every fix.
     *
     * That distinction is load-bearing. A head redraws its route off this, and
     * a redraw re-binds the guidance session to the selected variant
     * (`onPlanPublished`), which resets progress to null. Bump it once a second
     * along with the progress tick and the banner blanks on every GPS fix for
     * the entire drive — a live route rendered as a route that has not started
     * yet. So: [publish] for "the drive moved", [bumpPlan] for "the drive is
     * now a different route".
     */
    private val _planRevision = MutableStateFlow(0)
    val planRevision: StateFlow<Int> = _planRevision.asStateFlow()

    val guidance = GuidanceController(
        scope = scope,
        planner = planner,
        onPlanReplaced = { bumpPlan() },
        postNotice = { _notices.tryEmit(it) },
        clockMs = clockMs,
        trace = trace,
        landmarks = landmarks,
        darkAt = darkAt,
    )

    val routePlanner: RoutePlanner get() = planner

    /** Latched, so a blind drive says so once — not once a second, for a whole tunnel. */
    private var blindnessAnnounced = false

    init {
        scope.launch { guidance.voiceCues.collect { _voiceCues.tryEmit(it) } }
        scope.launch { guidance.state.collect { publish() } }
    }

    /**
     * The fix pump. Four polls a second while a drive is live, a slow pulse
     * when it is not — the engine has no idea whether anyone is looking at it,
     * and must not burn a battery on the assumption that someone is.
     *
     * APPLE-LOCK — the hot rate is deliberately faster than the chip's fix
     * rate. A 1 s poll over a 1 s fix stream added up to a full second of
     * phase lag: at 50 km/h the banner, the snap and the camera steered a car
     * 14 m behind the real one, then caught up in a jump. Polling at 250 ms
     * bounds the staleness at a quarter of that; the equality guard below
     * makes the extra polls free — the SAME fix re-read is not a new
     * observation, and must not be judged as one (an off-route streak counted
     * off one repeated fix four times would quadruple-count a single sin).
     */
    private var pumpJob: kotlinx.coroutines.Job? = null

    fun start() {
        if (pumpJob?.isActive == true) return
        pumpJob = scope.launch {
            var lastPumped: NavFix? = null
            while (isActive) {
                val fix = runCatching { locationSource() }.getOrNull()
                if (fix != null && fix != lastPumped) {
                    lastPumped = fix
                    onFix(fix)
                }
                delay(if (guidance.isActive) FIX_INTERVAL_HOT_MS else FIX_INTERVAL_IDLE_MS)
            }
        }
    }

    /**
     * ZERO-FAIL — and *only* if the fix has been judged.
     *
     * The location chain's lower rungs (a last-known fix of unknown age, a
     * city-level IP lookup up to half an hour stale) exist so the AI can say
     * roughly where the operator is, and they are right to exist. Steering on
     * one is how a drive dies: guidance map-matches the guess, finds itself
     * kilometres from its own polyline, calls that «off-route», and reroutes the
     * operator away from a route that was correct.
     *
     * So a fix that is not navigation-grade moves nothing. The drive holds its
     * last judged state — no cues, no reroute, no false arrival — and admits the
     * blindness once, rather than lying confidently once a second.
     */
    fun onFix(fix: NavFix) {
        if (!fix.navigationGrade) {
            if (guidance.isActive && !blindnessAnnounced) {
                blindnessAnnounced = true
                _notices.tryEmit("Немає надійної позиції — тримаю маршрут і не перебудовую його.")
                publish(blind = true)
            }
            return
        }
        if (blindnessAnnounced) {
            blindnessAnnounced = false
            publish(blind = false)
        }
        if (!guidance.isActive) return
        // Напрямок руху їде разом із фіксом: наведення його не вживає, але без
        // нього у сліді нема з чим порівняти напрямок дороги, на яку зшили.
        guidance.onFix(
            fix.lat, fix.lon, fix.speedMps, fix.trusted, fix.accuracyM, fix.gait,
            bearingDeg = fix.bearingDeg,
        )
    }

    /** NAV-5 — a warning from a watcher that is not the guidance session itself. */
    fun announce(text: String) = guidance.announce(text)

    /**
     * A head changed the plan through the planner directly (the map's «через цю
     * точку», a variant pick in the compare sheet). The engine cannot see that
     * happen — [planner] is a plain object, not a flow — so the head must say
     * so, or the *other* head keeps rendering a route nobody is driving.
     */
    fun refresh() = bumpPlan()

    /** PHANTOM's hand (`nav.start` / `nav.stop`) and the operator's tap share this door. */
    suspend fun execute(cmd: NavCommand, from: Pair<Double, Double>?) {
        when (cmd) {
            is NavCommand.Start -> {
                val origin = from ?: run {
                    _notices.tryEmit("Не знаю, де ви — не можу прокласти маршрут.")
                    return
                }
                val profile = resolveProfile(cmd.profile)
                guidance.startOnNextPlan()
                val waypoints = listOf(origin) + cmd.via + (cmd.toLat to cmd.toLon)
                if (plan(waypoints, profile) == null) {
                    guidance.onPlanFailed()
                    // An OFF-by-config service must say so — «не вдалось»
                    // reads as a broken navigator when the truth is that no
                    // routing server is configured (release builds ship that
                    // way since the legal sweep).
                    _notices.tryEmit(
                        if (local.phantom.companion.core.net.map.GeoEndpoints.config.routingTemplate == null) {
                            "Маршрутизацію не налаштовано — вкажіть сервер у Налаштування → Мапа → Геосервіси."
                        } else {
                            "Не вдалось прокласти маршрут."
                        },
                    )
                }
            }
            NavCommand.Stop -> stop()
            is NavCommand.Deadline -> Unit // owned by the map surface's DeadlineWatch
        }
    }

    /** Plan (or replan) and publish. Returns null when OSRM had nothing. */
    suspend fun plan(
        waypoints: List<Pair<Double, Double>>,
        profile: RouteProfile,
        departSec: Int? = null,
    ): RouteVariant? {
        val selected = planner.plan(waypoints, profile, departSec) ?: return null
        bumpPlan()
        return selected
    }

    fun selectVariant(index: Int): RouteVariant? {
        val v = planner.select(index) ?: return null
        bumpPlan()
        return v
    }

    fun stop() {
        guidance.stop()
        planner.clear()
        blindnessAnnounced = false
        bumpPlan()
    }

    /**
     * The route itself changed. Rebind the live session to the new geometry —
     * this is what makes an off-route reroute actually take, and what consumes
     * a pending `nav.start` — then tell every head to redraw.
     *
     * The rebind lives HERE, and not in a head, on purpose: a reroute that fires
     * while the phone is in the operator's pocket and the car is driving must
     * still take. Before the extraction it lived in `MapViewModel.publishPlan`,
     * which is to say it did not happen at all unless a Compose surface existed.
     */
    private fun bumpPlan() {
        guidance.onPlanPublished()
        publish()
        _planRevision.value += 1
    }

    private fun publish(blind: Boolean = blindnessAnnounced) {
        val variant = planner.selectedVariant
        val g = guidance.state.value
        _state.value = NavState(
            phase = when (g) {
                is GuidanceState.Guiding -> Phase.GUIDING
                GuidanceState.Arrived -> Phase.ARRIVED
                GuidanceState.Idle -> Phase.IDLE
            },
            progress = (g as? GuidanceState.Guiding)?.progress,
            routePoints = variant?.points.orEmpty(),
            profile = variant?.profile,
            destination = planner.waypoints.lastOrNull(),
            blind = blind,
        )
    }

    /**
     * The default stays FOOT, deliberately, even under a highway mandate: it is
     * the contract `nav.start` already had, and quietly turning every
     * profile-less «проклади маршрут» into a drive would be a behaviour change
     * smuggled in under an unrelated refactor. The car surface passes CAR
     * explicitly.
     */
    private fun resolveProfile(word: String?): RouteProfile = when (word?.lowercase()?.trim()) {
        "авто", "машиною", "машина", "car", "driving" -> RouteProfile.CAR
        "вело", "велосипед", "bike", "cycling" -> RouteProfile.BIKE
        else -> RouteProfile.FOOT
    }

    private companion object {
        /** Hot poll: bounds fix staleness at ~250 ms (see [start]). */
        const val FIX_INTERVAL_HOT_MS = 250L
        const val FIX_INTERVAL_IDLE_MS = 10_000L
    }
}

/**
 * One judged position, stripped of everything the navigator does not need.
 *
 * Deliberately *not* `core-ai`'s `LocationFix`: the headless navigator must not
 * depend on the AI module to know where the car is. [navigationGrade] is the
 * whole of what it needs to know about provenance — the caller has already done
 * the judging, and this is the verdict.
 */
data class NavFix(
    val lat: Double,
    val lon: Double,
    val speedMps: Float?,
    val bearingDeg: Float?,
    /** False for a coarse/stale fix: the engine will not steer on it. */
    val navigationGrade: Boolean,
    /**
     * CRITICAL FIELD FAILURE, Kyiv 2026-07-16 — navigation-grade is not
     * enough. A dead-reckoned fix is honestly NAVIGATION (the engine working
     * through a tunnel), but four hours of EW turned «working through a
     * tunnel» into an inertial estimate kilometres adrift, and guidance
     * rerouted off it every cooldown, all day. [trusted] is the stricter
     * verdict: sky-fused, not dead-reckoned, small doubt. Progress may be
     * maintained by an untrusted fix near the corridor (that IS dead
     * reckoning doing its job); off-route verdicts, reroutes and arrival
     * require a trusted one. Fails closed.
     */
    val trusted: Boolean = false,
    /**
     * Reported 68 % radius, metres. Off-route verdicts inflate their corridor
     * threshold by it — indoor scatter that the fix admits to must not read
     * as «зійшов з маршруту». Null = unknown (treated as exact).
     */
    val accuracyM: Float? = null,
    /** The kinematic verdict: guidance paces the whole session off it. */
    val gait: NavGait = NavGait.UNKNOWN,
)
