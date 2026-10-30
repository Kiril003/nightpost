package local.phantom.companion.core.nav

import local.phantom.companion.core.net.map.LaneInfo
import local.phantom.companion.core.net.map.RouteStep
import local.phantom.companion.core.net.map.RouteVariant
import java.util.Locale
import kotlin.math.roundToInt

/**
 * NAV-2 — one live guidance run over one route variant: map-matches each
 * GPS fix, decides which maneuver is next, emits Ukrainian voice cues at
 * profile-tuned distances (each exactly once), watches the posted limit,
 * detects arrival and — with hysteresis — leaving the route. Pure Kotlin
 * with an injected clock, so every rule here is unit-tested on the JVM.
 */
class GuidanceSession private constructor(
    private val variant: RouteVariant,
    private val path: RoutePath,
    /** Posted limit (km/h) at a fraction along the route, or null when untagged. */
    private val limitAt: (fraction: Double) -> Int?,
    private val darkAt: (lat: Double, lon: Double, epochMs: Long) -> Boolean,
) {
    /** What one fix produced: new progress, any cues to speak, lifecycle flags. */
    data class Update(
        val progress: GuidanceProgress,
        val cues: List<NavCue>,
        val arrived: Boolean,
        val requestReroute: Boolean,
        /**
         * ON-TIME VOICE — the final-tier phrase of the maneuver that just
         * became «next», emitted once per step, minutes before it fires.
         * The voice layer pre-synthesizes it so the premium voice can speak
         * the turn with ~zero first-audio latency. Advisory: dropping it
         * costs nothing but the nicer voice.
         */
        val prewarm: String? = null,
        /**
         * Слід (див. [GuidanceTrace]): наскільки збоку від маршруту цей фікс і
         * скільки таких поспіль. Банер їх не показує — рішення про перебудову
         * стоїть саме на них, тож без них «чому воно не перебудувало» лишається
         * здогадом. Мають типове значення: жодне наявне місце побудови не
         * зобовʼязане про них знати.
         */
        val offMeters: Double = 0.0,
        val offStreak: Int = 0,
    )

    /**
     * Cue tiers are FLOORS, not distances: the effective trigger distance of
     * tier *i* is `max(floor[i], speed × lead[i])`. A fixed 60 m final cue is
     * fine on foot and a lie at 90 km/h — the words land as the wheel should
     * already be turning. Time-based leads keep the voice ahead of the car at
     * any speed; the floors keep it sane at a crawl. The whole table is
     * re-picked per fix from the measured gait — see [paceFor].
     */

    /** stepIndex * MAX_TIERS + tier rank (0 = tightest) of every cue spoken. */
    private val announced = HashSet<Int>()
    private var offStreak = 0

    /**
     * APPLE-LOCK wave 2 — when the CURRENT off-route run began. The streak
     * alone was calibrated at 1 Hz; with the chip now permitted to deliver
     * 2–4 Hz under navigation grade, three fixes can be 0.75 s — indistinct
     * from one bad corner of multipath. A reroute needs both: enough fixes
     * AND enough sustained TIME off the corridor.
     */
    private var offSinceMs: Long? = null
    private var overspeedSinceMs: Long? = null
    private var lastOverspeedCueMs = 0L

    /** The last progress computed from a fix worth believing — what the
     *  banner holds while an untrusted estimate wanders off the corridor. */
    private var heldProgress: GuidanceProgress? = null

    /** The matcher's memory: progress-along of the last believed snap.
     *  Constrains the next snap to a window — see [RoutePath.snapNear]. */
    private var lastAlongM: Double? = null

    /** The last step a prewarm hint was emitted for — once per step. */
    @Volatile
    private var prewarmedStepIdx = -1

    @Volatile
    private var landmarks: Map<Int, List<StepHint>> = emptyMap()
    private var chosenStep = -1
    private var chosenAtMs = 0L
    private var chosenHint: StepHint? = null

    /** Орієнтири приходять після старту; вже прогріта фраза без них — застаріла. */
    fun attachLandmarks(hints: Map<Int, List<StepHint>>) {
        landmarks = hints
        chosenStep = -1
        prewarmedStepIdx = -1
    }

    private fun hintFor(stepIdx: Int, nowMs: Long, lat: Double, lon: Double): StepHint? {
        if (stepIdx != chosenStep || nowMs - chosenAtMs > HINT_RECHECK_MS) {
            chosenHint = TurnLandmarks.choose(landmarks[stepIdx], nowMs) { darkAt(lat, lon, nowMs) }
            chosenStep = stepIdx
            chosenAtMs = nowMs
        }
        return chosenHint
    }

    private fun phraseFor(
        stepIdx: Int,
        step: RouteStep,
        distanceM: Int?,
        nowMs: Long,
        lat: Double,
        lon: Double,
        isIntermediate: Boolean = false,
    ): String {
        val hint = hintFor(stepIdx, nowMs, lat, lon)
        val chain = TurnLandmarks.chainedNext(variant, path, stepIdx)
        val following = if (chain == null && (step.type == "fork" || step.modifier?.startsWith("slight") == true)) {
            val next = variant.steps.getOrNull(stepIdx + 1)
            val gap = (path.stepAlongM.getOrNull(stepIdx + 1) ?: 0.0) - (path.stepAlongM.getOrNull(stepIdx) ?: 0.0)
            val nextHint = TurnLandmarks.choose(landmarks[stepIdx + 1], nowMs) { darkAt(lat, lon, nowMs) }
                ?.takeIf { it.relation == LandmarkRelation.AFTER || it.relation == LandmarkRelation.AT }
            if (next != null && nextHint != null && TurnLandmarks.isTurn(next) && gap <= FOLLOWING_MAX_M) next to nextHint else null
        } else {
            null
        }
        return guidancePhraseUk(step, hint, distanceM, chain, isIntermediate, following)
    }

    /** Timestamp of the previous fix to compute delta-time for speed/tunnel look-ahead. */
    private var lastFixEpochMs = 0L

    /**
     * One judged fix. [trusted] is the EW verdict (sky-fused, small doubt) —
     * see [NavFix.trusted] for the field failure that demanded it:
     *
     *  - trusted        → full authority: progress, cues, off-route streak,
     *                     reroute, arrival.
     *  - untrusted NEAR the corridor → dead reckoning maintaining progress
     *                     through a blackout, exactly as designed: progress
     *                     and maneuver cues advance; the off-route streak,
     *                     reroute and arrival are frozen.
     *  - untrusted FAR from the corridor → a runaway estimate. The session
     *                     HOLDS its last believed progress, speaks nothing,
     *                     reroutes nowhere. The operator keeps his route.
     */
    fun onFix(
        lat: Double,
        lon: Double,
        speedMps: Float?,
        nowMs: Long,
        trusted: Boolean = true,
        /** Reported 68 % radius of this fix, metres. Null = unknown. */
        accuracyM: Float? = null,
        gait: NavGait = NavGait.UNKNOWN,
        /**
         * Напрямок РЕАЛЬНОГО руху, градуси; null — невідомий.
         *
         * Скарга власника: «їде іншою дорогою — притягує на ту, де маршрут».
         * Сама лише відстань цього не ловить: сусідня вулиця за 25 м ближча за
         * будь-який розумний поріг. Курс ловить — той, хто їде ПОПЕРЕК
         * коридору, на ньому не перебуває, хоч би лінія була поруч.
         */
        motionBearingDeg: Float? = null,
    ): Update {
        val pace = paceFor(variant.profile, gait)
        val dtS = if (lastFixEpochMs > 0L) ((nowMs - lastFixEpochMs) / 1000.0).coerceIn(0.1, 120.0) else 1.0
        lastFixEpochMs = nowMs
        val v = (speedMps ?: 0f).coerceAtLeast(0f).toDouble()
        // Progress-monotonic map-matching with velocity-scaled ahead window:
        // expands search window through long tunnels or high speed stretches.
        val dynamicAheadM = maxOf(SNAP_AHEAD_M, v * dtS * 1.5)
        val windowed = lastAlongM?.let { path.snapNear(lat, lon, it, SNAP_BACK_M, dynamicAheadM) }
        val snap = when {
            windowed == null -> path.snap(lat, lon)
            windowed.offMeters > SNAP_ESCAPE_OFF_M || dtS > 10.0 -> {
                val global = path.snap(lat, lon)
                if (global.offMeters < windowed.offMeters * 0.8 && global.alongM >= (lastAlongM ?: 0.0) - SNAP_BACK_M) {
                    global
                } else {
                    windowed
                }
            }
            else -> windowed
        }

        if (!trusted && snap.offMeters > UNTRUSTED_HOLD_OFF_M) {
            heldProgress?.let {
                return Update(
                    it,
                    emptyList(),
                    arrived = false,
                    requestReroute = false,
                    offMeters = snap.offMeters,
                    offStreak = offStreak,
                )
            }
            // No believed progress yet (blackout from the first metre): fall
            // through to compute one so the banner is not empty, but the
            // lifecycle gates below all require [trusted].
        }

        // QA FIELD FAILURE (стоячи вдома) — «off-route» must survive indoor
        // GPS scatter. The corridor threshold is inflated by the fix's own
        // reported doubt: a fix that admits ±80 m cannot testify to a 50 m
        // departure. And a STATIONARY operator cannot leave a route at all —
        // the streak only grows while there is genuine movement to explain it.
        val movingEnough = (speedMps ?: 0f) >= STREAK_MIN_SPEED_MPS
        val offRouteLimit = maxOf(
            pace.offRouteM,
            (accuracyM ?: 0f).toDouble() * ACCURACY_OFF_SCALE,
            if (!movingEnough) 50.0 else pace.offRouteM,
        )
        // Дві незалежні підстави, і друга не має порога відстані взагалі:
        // рух під великим кутом до коридору — це інша дорога, а не похибка.
        val headingGap = motionBearingDeg
            ?.takeIf { movingEnough }
            ?.let { headingGapDeg(it.toDouble(), snap.bearingDeg) }
        val crossingCorridor = headingGap != null && headingGap > OFF_ROUTE_HEADING_DEG
        val offRoute = snap.offMeters > offRouteLimit || crossingCorridor
        // The streak that triggers a reroute counts only fixes with the
        // authority to testify; an untrusted run neither grows it nor clears
        // it (a wandering estimate proves nothing about the operator), and a
        // standing operator clears it (scatter proves nothing either).
        offStreak = when {
            !trusted -> offStreak
            offRoute && movingEnough -> offStreak + 1
            !movingEnough -> 0
            offRoute -> offStreak
            else -> 0
        }
        // The clock mirrors the streak's own discipline: it arms on the
        // first trusted MOVING off-route fix, resets on stationary/standing,
        // and clears the moment a trusted fix is back on the corridor.
        offSinceMs = when {
            !trusted -> offSinceMs
            offRoute && movingEnough -> offSinceMs ?: nowMs
            !movingEnough -> null
            offRoute -> offSinceMs
            else -> null
        }
        val requestReroute = trusted && movingEnough && offStreak >= OFF_ROUTE_STREAK &&
            offSinceMs.let { it != null && nowMs - it >= pace.offRouteSustainMs }

        val stepIdx = path.nextStepIndex(snap.alongM)
        val step = variant.steps.getOrNull(stepIdx)
        val stepAlong = path.stepAlongM.getOrNull(stepIdx) ?: path.totalDistM
        val distToMan = (stepAlong - snap.alongM).coerceAtLeast(0.0)
        val remaining = (path.totalDistM - snap.alongM).coerceAtLeast(0.0)
        val remainingDur = remainingDurationS(
            path.totalDurS - path.durationAtM(snap.alongM),
            remaining,
            pace,
        )
        val fraction = (snap.alongM / path.totalDistM).coerceIn(0.0, 1.0)
        val limit = limitAt(fraction)
        val speedKmh = speedMps?.takeIf { it >= 0f }?.let { (it * 3.6f).roundToInt() }

        val cues = mutableListOf<NavCue>()

        // Arrival is a lifecycle decision — an inertial estimate drifting
        // across the destination must not end a live drive.
        val arrived = trusted && remaining <= pace.arriveRadiusM && snap.offMeters <= ARRIVE_OFF_MAX_M
        if (arrived) {
            cues += NavCue(
                text = "Ви на місці. Навігацію завершено.",
                urgency = NavCue.Urgency.NOW,
                key = "arrival",
                bornMs = nowMs,
                ttlMs = ARRIVAL_CUE_TTL_MS,
            )
        } else if (!offRoute && step != null) {
            // The tightest crossed-but-unannounced tier speaks; every wider
            // one is marked spoken too, so a GPS jump never causes a burst.
            // Tier reach scales with speed — and the lead the voice needs is
            // not just the operator's reaction: the synthesis pipeline eats
            // [TTS_PIPELINE_LEAD_S] before the first sample leaves the
            // speaker, so a moving trigger fires that much of travel earlier.
            val v = (speedMps ?: 0f).coerceAtLeast(0f).toDouble()
            var tierIdx = -1
            for (i in pace.tierFloorsM.indices) {
                val lead = pace.tierLeadsS[i]
                val reach = if (lead > 0.0) v * (lead + TTS_PIPELINE_LEAD_S) else 0.0
                if (distToMan <= maxOf(pace.tierFloorsM[i], reach)) tierIdx = i
            }
            if (tierIdx >= 0) {
                // Ranked from the tightest tier, so a gait change mid-step
                // (walking out of a car) cannot re-open a tier already spoken.
                val rank = pace.tierFloorsM.lastIndex - tierIdx
                val fresh = announced.add(stepIdx * MAX_TIERS + rank)
                for (r in rank + 1 until MAX_TIERS) announced.add(stepIdx * MAX_TIERS + r)
                if (fresh) {
                    // The FINAL tier is the wheel's cue: bare imperative, no
                    // distance — the way Apple's last call is «Turn right
                    // onto X». Distance-free, its exact words are known the
                    // moment the step becomes next, which is what makes it
                    // prewarmable; and it may preempt any lesser utterance,
                    // expiring fast (spoken late it describes a turn already
                    // behind the car). Wider tiers speak the honest distance.
                    // Keyed per step, so a tighter tier evicts a waiting
                    // wider one.
                    val finalTier = rank == 0
                    val isTrivialStraight = (step.type in setOf("continue", "new name") || (step.type == "turn" && step.modifier in setOf("straight", null))) &&
                        step.type !in setOf("fork", "off ramp", "on ramp", "roundabout", "rotary", "exit roundabout", "depart", "arrive", "uturn")

                    // Smart Co-Pilot: do not spam the driver with advance countdown alerts ("Через 600 метрів прямо", "Через 250 метрів прямо")
                    // when simply continuing straight along the same road corridor!
                    val shouldSpeak = if (TransitSteps.isTransit(step)) {
                        finalTier
                    } else if (isTrivialStraight) {
                        finalTier && (stepIdx == 0 || !step.roadName.isNullOrBlank())
                    } else {
                        true
                    }

                    if (shouldSpeak) {
                        val isIntermediate = step.type == "arrive" && stepIdx < variant.steps.lastIndex
                        cues += NavCue(
                            text = phraseFor(
                                stepIdx, step, if (finalTier) null else distToMan.roundToInt(),
                                nowMs, lat, lon, isIntermediate,
                            ),
                            urgency = if (finalTier) NavCue.Urgency.NOW else NavCue.Urgency.SOON,
                            key = "step-$stepIdx",
                            bornMs = nowMs,
                            ttlMs = if (finalTier) FINAL_CUE_TTL_MS else APPROACH_CUE_TTL_MS,
                        )
                    }
                }
            }
        }

        // Soft overspeed: only after it holds for a few seconds, repeated
        // at most every half-minute — a nudge, not a nag.
        if (limit != null && speedKmh != null && speedKmh > limit + overspeedToleranceKmh(limit)) {
            val since = overspeedSinceMs ?: nowMs.also { overspeedSinceMs = it }
            if (nowMs - since >= OVERSPEED_HOLD_MS && nowMs - lastOverspeedCueMs >= OVERSPEED_REPEAT_MS) {
                lastOverspeedCueMs = nowMs
                cues += NavCue(
                    text = "Обережно: $speedKmh при ліміті $limit.",
                    urgency = NavCue.Urgency.INFO,
                    key = "overspeed",
                    bornMs = nowMs,
                    ttlMs = OVERSPEED_CUE_TTL_MS,
                )
            }
        } else {
            overspeedSinceMs = null
        }

        // ON-TIME VOICE — the moment a maneuver becomes «next», its
        // final-tier words are already known; say so once, so the premium
        // voice can have them synthesized long before the wheel needs them.
        val prewarm = if (!arrived && step != null && stepIdx != prewarmedStepIdx) {
            prewarmedStepIdx = stepIdx
            phraseFor(stepIdx, step, null, nowMs, lat, lon)
        } else {
            null
        }

        // NAV-VISUAL — what the banner needs to stop being a single line of
        // text. The step AFTER the next one is not a decoration: on a
        // motorway interchange the two maneuvers arrive so close together
        // that knowing only the first one is knowing nothing. And a bar
        // that fills as the turn approaches says «how soon» faster than a
        // number the operator has to read while driving.
        val nextStep = variant.steps.getOrNull(stepIdx + 1)
        val nextStepAlong = path.stepAlongM.getOrNull(stepIdx + 1) ?: path.totalDistM
        val stepStartAlong = if (stepIdx > 0) {
            path.stepAlongM.getOrNull(stepIdx - 1) ?: 0.0
        } else {
            0.0
        }
        val stepSpan = stepAlong - stepStartAlong
        val progress = GuidanceProgress(
            snappedLat = snap.lat,
            snappedLon = snap.lon,
            bearingDeg = snap.bearingDeg,
            curvatureDegPerM = path.curvatureAheadDegPerM(snap.alongM, CURVATURE_WINDOW_M),
            maneuverType = step?.type,
            maneuverModifier = step?.modifier,
            roadName = step?.roadName?.takeIf { it.isNotBlank() },
            distanceToManeuverM = distToMan,
            remainingDistanceM = remaining,
            remainingDurationS = remainingDur,
            speedKmh = speedKmh,
            limitKmh = limit,
            speedBasis = variant.speedBasis,
            offRoute = offRoute,
            nextManeuverType = nextStep?.type,
            nextManeuverModifier = nextStep?.modifier,
            nextManeuverRoadName = nextStep?.roadName?.takeIf { it.isNotBlank() },
            distanceBetweenManeuversM = (nextStepAlong - stepAlong).coerceAtLeast(0.0),
            // A zero-length step would divide by zero; it also means the
            // operator is standing exactly on the maneuver, i.e. full.
            stepFraction = if (stepSpan > 0.0) {
                ((snap.alongM - stepStartAlong) / stepSpan).coerceIn(0.0, 1.0).toFloat()
            } else {
                1f
            },
            routeFraction = fraction.toFloat(),
            exitNumber = step?.exitNumber,
            turnAngleDeg = step?.turnAngleDeg,
            nextExitNumber = nextStep?.exitNumber,
            nextTurnAngleDeg = nextStep?.turnAngleDeg,
            stepIndex = stepIdx,
            landmark = if (step != null && !arrived) hintFor(stepIdx, nowMs, lat, lon) else null,
            lanes = step?.lanes.orEmpty(),
            laneApproach = step?.lanes?.isNotEmpty() == true && !offRoute &&
                distToMan <= laneWindowM(v),
        )
        // Only a fix worth believing may become the position the session
        // holds to during the next blackout — and the matcher's memory.
        if (trusted || snap.offMeters <= UNTRUSTED_HOLD_OFF_M) {
            heldProgress = progress
            lastAlongM = snap.alongM
        }
        return Update(
            progress = progress,
            cues = cues,
            arrived = arrived,
            requestReroute = requestReroute,
            prewarm = prewarm,
            offMeters = snap.offMeters,
            offStreak = offStreak,
        )
    }

    companion object {
        private const val MAX_TIERS = 8
        private const val HINT_RECHECK_MS = 60_000L
        private const val FOLLOWING_MAX_M = 300.0
        private const val OFF_ROUTE_STREAK = 3

        /** Corridor threshold inflates by this × the fix's reported accuracy:
         *  scatter that the fix itself admits to is not a departure. */
        private const val ACCURACY_OFF_SCALE = 1.5

        /** Below this the operator is standing; standing cannot leave a route. */
        private const val STREAK_MIN_SPEED_MPS = 1.2f

        /** The matcher's window around the last believed progress. Backwards
         *  covers a missed turn driven back; forwards covers a fast segment
         *  between 1 Hz fixes with margin. */
        private const val SNAP_BACK_M = 300.0
        private const val SNAP_AHEAD_M = 800.0

        /** A windowed snap worse than this earns one global retry, accepted
         *  only when decisively better (a real teleport: ferry, towed car). */
        private const val SNAP_ESCAPE_OFF_M = 200.0

        /** An untrusted estimate farther than this from the corridor is a
         *  runaway, not the operator — hold the last believed progress. */
        private const val UNTRUSTED_HOLD_OFF_M = 120.0
        private const val ARRIVE_OFF_MAX_M = 60.0
        private const val OVERSPEED_HOLD_MS = 4_000L
        private const val OVERSPEED_REPEAT_MS = 30_000L

        /**
         * ON-TIME VOICE — what the synthesis pipeline costs before the first
         * sample is audible, folded into every speed-scaled trigger. Sized
         * for the urgent route's worst honest case (System TTS cold-ish),
         * not Supertonic's: a cue that fires early by a second is fine, one
         * that lands after the wheel is not.
         */
        const val TTS_PIPELINE_LEAD_S = 1.5

        /** The camera reads the road's bend over this much corridor ahead. */
        private const val CURVATURE_WINDOW_M = 120.0

        /**
         * PRESENCE — how far ahead of a maneuver the lane band belongs. A
         * floor plus a speed lead, the same discipline as the voice tiers:
         * 150 m is eleven seconds in town and six on a motorway, and a driver
         * who learns which lane to hold six seconds out has learned it late.
         * Capped so the band never runs back into the previous junction.
         */
        const val LANE_WINDOW_FLOOR_M = 150.0
        const val LANE_WINDOW_LEAD_S = 10.0
        const val LANE_WINDOW_MAX_M = 250.0

        fun laneWindowM(speedMps: Double): Double =
            (speedMps * LANE_WINDOW_LEAD_S).coerceIn(LANE_WINDOW_FLOOR_M, LANE_WINDOW_MAX_M)

        /** A final-tier cue spoken later than this describes the past. */
        const val FINAL_CUE_TTL_MS = 10_000L
        const val APPROACH_CUE_TTL_MS = 20_000L
        const val OVERSPEED_CUE_TTL_MS = 8_000L
        const val ARRIVAL_CUE_TTL_MS = 30_000L

        fun overspeedToleranceKmh(limit: Int): Int = maxOf(5, limit / 12)

        /** null when the variant has no usable geometry. */
        fun of(
            variant: RouteVariant,
            darkAt: (Double, Double, Long) -> Boolean = { _, _, _ -> false },
            limitAt: (Double) -> Int?,
        ): GuidanceSession? {
            val path = RoutePath.of(variant) ?: return null
            return GuidanceSession(variant, path, limitAt, darkAt)
        }
    }
}

/** Live guidance state of one fix, rendered by the banner, strip and shared gaze. */
data class GuidanceProgress(
    val snappedLat: Double,
    val snappedLon: Double,
    val bearingDeg: Double,
    /** |heading change| per metre of corridor just ahead — the camera leans
     *  with it. 0.0 = straight (and the default keeps old call sites). */
    val curvatureDegPerM: Double = 0.0,
    val maneuverType: String?,
    val maneuverModifier: String?,
    val roadName: String?,
    val distanceToManeuverM: Double,
    val remainingDistanceM: Double,
    val remainingDurationS: Double,
    val speedKmh: Int?,
    val limitKmh: Int?,
    val offRoute: Boolean,
    /**
     * NAV-VISUAL — the maneuver AFTER the next one. On an interchange the
     * two arrive within a few hundred metres of each other, and an operator
     * who sees only the first takes the ramp and then misses the fork. All
     * four fields default so no existing construction site has to care.
     */
    val nextManeuverType: String? = null,
    val nextManeuverModifier: String? = null,
    val nextManeuverRoadName: String? = null,
    /** Metres between the upcoming maneuver and the one after it. */
    val distanceBetweenManeuversM: Double = 0.0,
    /** 0..1 — how much of the CURRENT step has been driven. Fills the approach bar. */
    val stepFraction: Float = 0f,
    /** 0..1 — how much of the whole route has been driven. */
    val routeFraction: Float = 0f,
    /**
     * РЕЄСТР — на чому стоїть [remainingDurationS]: `"POSTED"` (знак),
     * `"CLASS"` (клас дороги), `"ASSUMED"` (одна пласка вгадана швидкість)
     * або `""` — коли хвилини порахував мережевий рушій і не сказав як.
     *
     * Несеться сюди рівно заради одного: **точну годину прибуття не можна
     * малювати над вгаданим числом**. Поле, а не обчислення на місці, бо
     * походження хвилин знає лише той, хто будував маршрут, — до екрана воно
     * інакше не доходить.
     *
     * Порожній рядок НЕ дорівнює ASSUMED: рушій мережі рахує по справжніх
     * швидкостях свого графа й просто не звітує про основу. Вгадали — це ми,
     * коли в пакеті доріг немає тегів узагалі.
     */
    val speedBasis: String = "",
    /** Which exit off the roundabout, 1-based. Null when not a roundabout. */
    val exitNumber: Int? = null,
    /** Real heading change of the turn, degrees, negative left. */
    val turnAngleDeg: Float? = null,
    val nextExitNumber: Int? = null,
    val nextTurnAngleDeg: Float? = null,
    /**
     * Котрий крок маршруту зараз попереду, -1 поза супроводом. Банер завжди
     * знав це число, а мапа — ні: маркери маневрів усі однакові, і жоден не
     * каже «цей твій наступний». Без індексу підсвітити правильний неможливо.
     */
    val stepIndex: Int = -1,
    /** Орієнтир наступного маневру; екран каже його мовою застосунку ([landmarkLead]), null — його нема. */
    val landmark: StepHint? = null,
    /**
     * PRESENCE — the lanes on the approach to the maneuver ahead, leftmost
     * first. Empty is the normal case: most of the road is untagged, and an
     * invented lane count is worse than none.
     */
    val lanes: List<LaneInfo> = emptyList(),
    /** True only inside [GuidanceSession.laneWindowM] of a maneuver that HAS
     *  lane data, and only while the corridor holds — the band's own switch. */
    val laneApproach: Boolean = false,
)

/** Short action text for the banner and the shared gaze («поверніть ліворуч на Хрещатик»). */
fun maneuverShortUk(
    type: String?,
    modifier: String?,
    roadName: String?,
    exitNumber: Int? = null,
): String {
    if (type?.startsWith(TransitSteps.PREFIX) == true) return TransitSteps.headUk(type, roadName.orEmpty())
    val action = maneuverActionUk(type ?: "continue", modifier, exitNumber)
    val road = ontoRoadUk(roadName)
    return "$action$road"
}

/** Short action text in English for the screen («Turn left onto Main St»). */
fun maneuverShortEn(
    type: String?,
    modifier: String?,
    roadName: String?,
    exitNumber: Int? = null,
): String {
    if (type?.startsWith(TransitSteps.PREFIX) == true) return TransitSteps.shortEn(type, roadName.orEmpty())
    val action = maneuverActionEn(type ?: "continue", modifier, exitNumber)
    val road = roadName?.trim()?.takeIf { it.isNotEmpty() }
        ?.let { if (type == "arrive") " on $it" else " onto $it" }.orEmpty()
    return "$action$road"
}

/** Only Ukrainian has its own phrasebook; every other language reads English, never Ukrainian. */
private fun Locale.isEnglish(): Boolean = language != "uk"

/** On-screen short phrase in the app language: Ukrainian for `uk`, English otherwise. */
fun maneuverShort(
    type: String?,
    modifier: String?,
    roadName: String?,
    exitNumber: Int? = null,
    locale: Locale = Locale.getDefault(),
): String = if (locale.isEnglish()) {
    maneuverShortEn(type, modifier, roadName, exitNumber)
} else {
    maneuverShortUk(type, modifier, roadName, exitNumber)
}

/** The two on-screen lines of one step: what to do, then the street or the rest of the ride. */
data class ManeuverLines(val action: String, val detail: String?)

/**
 * Banner, step list and mount glance read a step through this. A transit step
 * carries its spoken sentence in [roadName]: the action is its instruction and
 * the detail is what follows, both in the app language.
 */
fun maneuverLines(
    type: String?,
    modifier: String?,
    roadName: String?,
    exitNumber: Int? = null,
    locale: Locale = Locale.getDefault(),
): ManeuverLines = if (type?.startsWith(TransitSteps.PREFIX) == true) {
    ManeuverLines(
        action = maneuverShort(type, modifier, roadName, exitNumber, locale),
        detail = TransitSteps.detail(type, roadName.orEmpty(), locale.isEnglish()),
    )
} else {
    ManeuverLines(
        action = maneuverAction(type ?: "continue", modifier, exitNumber, locale),
        detail = roadName?.takeIf { it.isNotBlank() },
    )
}

/** On-screen action in the app language (banner, step list, mount glance). */
fun maneuverAction(
    type: String,
    modifier: String?,
    exitNumber: Int? = null,
    locale: Locale = Locale.getDefault(),
    isIntermediate: Boolean = false,
): String = if (locale.isEnglish()) {
    maneuverActionEn(type, modifier, exitNumber, isIntermediate)
} else {
    maneuverActionUk(type, modifier, exitNumber, isIntermediate)
}

fun maneuverActionEn(
    type: String,
    modifier: String?,
    exitNumber: Int? = null,
    isIntermediate: Boolean = false,
): String {
    val dir = when (modifier) {
        "left" -> "left"
        "right" -> "right"
        "slight left" -> "slightly left"
        "slight right" -> "slightly right"
        "sharp left" -> "sharply left"
        "sharp right" -> "sharply right"
        "straight" -> "straight"
        "uturn" -> "around"
        else -> null
    }
    fun turnInto(d: String?): String = when (d) {
        null -> "Follow the route"
        "straight" -> "Continue straight"
        "around" -> "Make a U-turn"
        else -> "Turn $d"
    }
    fun steer(verb: String, bare: String): String = when (dir) {
        null, "straight" -> bare
        "around" -> turnInto(dir)
        else -> "$verb $dir"
    }
    val side = when {
        dir?.endsWith("left") == true -> " on the left"
        dir?.endsWith("right") == true -> " on the right"
        else -> ""
    }
    return when (type) {
        "depart" -> "Depart"
        "arrive" -> if (isIntermediate) "Waypoint ahead" else "Destination ahead"
        "turn" -> turnInto(dir)
        "end of road" -> "At the end of the road, ${turnInto(dir).replaceFirstChar { it.lowercase() }}"
        "continue", "new name" -> steer("Continue", "Continue straight")
        "merge" -> steer("Merge", "Merge")
        "on ramp" -> "Take the ramp$side"
        "off ramp" -> "Take the exit$side"
        "fork" -> steer("Keep", "Keep straight")
        "roundabout", "rotary", "roundabout turn", "exit roundabout" ->
            exitNumber?.let { "Take the ${exitOrdinalEn(it)} exit" }
                ?: dir?.let { "At the roundabout, ${turnInto(it).replaceFirstChar { c -> c.lowercase() }}" }
                ?: "Enter the roundabout"
        "uturn" -> "Make a U-turn"
        else -> if (dir != null) turnInto(dir) else "Continue straight"
    }
}

fun exitOrdinalEn(exit: Int): String {
    val suffix = if (exit % 100 in 11..13) {
        "th"
    } else {
        when (exit % 10) {
            1 -> "st"
            2 -> "nd"
            3 -> "rd"
            else -> "th"
        }
    }
    return "$exit$suffix"
}

/**
 * Full spoken cue: «Через 200 метрів поверніть праворуч на Хрещатик».
 * [distanceM] null (or очень близько) → the imperative alone.
 */
fun maneuverPhraseUk(
    type: String,
    modifier: String?,
    roadName: String?,
    distanceM: Int?,
    exitNumber: Int? = null,
    isIntermediate: Boolean = false,
): String {
    val action = maneuverActionUk(type, modifier, exitNumber, isIntermediate)
    val road = ontoRoadUk(roadName)
    return if (distanceM == null || distanceM <= 30) {
        "$action$road"
    } else {
        "Через ${spokenDistanceUk(distanceM)} ${action.replaceFirstChar { it.lowercase() }}$road"
    }
}

/** Imperative for one OSRM maneuver type + modifier, in Ukrainian. */
fun maneuverActionUk(
    type: String,
    modifier: String?,
    exitNumber: Int? = null,
    isIntermediate: Boolean = false,
): String {
    val dir = when (modifier) {
        "left" -> "ліворуч"
        "right" -> "праворуч"
        "slight left" -> "плавно ліворуч"
        "slight right" -> "плавно праворуч"
        "sharp left" -> "різко ліворуч"
        "sharp right" -> "різко праворуч"
        "straight" -> "прямо"
        "uturn" -> "у зворотний бік"
        else -> null
    }
    // «Поверніть прямо» — те, що цей код казав уголос водієві, і те, чого не
    // можна виконати. OSRM ставить `type=turn` на КОЖНОМУ вузлі, де є з чого
    // вибирати, а `modifier=straight` означає «з цього перехрестя — прямо». Це
    // не поворот, і слово «поверніть» тут не описує дію, а суперечить їй.
    fun turnInto(d: String?): String = when (d) {
        null -> "Поверніть за маршрутом"
        "прямо" -> "Далі прямо"
        "у зворотний бік" -> "Розверніться"
        else -> "Поверніть $d"
    }
    return when (type) {
        "depart" -> "Рушаймо"
        "arrive" -> if (isIntermediate) "Проміжна точка попереду" else "Фініш попереду"
        "turn" -> turnInto(dir)
        "end of road" -> if (dir == "прямо") {
            "У кінці дороги — прямо"
        } else {
            "У кінці дороги ${turnInto(dir).replaceFirstChar { it.lowercase() }}"
        }
        "continue", "new name" -> "Продовжуйте ${dir ?: "прямо"}"
        "merge" -> "Приєднайтесь до потоку" + (dir?.let { " $it" } ?: "")
        "on ramp" -> "Виїзд" + (dir?.let { " $it" } ?: "")
        "off ramp" -> "Зʼїзд" + (dir?.let { " $it" } ?: "")
        "fork" -> "Тримайтесь ${dir ?: "прямо"}"
        "roundabout", "rotary", "roundabout turn", "exit roundabout" ->
            // A roundabout has no «right»: the modifier is the bearing of the
            // exit, and saying it aloud contradicts a glyph that draws the
            // whole ring. Drivers are told which exit, and so is this one.
            exitNumber?.let { "На кільці — ${exitOrdinalUk(it)} зʼїзд" }
                ?: "На кільці ${dir ?: "за маршрутом"}"
        "uturn" -> "Розверніться"
        else -> if (dir != null) turnInto(dir) else "Продовжуйте прямо"
    }
}

/** «перший» … «девʼятий» — how an exit is named aloud. */
fun exitOrdinalUk(exit: Int): String = when (exit) {
    1 -> "перший"
    2 -> "другий"
    3 -> "третій"
    4 -> "четвертий"
    5 -> "пʼятий"
    6 -> "шостий"
    7 -> "сьомий"
    8 -> "восьмий"
    else -> "девʼятий"
}

/** «200 метрів», «1 кілометр», «1 кома 2 кілометра» — TTS-friendly, coarsely rounded. */
fun spokenDistanceUk(m: Int): String = when {
    m >= 950 -> {
        val tenths = (m / 100.0).roundToInt()
        val whole = tenths / 10
        val frac = tenths % 10
        if (frac == 0) "$whole ${kmWordUk(whole)}" else "$whole кома $frac кілометра"
    }
    m >= 100 -> "${(m / 50.0).roundToInt() * 50} метрів"
    else -> "${(m / 10.0).roundToInt() * 10} метрів"
}

private fun kmWordUk(km: Int): String = when {
    km % 100 in 11..14 -> "кілометрів"
    km % 10 == 1 -> "кілометр"
    km % 10 in 2..4 -> "кілометри"
    else -> "кілометрів"
}
