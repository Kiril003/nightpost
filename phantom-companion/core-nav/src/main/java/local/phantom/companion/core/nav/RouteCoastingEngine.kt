package local.phantom.companion.core.nav

import local.phantom.companion.core.net.map.RouteVariant
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * ATLAS-2.5 §12 — route-constrained coasting under GPS denial.
 *
 * THE INVARIANT AND ITS ONE EXCEPTION
 * -----------------------------------
 * In free roam the displayed position is only ever the fused fix the
 * ConsistencyGate let through: hold, never fabricate. That law is unchanged,
 * and this engine enforces it mechanically — with guidance inactive it has no
 * position to give ([CoastState.puck] is null and the head has nothing to
 * draw but the gate's verdict).
 *
 * The owner's mandate of 2026-08-22 carves out exactly one exception: during
 * ACTIVE GUIDANCE, when the sky dies in a tunnel or under EW, the drive must
 * continue along the route the operator is already following, seamlessly
 * enough that he does not notice — with an honest badge saying which mode he
 * is in, and a smooth merge back, never a teleport.
 *
 * THE STATE MACHINE
 * -----------------
 *  NORMAL   — the gate's fix is the truth; the engine only remembers where on
 *             the corridor it landed, and how fast it was going.
 *  COASTING — entered only when guidance is active, the last measured fix was
 *             ON the corridor, and denial has held for [enterAfterMs]. The
 *             puck walks the matched polyline forward from the last matched
 *             point at the last trusted speed under a decaying envelope,
 *             braking for the bend ahead. It never exceeds the last trusted
 *             speed and never leaves the polyline.
 *  MERGING  — a fix came back on the corridor: the puck slides from the
 *             coasted point to the measured one, eased and rate-capped so it
 *             cannot cover the gap in one frame.
 *  EXPIRED  — a cap was hit. The puck freezes at the last coasted point and
 *             says so. Guidance waits; it does not jump forward and it does
 *             not keep inventing.
 *
 * WIRING CONTRACT — for the wave that consumes this (feature-stream owns it;
 * nothing in production calls this today)
 * ------------------------------------------------------------------------
 *  1. [bind] the selected variant on every plan revision (NavigationEngine's
 *     `planRevision`), and `bind(null)` when the drive ends. An unbound engine
 *     can never coast.
 *  2. Call [update] from the display pump at ≥4 Hz — the engine is dt-driven
 *     and a slow caller only makes the puck coarser, but a caller that stops
 *     calling stops the clock.
 *  3. `fix` is the ConsistencyGate verdict ONLY when it is genuinely believed:
 *     `Status.TRUSTED`, navigation-grade, gate source FUSED. Every other state
 *     — NO_FIX, DEGRADED, SPOOFED, HELD, NETWORK_HOLD — is a denial and must
 *     be passed as `null`. A network hold is a coarse cell fix; anchoring a
 *     coast to one would coast off a lie.
 *  4. `guidanceActive` is `Phase.GUIDING`, nothing looser. Free roam passes
 *     false and gets no puck back, by construction.
 *  5. RENDER RULE: `puck == null` → draw the gate's position unchanged.
 *     `puck != null` → draw it instead of the gate's, and show
 *     [CoastState.badge]. There is no third case.
 *  6. Feed the puck back into guidance as [CoastedFix.toNavFix] — a fix
 *     that is navigation-grade but NOT trusted. That is the path
 *     [GuidanceSession] already documents: maneuver progress and cues advance
 *     through the blackout, while off-route, reroute and arrival stay frozen.
 *     Never feed it as trusted, and never call [GuidanceSession] twice for the
 *     same instant.
 *  7. [CoastState.offRouteOnReturn] is a notification, not an order: the same
 *     measured fix also reaches guidance, whose existing off-route hysteresis
 *     owns the reroute. Do not reroute from here.
 *  8. [CoastState.hardResync] marks the one frame where the gap was too wide
 *     to slide honestly and the truth was handed over at once. Cross-fade the
 *     puck if anything; do not animate it across the gap.
 *  9. Badge sentences live in [coastBadgeUa]/[coastBadgeEn] for now, the
 *     ConsistencyGate precedent — this module carries no resources. They move
 *     to `values/` + `values-en/` when a UI module owns the surface.
 */
class RouteCoastingEngine(
    /** Denial must hold this long before a single metre is fabricated. */
    private val enterAfterMs: Long = ENTER_AFTER_MS,
    private val maxCoastMs: Long = MAX_COAST_MS,
    private val maxCoastM: Double = MAX_COAST_M,
    /** A returning fix further than this off the corridor is not ours to merge. */
    private val offRouteExitM: Double = OFF_ROUTE_EXIT_M,
    private val lateralAccelMps2: Double = CURVE_LATERAL_ACCEL_MPS2,
) {
    private var path: RoutePath? = null
    private var mode = CoastMode.NORMAL

    private var haveAnchor = false
    private var anchorAlongM = 0.0
    private var anchorSpeedMps = 0.0
    private var deniedSinceMs = 0L

    private var coastStartMs = 0L
    private var coastStartAlongM = 0.0
    private var coastAlongM = 0.0
    private var coastSpeedMps = 0.0
    private var entrySpeedMps = 0.0
    private var lastTickMs = 0L

    private var curLat = 0.0
    private var curLon = 0.0
    private var curBearingDeg = 0.0
    private var confidence = 1f

    /** Where the displayed puck sat relative to the corridor when the coast
     *  began. Carried and melted rather than snapped away — otherwise entering
     *  a coast is itself a sideways teleport of the whole map-match error. */
    private var offsetLat = 0.0
    private var offsetLon = 0.0
    private var emittedCoastedMs = 0L

    private var mergeStartMs = 0L
    private var mergeFromConfidence = 0f

    /** The route the coast is constrained to. Null forbids coasting outright. */
    fun bind(variant: RouteVariant?) {
        path = variant?.let { RoutePath.of(it) }
        reset()
    }

    fun reset() {
        mode = CoastMode.NORMAL
        haveAnchor = false
        deniedSinceMs = 0L
        coastSpeedMps = 0.0
        confidence = 1f
        offsetLat = 0.0
        offsetLon = 0.0
        emittedCoastedMs = 0L
    }

    /**
     * One display tick. [fix] is the believed measurement, or null when the
     * sky was denied or refused for this tick (contract §3).
     */
    fun update(fix: CoastFix?, guidanceActive: Boolean, nowMs: Long): CoastState {
        val p = path
        if (!guidanceActive || p == null) {
            // The memory-law invariant, enforced in code rather than in prose:
            // free roam cannot reach any branch below that fabricates.
            reset()
            return LIVE_STATE
        }
        return if (fix != null) onMeasured(fix, p, nowMs) else onDenied(p, nowMs)
    }

    private fun onMeasured(fix: CoastFix, p: RoutePath, nowMs: Long): CoastState {
        val snap = matchOf(fix, p)
        deniedSinceMs = 0L
        return when (mode) {
            CoastMode.NORMAL -> {
                anchorOn(snap, fix, nowMs)
                LIVE_STATE
            }
            CoastMode.COASTING, CoastMode.EXPIRED -> beginMerge(fix, snap, nowMs)
            CoastMode.MERGING -> mergeStep(fix, snap, nowMs)
        }
    }

    private fun onDenied(p: RoutePath, nowMs: Long): CoastState {
        if (deniedSinceMs == 0L) deniedSinceMs = nowMs
        return when (mode) {
            CoastMode.NORMAL -> {
                // Hold, don't fabricate: for the first [enterAfterMs] a lost fix
                // is just a lost fix, and the gate's own hold is the honest
                // picture. Only a sustained denial during guidance earns a coast.
                val eligible = haveAnchor && nowMs - deniedSinceMs >= enterAfterMs
                if (!eligible) LIVE_STATE else enterCoasting(p, nowMs)
            }
            CoastMode.COASTING -> coastStep(p, nowMs)
            CoastMode.EXPIRED -> expiredState()
            CoastMode.MERGING -> {
                // The sky flickered out mid-merge. Freeze the slide; if the
                // denial holds, coast on from wherever the merge had reached.
                if (nowMs - deniedSinceMs < enterAfterMs) {
                    mergingState()
                } else {
                    anchorAlongM = p.snap(curLat, curLon).alongM
                    anchorSpeedMps = coastSpeedMps
                    haveAnchor = true
                    enterCoasting(p, nowMs)
                }
            }
        }
    }

    private fun anchorOn(snap: RouteSnap, fix: CoastFix, nowMs: Long) {
        // An anchor off the corridor is not a place to coast from — the coast
        // would follow a route the operator has already left.
        haveAnchor = snap.offMeters <= offRouteExitM
        anchorAlongM = snap.alongM
        anchorSpeedMps = (fix.speedMps ?: 0.0).coerceAtLeast(0.0)
        curLat = fix.lat
        curLon = fix.lon
        curBearingDeg = fix.bearingDeg ?: snap.bearingDeg
        confidence = 1f
        lastTickMs = nowMs
    }

    private fun enterCoasting(p: RoutePath, nowMs: Long): CoastState {
        mode = CoastMode.COASTING
        coastStartMs = nowMs
        coastStartAlongM = anchorAlongM
        coastAlongM = anchorAlongM
        entrySpeedMps = anchorSpeedMps
        coastSpeedMps = anchorSpeedMps
        lastTickMs = nowMs
        val onCorridor = p.pointAtM(anchorAlongM)
        offsetLat = curLat - onCorridor.lat
        offsetLon = curLon - onCorridor.lon
        return coastStep(p, nowMs)
    }

    private fun coastStep(p: RoutePath, nowMs: Long): CoastState {
        val dt = tickSeconds(nowMs)
        val elapsedMs = nowMs - coastStartMs

        // The envelope: the last trusted speed, decaying gently. The decay is
        // not physics — a car in a tunnel holds its speed — it is the estimate
        // admitting it ages. Too steep and the puck visibly stalls mid-tunnel,
        // which is the very break the driver must not see.
        val envelope = entrySpeedMps * exp(-(elapsedMs / 1000.0) / SPEED_DECAY_TAU_S)
        val target = minOf(envelope, curveSpeedCapMps(p, coastAlongM))
        coastSpeedMps = when {
            target < coastSpeedMps -> maxOf(target, coastSpeedMps - COAST_DECEL_MPS2 * dt)
            else -> minOf(target, coastSpeedMps + COAST_ACCEL_MPS2 * dt)
        }.coerceAtLeast(0.0)

        coastAlongM = (coastAlongM + coastSpeedMps * dt).coerceAtMost(p.totalDistM)
        val coastedM = coastAlongM - coastStartAlongM
        val decay = exp(-dt / ENTRY_OFFSET_TAU_S)
        offsetLat *= decay
        offsetLon *= decay
        val here = p.pointAtM(coastAlongM)
        curLat = here.lat + offsetLat
        curLon = here.lon + offsetLon
        curBearingDeg = here.bearingDeg
        emittedCoastedMs = elapsedMs

        val spent = maxOf(
            elapsedMs.toDouble() / maxCoastMs.toDouble(),
            coastedM / maxCoastM,
        )
        confidence = (1.0 - spent).coerceIn(0.0, 1.0).toFloat()

        if (spent >= 1.0 || coastAlongM >= p.totalDistM) {
            mode = CoastMode.EXPIRED
            coastSpeedMps = 0.0
            confidence = 0f
            return expiredState()
        }
        return CoastState(
            mode = CoastMode.COASTING,
            puck = emitted(CoastMode.COASTING, coastSpeedMps, coastedM),
            badge = CoastBadge.COASTING,
        )
    }

    /**
     * Comfortable speed for the bend ahead, from the same curvature the camera
     * leans on ([RoutePath.curvatureAheadDegPerM] over the window
     * [GuidanceProgress.curvatureDegPerM] uses). v = sqrt(a_lat / κ): a coast
     * that carries the puck into a turn at open-road speed draws a car that
     * left the road.
     */
    private fun curveSpeedCapMps(p: RoutePath, alongM: Double): Double {
        val degPerM = p.curvatureAheadDegPerM(alongM, CURVE_WINDOW_M)
        if (degPerM <= CURVE_STRAIGHT_DEG_PER_M) return Double.MAX_VALUE
        return sqrt(lateralAccelMps2 / Math.toRadians(degPerM))
    }

    private fun beginMerge(fix: CoastFix, snap: RouteSnap, nowMs: Long): CoastState {
        if (snap.offMeters > offRouteExitM) {
            // The operator came back somewhere else entirely. Coasting has no
            // claim on that position; hand the truth over and let guidance's
            // own off-route hysteresis decide about a reroute.
            val gapM = haversineM(curLat to curLon, fix.lat to fix.lon)
            stopOverriding(snap, fix, nowMs)
            return CoastState(
                CoastMode.NORMAL, null, CoastBadge.LIVE,
                offRouteOnReturn = true,
                hardResync = gapM > MERGE_DONE_M,
            )
        }
        val gapM = haversineM(curLat to curLon, fix.lat to fix.lon)
        if (gapM <= MERGE_DONE_M) {
            stopOverriding(snap, fix, nowMs)
            return LIVE_STATE
        }
        if (gapM > MERGE_MAX_GAP_M) {
            // Sliding a puck this far is not a merge, it is a teleport in slow
            // motion. The coast was wrong; say so in one frame instead of
            // spending ten seconds performing a correction.
            stopOverriding(snap, fix, nowMs)
            return CoastState(CoastMode.NORMAL, null, CoastBadge.LIVE, hardResync = true)
        }
        mode = CoastMode.MERGING
        mergeStartMs = nowMs
        mergeFromConfidence = confidence
        lastTickMs = nowMs
        return mergeStep(fix, snap, nowMs)
    }

    private fun mergeStep(fix: CoastFix, snap: RouteSnap, nowMs: Long): CoastState {
        val dt = tickSeconds(nowMs)
        val elapsedMs = nowMs - mergeStartMs
        val gapM = haversineM(curLat to curLon, fix.lat to fix.lon)

        // Ease toward the measurement, then cap the step by an apparent speed a
        // vehicle could plausibly carry. The cap is what makes it a slide: the
        // ease alone would cross 80 m in one 250 ms frame.
        val eased = 1.0 - exp(-dt / MERGE_TAU_S)
        val maxStepM = (coastSpeedMps + MERGE_CATCHUP_MPS) * dt
        val f = if (gapM * eased <= maxStepM || gapM <= 0.0) eased else maxStepM / gapM
        curLat += (fix.lat - curLat) * f
        curLon += (fix.lon - curLon) * f
        curBearingDeg = snap.bearingDeg
        confidence = (
            mergeFromConfidence + (1f - mergeFromConfidence) *
                (elapsedMs.toFloat() / MERGE_MS.toFloat()).coerceIn(0f, 1f)
            ).coerceIn(0f, 1f)

        val remainingM = haversineM(curLat to curLon, fix.lat to fix.lon)
        val overdue = elapsedMs >= MERGE_MAX_MS
        if (remainingM <= MERGE_DONE_M || overdue) {
            stopOverriding(snap, fix, nowMs)
            return CoastState(
                CoastMode.NORMAL, null, CoastBadge.LIVE,
                hardResync = overdue && remainingM > MERGE_DONE_M,
            )
        }
        return mergingState()
    }

    private fun stopOverriding(snap: RouteSnap, fix: CoastFix, nowMs: Long) {
        mode = CoastMode.NORMAL
        anchorOn(snap, fix, nowMs)
    }

    private fun mergingState() = CoastState(
        mode = CoastMode.MERGING,
        puck = emitted(CoastMode.MERGING, coastSpeedMps, coastAlongM - coastStartAlongM),
        badge = CoastBadge.MERGING,
    )

    private fun expiredState() = CoastState(
        mode = CoastMode.EXPIRED,
        puck = emitted(CoastMode.EXPIRED, 0.0, coastAlongM - coastStartAlongM),
        badge = CoastBadge.WAITING,
    )

    private fun emitted(
        emitMode: CoastMode,
        speedMps: Double,
        coastedM: Double,
    ) = CoastedFix(
        lat = curLat,
        lon = curLon,
        bearingDeg = curBearingDeg,
        speedMps = speedMps,
        confidence = confidence,
        mode = emitMode,
        coastedM = coastedM,
        coastedMs = emittedCoastedMs,
        uncertaintyM = COAST_UNCERTAINTY_BASE_M + coastedM * COAST_DRIFT_SHARE,
    )

    /**
     * Windowed match, anchored on where the engine believes the puck is — with
     * the escape [GuidanceSession] also carries. A car that stopped inside the
     * tunnel while the coast ran 800 m ahead comes back BEHIND the window: a
     * windowed-only match would read that as off-route and spend a reroute on
     * a route the operator never left.
     */
    private fun matchOf(fix: CoastFix, p: RoutePath): RouteSnap {
        if (!haveAnchor && mode == CoastMode.NORMAL) return p.snap(fix.lat, fix.lon)
        val near = if (mode == CoastMode.NORMAL) anchorAlongM else coastAlongM
        val windowed = p.snapNear(fix.lat, fix.lon, near, MATCH_BACK_M, MATCH_AHEAD_M)
        if (windowed.offMeters <= MATCH_ESCAPE_OFF_M) return windowed
        val global = p.snap(fix.lat, fix.lon)
        return if (global.offMeters < windowed.offMeters) global else windowed
    }

    private fun tickSeconds(nowMs: Long): Double {
        val dt = ((nowMs - lastTickMs) / 1000.0).coerceIn(0.0, MAX_TICK_S)
        lastTickMs = nowMs
        return dt
    }

    companion object {
        /** Two and a half seconds of silence: shorter and a single dropped fix
         *  at a traffic light would start fabricating motion. */
        const val ENTER_AFTER_MS = 2_500L

        /** Owner's cap. Past 45 s of unaided propagation the estimate is a
         *  story, and a frozen puck that admits it beats a moving one that
         *  does not. */
        const val MAX_COAST_MS = 45_000L

        /** The other cap, the one that binds on a motorway: at 25 m/s the time
         *  cap alone would fabricate 1.1 km. Error grows with distance, not
         *  with seconds. */
        const val MAX_COAST_M = 1_000.0

        /** Corridor width for both the anchor and the returning fix. Sits just
         *  above PACE_CAR.offRouteM so the two verdicts cannot disagree about
         *  the same metre. */
        const val OFF_ROUTE_EXIT_M = 60.0

        /** ~7 % of the last trusted speed lost over the full 45 s. */
        const val SPEED_DECAY_TAU_S = 600.0

        const val COAST_DECEL_MPS2 = 2.5
        const val COAST_ACCEL_MPS2 = 1.2

        /** Same 120 m read-ahead the camera's curvature response uses. */
        const val CURVE_WINDOW_M = 120.0
        const val CURVE_STRAIGHT_DEG_PER_M = 0.05
        const val CURVE_LATERAL_ACCEL_MPS2 = 2.0

        /** Nominal blend window; the rate cap below may stretch it. */
        const val MERGE_MS = 1_500L
        const val MERGE_TAU_S = 0.45
        const val MERGE_MAX_MS = 6_000L

        /** Extra apparent speed the puck may carry while closing the gap. */
        const val MERGE_CATCHUP_MPS = 28.0

        /** Below this the coast and the measurement are the same place. */
        const val MERGE_DONE_M = 3.0

        /** Above this no slide is honest — see [beginMerge]. */
        const val MERGE_MAX_GAP_M = 400.0

        /** How fast the entry offset melts onto the corridor (~3 s). */
        const val ENTRY_OFFSET_TAU_S = 1.0

        const val MATCH_BACK_M = 600.0
        const val MATCH_AHEAD_M = 800.0
        const val MATCH_ESCAPE_OFF_M = 100.0

        /** A caller that stalled longer than this lost the drive, not a frame;
         *  charging the whole gap to the puck would fling it down the road. */
        const val MAX_TICK_S = 5.0

        const val COAST_UNCERTAINTY_BASE_M = 15.0
        const val COAST_DRIFT_SHARE = 0.15

        private val LIVE_STATE = CoastState(CoastMode.NORMAL, null, CoastBadge.LIVE)
    }
}
