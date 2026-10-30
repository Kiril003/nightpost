package local.phantom.companion.feature.car

import android.text.SpannableString
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Distance
import androidx.car.app.model.DistanceSpan
import androidx.car.app.model.Template
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import androidx.car.app.navigation.model.Trip
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import local.phantom.companion.core.nav.GuidanceHub
import local.phantom.companion.core.nav.GuidanceProgress
import local.phantom.companion.core.nav.GuidanceState
import local.phantom.companion.core.nav.TransitSteps
import local.phantom.companion.core.nav.maneuverShort

/**
 * Turn-by-turn on the car display, driven by [GuidanceHub].
 *
 * The phone owns the drive; this renders it. Reading the hub rather than a
 * GuidanceController is the whole point — the host starts this process with no
 * Activity of ours alive, so a ViewModel-owned controller is unreachable.
 */
internal class CarNavigationScreen(
    carContext: CarContext,
    private val ports: CarPorts?,
    private val isCluster: Boolean = false,
) : Screen(carContext), DefaultLifecycleObserver {

    private var job: Job? = null
    private val surface = CarMapSurface(carContext)
    private val navManager: NavigationManager =
        carContext.getCarService(NavigationManager::class.java)

    /**
     * `navigationStarted()`/`navigationEnded()` are a claim to the host, not a
     * state of ours: the host arbitrates which app may guide, and without the
     * claim it need not render turn cards at all. Calling them out of order
     * throws, so the claim is latched.
     */
    private var claimed = false

    init {
        lifecycle.addObserver(this)
        navManager.setNavigationManagerCallback(object : NavigationManagerCallback {
            override fun onStopNavigation() {
                stopDrive()
            }

            override fun onAutoDriveEnabled() = Unit
        })
    }

    override fun onStart(owner: LifecycleOwner) {
        job = owner.lifecycleScope.launch {
            GuidanceHub.state.collectLatest { state ->
                claim(state is GuidanceState.Guiding)
                updateClusterTrip(state)
                invalidate()
            }
        }
        if (!isCluster) {
            surface.attach(owner.lifecycleScope)
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        job?.cancel()
        job = null
        if (!isCluster) {
            surface.detach()
        }
        claim(false)
    }

    /** The host switched day/night (or resized us); the Canvas must repaint. */
    fun onCarConfigurationChanged() {
        if (!isCluster) surface.redraw()
    }

    private fun claim(guiding: Boolean) {
        if (guiding == claimed) return
        claimed = guiding
        runCatching {
            if (guiding) navManager.navigationStarted() else navManager.navigationEnded()
        }
    }

    /**
     * NF-4 — the instrument cluster and any heads-up display have their own
     * feed, separate from [routingInfo]'s card. Must follow [claim]: the host
     * throws if navigation was never started.
     */
    private fun updateClusterTrip(state: GuidanceState) {
        if (!claimed) return
        val trip = (state as? GuidanceState.Guiding)?.let { clusterTrip(it.progress) } ?: return
        runCatching { navManager.updateTrip(trip) }
    }

    /** Stop means stop. Popping the screen used to leave the drive running. */
    private fun stopDrive() {
        val p = ports
        if (p == null) {
            screenManager.pop()
            return
        }
        lifecycleScope.launch {
            runCatching { p.stopDrive() }
            screenManager.pop()
        }
    }

    override fun onGetTemplate(): Template {
        val state = GuidanceHub.state.value
        val builder = NavigationTemplate.Builder()
            .setActionStrip(actionStripFor(state))

        if (!isCluster) {
            builder.setMapActionStrip(
                ActionStrip.Builder()
                    .addAction(Action.PAN)
                    .build(),
            )
            builder.setPanModeListener(object : androidx.car.app.navigation.model.PanModeListener {
                override fun onPanModeChanged(isInPanMode: Boolean) {
                    invalidate()
                }
            })
        }

        navigationInfoFor(state)?.let { builder.setNavigationInfo(it) }
        (state as? GuidanceState.Guiding)?.progress?.let {
            builder.setDestinationTravelEstimate(travelEstimate(it))
        }
        return builder.build()
    }

    /**
     * Смуга дій за станом — бо кнопка мусить робити те, що обіцяє.
     *
     * Раніше смуга будувалась ДО `when` і завжди несла «Зупинити». У спокої
     * зупиняти було нічого: кнопка просто закривала екран, лишаючи водія без
     * жодного виходу вперед.
     *
     * Смуга обовʼязкова, і це не наш вибір: `NavigationTemplate.build()` кидає
     * «Action strip for this template must be set», а `ActionStrip.build()` —
     * «Action strip must contain at least one action» (перевірено в байткоді
     * androidx.car.app 1.4.0). Тому «нічого» тут нездійсненне; питання лише в
     * тому, що саме сказати.
     */
    private fun actionStripFor(state: GuidanceState): ActionStrip {
        val action = when (carActionChoice(state, isCluster, ports != null)) {
            CarActionChoice.STOP -> stopAction()
            CarActionChoice.WHERE_TO -> whereToAction()
            CarActionChoice.APP_ICON -> Action.APP_ICON
        }
        return ActionStrip.Builder().addAction(action).build()
    }

    private fun stopAction(): Action = Action.Builder()
        .setTitle(CarCopy.STOP_DRIVE)
        .setOnClickListener { stopDrive() }
        .build()

    /**
     * У спокої на головному дисплеї — «Куди?», що відкриває сітку категорій.
     * Джерело доведене: та сітка стоїть на `search()` і `placesKnown()` і вміє
     * чесно назвати всі чотири стани незнання ([Nearest]).
     *
     * На приладовій панелі — піктограма застосунку. Не з ліні: сесія кластера
     * має право показувати ЛИШЕ NavigationTemplate, тож штовхнути звідти
     * сітку (GridTemplate) не можна, а кермо не має дотику взагалі. Кнопка,
     * до якої не дотягнутись, — мертва кнопка; піктограма ж нічого не
     * обіцяє, тому займає обовʼязкове місце, не брешучи.
     */
    private fun whereToAction(): Action {
        // carActionChoice уже довела, що порт є; сторожа лишаю, бо «уже
        // довела» — це те саме речення, яким гине половина падінь.
        val p = ports ?: return Action.APP_ICON
        return Action.Builder()
            .setTitle(CarCopy.WHERE_TO)
            .setOnClickListener { screenManager.push(CarHomeScreen(carContext, p)) }
            .build()
    }

    private fun travelEstimate(p: GuidanceProgress): TravelEstimate =
        travelEstimateOf(p)

}

/**
 * Відстань — завжди. Годинник — лише коли хвилини на чомусь стоять.
 *
 * Відстань, що лишилась, виміряна: геометрія маршруту справжня. Час — ні,
 * і не завжди однаково «ні». `ASSUMED` означає, що весь маршрут пораховано
 * за ОДНІЄЮ пласкою вгаданою швидкістю (7,5 м/с), бо в пакеті доріг немає
 * тегів — а це рівно наш випадок на кожному спеченому пакеті, який зараз
 * лежить на пристроях. Малювати над таким числом точну годину — «14:37» —
 * означає забрати в людини рішення «встигну / не встигну» і підмінити його
 * вигадкою з інтонацією впевненості.
 *
 * Тому при `ASSUMED` [TravelEstimate.setRemainingTimeSeconds] лишається на
 * своєму типовому `REMAINING_TIME_UNKNOWN`: хазяїн малює відстань і не малює
 * годинника. Той самий прийом, яким уже живе [clusterEstimate].
 *
 * Порожня основа — НЕ `ASSUMED`: так відповідає мережевий рушій, який
 * порахував хвилини по справжніх швидкостях свого графа й просто не сказав,
 * по яких. Це обчислення, а не здогад, і годинник йому належить.
 *
 * МЕЖА: те, що хазяїн при `REMAINING_TIME_UNKNOWN` ховає саме годинник, а не
 * всю картку, — прочитане з API, а не побачене на склі. Перевірити на DHU.
 */
internal fun travelEstimateOf(
    p: GuidanceProgress,
    nowMs: Long = System.currentTimeMillis(),
): TravelEstimate {
    val guessed = p.speedBasis == GUESSED_BASIS
    val arrivalMs = if (guessed) nowMs else nowMs + (p.remainingDurationS * 1000).toLong()
    val b = TravelEstimate.Builder(
        distance(p.remainingDistanceM),
        androidx.car.app.model.DateTimeWithZone.create(arrivalMs, java.util.TimeZone.getDefault()),
    )
    if (!guessed) {
        b.setRemainingTimeSeconds(p.remainingDurationS.toLong().coerceAtLeast(0L))
    }
    return b.build()
}

/**
 * Імʼя стану, а не рядок з повітря: його пише
 * `AppContainer` як `RoadProfile.SpeedBasis.ASSUMED.name` у
 * `RouteVariant.speedBasis`. Тримається тут константою, щоб перейменування
 * стану не розʼїхалось із екраном мовчки.
 */
internal const val GUESSED_BASIS = "ASSUMED"

/** Metres under a kilometre, one decimal above — what a driver reads. */
internal fun distance(metres: Double): Distance = when {
    metres < 1_000 -> Distance.create((metres / 10.0).toInt() * 10.0, Distance.UNIT_METERS)
    else -> Distance.create(metres / 1_000.0, Distance.UNIT_KILOMETERS)
}

/**
 * What the cluster gets, via `NavigationManager.updateTrip` — the next
 * manoeuvre and the distance to it, and nothing else. The routing card
 * already carries the interchange look-ahead (the current step's "next"
 * step); a display behind the wheel, read in less time than the console, has
 * no room to repeat it.
 */
internal fun clusterTrip(progress: GuidanceProgress?, locale: Locale = Locale.getDefault()): Trip =
    if (progress == null) {
        Trip.Builder().setLoading(true).build()
    } else {
        Trip.Builder().addStep(clusterStep(progress, locale), clusterEstimate(progress)).build()
    }

/**
 * The road name when there is one, else the maneuver. A transit step carries
 * its spoken Ukrainian sentence as the road name, so it is cued as an instruction.
 */
internal fun stepCue(type: String?, modifier: String?, roadName: String?, exitNumber: Int?, locale: Locale): String = when {
    type?.startsWith(TransitSteps.PREFIX) == true -> maneuverShort(type, modifier, roadName, exitNumber, locale)
    !roadName.isNullOrBlank() -> roadName
    else -> maneuverShort(type, modifier, null, exitNumber, locale)
}

internal fun clusterStep(p: GuidanceProgress, locale: Locale = Locale.getDefault()): Step {
    val cue = stepCue(p.maneuverType, p.maneuverModifier, p.roadName, p.exitNumber, locale)
    return Step.Builder(cue)
        .setManeuver(carManeuver(p.maneuverType, p.maneuverModifier, p.exitNumber))
        .build()
}

/**
 * Що саме несе навігаційна картка шаблону — і коли не несе нічого.
 *
 * Винесено з екрана НЕ заради краси: екран потребує CarContext, тож у
 * звичайному тесті його не збудувати. Рішення ж перевірити треба, а
 * дзеркало рішення (окрема копія тієї самої логіки в тесті) доводить лише
 * те, що я вмію двічі написати однакове. Тому це вузол: інших джерел
 * картки в екрана немає.
 *
 * `null` = картки немає. Це стан спокою, і він єдиний, у якому ми чесно
 * не маємо що сказати: `Idle` приходить і коли маршруту не просили, і коли
 * він не запустився, а ці два світи вимагають від водія протилежного.
 */
internal fun navigationInfoFor(
    state: GuidanceState,
    locale: Locale = Locale.getDefault(),
): NavigationTemplate.NavigationInfo? =
    when (state) {
        // Спінер лишається РІВНО тут: маршрут справді рахується.
        is GuidanceState.Guiding ->
            state.progress?.let { routingInfo(it, locale) }
                ?: RoutingInfo.Builder().setLoading(true).build()
        GuidanceState.Arrived ->
            RoutingInfo.Builder()
                .setCurrentStep(
                    Step.Builder(CarCopy.ARRIVED)
                        .setManeuver(Maneuver.Builder(Maneuver.TYPE_DESTINATION).build())
                        .build(),
                    Distance.create(0.0, Distance.UNIT_METERS),
                )
                .build()
        GuidanceState.Idle -> null
    }

/** Єдина дія обовʼязкової смуги — і чому саме вона. Див. [CarNavigationScreen.actionStripFor]. */
internal enum class CarActionChoice { STOP, WHERE_TO, APP_ICON }

internal fun carActionChoice(
    state: GuidanceState,
    isCluster: Boolean,
    hasPorts: Boolean,
): CarActionChoice = when {
    state !is GuidanceState.Idle -> CarActionChoice.STOP
    isCluster || !hasPorts -> CarActionChoice.APP_ICON
    else -> CarActionChoice.WHERE_TO
}

internal fun routingInfo(p: GuidanceProgress, locale: Locale = Locale.getDefault()): RoutingInfo {
        val d = distance(p.distanceToManeuverM)
        val cueText = stepCue(p.maneuverType, p.maneuverModifier, p.roadName, p.exitNumber, locale)

        val step = Step.Builder(cueText)
            .setManeuver(carManeuver(p.maneuverType, p.maneuverModifier, p.exitNumber))
            .build()

        val b = RoutingInfo.Builder().setCurrentStep(step, d)
        // The interchange case: a driver shown only the first turn takes the
        // ramp and misses the fork a few hundred metres later.
        if (p.nextManeuverType != null) {
            val nextCue = stepCue(p.nextManeuverType, p.nextManeuverModifier, p.nextManeuverRoadName, p.nextExitNumber, locale)
            b.setNextStep(
                Step.Builder(nextCue)
                    .setManeuver(
                        carManeuver(p.nextManeuverType, p.nextManeuverModifier, p.nextExitNumber),
                    )
                    .build(),
            )
        }
        return b.build()
    }


/**
 * Distance to the manoeuvre is measured; time to it is not — only time to the
 * final destination is. Leaving [TravelEstimate.Builder.setRemainingTimeSeconds]
 * at its default (`REMAINING_TIME_UNKNOWN`) tells the host to show no clock
 * rather than a number nobody calculated.
 */
internal fun clusterEstimate(p: GuidanceProgress, nowMs: Long = System.currentTimeMillis()): TravelEstimate =
    TravelEstimate.Builder(
        distance(p.distanceToManeuverM),
        androidx.car.app.model.DateTimeWithZone.create(nowMs, java.util.TimeZone.getDefault()),
    ).build()

/** OSRM maneuver -> car icon. A wrong arrow sends a driver into a turn. */
internal fun maneuverTypeOf(type: String?, modifier: String?): Int {
    val m = modifier?.lowercase().orEmpty()
    return when (type?.lowercase()) {
        "turn" -> when {
            m.contains("sharp left") -> Maneuver.TYPE_TURN_SHARP_LEFT
            m.contains("sharp right") -> Maneuver.TYPE_TURN_SHARP_RIGHT
            m.contains("slight left") -> Maneuver.TYPE_TURN_SLIGHT_LEFT
            m.contains("slight right") -> Maneuver.TYPE_TURN_SLIGHT_RIGHT
            m.contains("left") -> Maneuver.TYPE_TURN_NORMAL_LEFT
            m.contains("right") -> Maneuver.TYPE_TURN_NORMAL_RIGHT
            m.contains("uturn") || m.contains("u-turn") -> Maneuver.TYPE_U_TURN_LEFT
            else -> Maneuver.TYPE_STRAIGHT
        }
        "merge" -> Maneuver.TYPE_MERGE_SIDE_UNSPECIFIED
        "fork" -> if (m.contains("left")) Maneuver.TYPE_FORK_LEFT else Maneuver.TYPE_FORK_RIGHT
        // Reached only when the exit number is missing: without it the CCW
        // types throw, and a plain ring is better than a crash.
        "roundabout", "rotary", "roundabout turn", "exit roundabout" ->
            Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW
        "on ramp", "onramp" -> Maneuver.TYPE_ON_RAMP_NORMAL_RIGHT
        "off ramp", "offramp" -> Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT
        "arrive" -> Maneuver.TYPE_DESTINATION
        "depart" -> Maneuver.TYPE_DEPART
        else -> Maneuver.TYPE_STRAIGHT
    }
}

/**
 * Builds the host's maneuver, with the two things a roundabout cannot be drawn
 * without: which way the ring turns, and which exit to take.
 *
 * Right-hand traffic circulates COUNTER-clockwise, so the CW type drew a ring
 * running against the driver — an arrow contradicting a voice that said
 * «третій зʼїзд». And the CCW types REQUIRE an exit number: building one
 * without it throws, which on a car screen is a crash mid-drive, so a
 * roundabout with no exit falls back to a plain arrow.
 */
internal fun carManeuver(type: String?, modifier: String?, exitNumber: Int?): Maneuver {
    val roundabout = type?.lowercase() in ROUNDABOUT_TYPES
    val builder = if (roundabout && exitNumber != null) {
        Maneuver.Builder(Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW)
            .setRoundaboutExitNumber(exitNumber.coerceIn(1, 9))
    } else {
        Maneuver.Builder(maneuverTypeOf(type, modifier))
    }
    return runCatching { builder.build() }
        .getOrElse {
            Maneuver.Builder(Maneuver.TYPE_STRAIGHT).build()
        }
}

private val ROUNDABOUT_TYPES =
    setOf("roundabout", "rotary", "roundabout turn", "exit roundabout")
