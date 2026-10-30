package local.phantom.companion.core.sensor.nav

import local.phantom.companion.core.sensor.GpsSample
import kotlin.math.abs

/**
 * PHANTOM-EW, layer 1 — the plausibility gate.
 *
 * A GPS spoofer does not produce noise; it produces a CONFIDENT, low-accuracy
 * number that is simply false. Every consumer of a fix therefore has to ask a
 * question that civilian navigation never asks: *could a body have got here
 * from where it was?* Physics is the one authority a transmitter cannot
 * forge — you cannot make a man on foot cover 15 km in one second, and you
 * cannot make his barometer agree with an altitude you invented.
 *
 * The gate is deliberately conservative: it rejects the *impossible*, not the
 * merely surprising. A false rejection costs a beat of dead reckoning; a
 * false acceptance drives the operator into the position an adversary chose.
 *
 * Pure and clock-injected — every rule below is a unit test.
 */
class SpoofGate(
    /** Nothing on this earth carries an operator faster than this. */
    private val maxSpeedMps: Double = MAX_PLAUSIBLE_SPEED_MPS,
    /** A jump this far between consecutive fixes is a teleport, not a journey. */
    private val maxJumpM: Double = MAX_JUMP_M,
    /** Acceleration no human vehicle sustains (≈4 g). */
    private val maxAccelMps2: Double = MAX_ACCEL_MPS2,
) {
    /** Why a fix was refused. `null` verdict = the fix is plausible. */
    enum class Reject { TELEPORT, IMPOSSIBLE_SPEED, IMPOSSIBLE_ACCEL, ALTITUDE_DISAGREES }

    /**
     * @param staleReference true when too much time passed since the last
     *   accepted fix for physics to say anything useful. The gate is NOT
     *   vouching for this fix — it is declining to judge it, which is a
     *   different thing, and the caller must treat it as such. See
     *   [REACQUIRE_GAP_MS].
     */
    data class Verdict(
        val reject: Reject?,
        val reason: String?,
        val staleReference: Boolean = false,
    ) {
        val accepted: Boolean get() = reject == null
    }

    private var lastLat: Double? = null
    private var lastLon: Double? = null
    private var lastMs: Long = 0L
    private var lastSpeedMps: Double = 0.0
    private var lastAccM: Double = 0.0

    /** A run of rejections is a jamming/spoofing CAMPAIGN, not a glitch. */
    var consecutiveRejects: Int = 0
        private set

    fun reset() {
        lastLat = null
        lastLon = null
        lastMs = 0L
        lastSpeedMps = 0.0
        lastAccM = 0.0
        consecutiveRejects = 0
        lastAltitudeM = 0f
        lastAltitudeSeen = false
    }

    /**
     * @param baroClimbMps the barometer's independent verdict on vertical
     *   motion, or null with no barometer. A GPS altitude that swings while
     *   the air pressure does not is the cheapest spoof there is to catch.
     */
    fun judge(
        fix: GpsSample,
        nowMs: Long,
        gpsAltitudeM: Float? = null,
        baroClimbMps: Float? = null,
    ): Verdict {
        val pLat = lastLat
        val pLon = lastLon

        // The first fix of a session has nothing to contradict. Accept it —
        // and note the asymmetry honestly: a spoofer who is already lying
        // when PHANTOM cold-starts owns the origin, and no amount of physics
        // will reveal that. Only a second source (map-matching, a known
        // start point) could, and that is beyond this gate.
        if (pLat == null || pLon == null) {
            remember(fix, nowMs, 0.0)
            rememberAltitude(gpsAltitudeM)
            return ACCEPTED
        }

        val dtS = ((nowMs - lastMs).coerceAtLeast(1L)) / 1000.0
        val jumpM = haversineM(pLat, pLon, fix.lat, fix.lon)
        // Both fixes' admitted doubt is explained motion, not evidence: a ±1200 m
        // cell fix scattering 600 m is honest. Capped so a liar cannot print its
        // way out of the rule.
        val doubtM = lastAccM + doubtOf(fix)
        val unexplainedM = (jumpM - doubtM).coerceAtLeast(0.0)
        val impliedSpeed = unexplainedM / dtS

        // The reference has gone stale (a tunnel, a garage, a jammer, the app
        // backgrounded at a light). Physics has nothing to say here: over two
        // minutes almost any position in the county is reachable, so «is this
        // plausible?» has no discriminating power left.
        //
        // The old code answered anyway, and got it catastrophically wrong. It
        // compared the raw distance against a flat 800 m with no reference to
        // elapsed time, so the operator's FIRST HONEST FIX after a tunnel was
        // «a teleport» — and since a refused fix is (rightly) never allowed to
        // become the new reference, every fix after it was measured against the
        // same stale point and refused too. Measured: 60 of 60 genuine fixes
        // refused, and the count still climbing. The lockout was permanent, and
        // the engine sat in SPOOFED for the rest of the session.
        //
        // Absence of evidence was being treated as evidence of spoofing.
        //
        // The gate now *declines to judge* and says so. It does not vouch for
        // the fix — [Verdict.staleReference] is a question handed upward, to the
        // one authority that did not go dark during the gap: the inertial track.
        // [CustomLocationEngine] must corroborate before believing this, which
        // is also what stops «jam, wait, then present a false origin» — the
        // standard attack this relaxation would otherwise invite.
        if (nowMs - lastMs > REACQUIRE_GAP_MS) {
            // Crucially, the gate does NOT move its own reference here.
            //
            // The first cut of this did, and it was worse than the bug it fixed:
            // the spoofer's position became the reference the very moment the
            // gate declined to judge it, so the engine's refusal upstairs was
            // irrelevant — the NEXT forged fix, one metre from the last one, was
            // now perfectly «plausible» and sailed straight through the normal
            // path with no corroboration at all. Measured: the engine followed a
            // 5 km teleport to within 3 km of it.
            //
            // Declining to judge and vouching are different acts. The gate does
            // the first. Only [CustomLocationEngine], having asked the inertial
            // track, may do the second — by calling [acceptReacquisition].
            return Verdict(reject = null, reason = null, staleReference = true)
        }

        // A hard teleport: the signature of a spoofer capturing the receiver
        // and dragging it somewhere else entirely. Only meaningful while the
        // reference is fresh — see above.
        if (unexplainedM > maxJumpM) {
            return reject(Reject.TELEPORT, "стрибок ${jumpM.toInt()} м за ${"%.1f".format(dtS)} с")
        }
        if (impliedSpeed > maxSpeedMps) {
            return reject(
                Reject.IMPOSSIBLE_SPEED,
                "швидкість ${(impliedSpeed * 3.6).toInt()} км/год — фізично неможлива",
            )
        }
        val accel = abs(impliedSpeed - lastSpeedMps) / dtS
        if (accel > maxAccelMps2) {
            return reject(
                Reject.IMPOSSIBLE_ACCEL,
                "прискорення ${accel.toInt()} м/с² — так не розганяються",
            )
        }
        // The barometer is the one witness a distant transmitter cannot bribe.
        // The FACT is computed in [VerticalFact], shared with the display gate —
        // one measurement, two thresholds: this one guards an accusation on the
        // badge, the gate's guards a refusal on the position path.
        val gpsClimb = if (lastAltitudeSeen) {
            VerticalFact.rateMps(lastAltitudeM, gpsAltitudeM, dtS)
        } else {
            null
        }
        val climbGap = VerticalFact.disagreementMps(gpsClimb, baroClimbMps)
        val verticalSlack = (verticalDoubtM(lastAccM) + verticalDoubtM(doubtOf(fix))) / dtS
        if (gpsClimb != null && baroClimbMps != null &&
            climbGap != null && climbGap > MAX_CLIMB_DISAGREEMENT_MPS + verticalSlack
        ) {
            return reject(
                Reject.ALTITUDE_DISAGREES,
                "висота GPS суперечить барометру (${gpsClimb.toInt()} проти ${baroClimbMps.toInt()} м/с)",
            )
        }

        remember(fix, nowMs, impliedSpeed)
        rememberAltitude(gpsAltitudeM)
        consecutiveRejects = 0
        return ACCEPTED
    }

    private var lastAltitudeM: Float = 0f
    private var lastAltitudeSeen = false

    private fun rememberAltitude(m: Float?) {
        if (m != null) {
            lastAltitudeM = m
            lastAltitudeSeen = true
        }
    }

    /**
     * The caller corroborated a [Verdict.staleReference] fix against evidence
     * the gate does not have (the inertial track), and is vouching for it. Only
     * now does the reference move.
     *
     * Until this is called, a stale re-acquisition is judged fresh every time —
     * so an adversary transmitting through a jam gets his position re-examined
     * on every single fix, and never gets to quietly become the new truth.
     */
    fun acceptReacquisition(fix: GpsSample, nowMs: Long, gpsAltitudeM: Float? = null) {
        remember(fix, nowMs, 0.0)
        rememberAltitude(gpsAltitudeM)
        consecutiveRejects = 0
    }

    private fun reject(kind: Reject, reason: String): Verdict {
        consecutiveRejects++
        // The rejected fix is NOT remembered: a spoofer must never get to
        // move the reference point it is judged against, one plausible step
        // at a time. That «salami walk» is the standard way past a naive gate.
        return Verdict(kind, reason)
    }

    private fun remember(fix: GpsSample, nowMs: Long, speed: Double) {
        lastLat = fix.lat
        lastLon = fix.lon
        lastMs = nowMs
        lastSpeedMps = speed
        lastAccM = doubtOf(fix)
    }

    companion object {
        private val ACCEPTED = Verdict(null, null)

        const val ACCURACY_CAP_M = 1_000.0

        fun doubtOf(fix: GpsSample): Double =
            fix.accuracyM.toDouble().takeIf { it.isFinite() && it > 0.0 }?.coerceAtMost(ACCURACY_CAP_M) ?: 0.0

        /** A good sky keeps the strict rule; only the admitted excess widens it. */
        fun verticalDoubtM(horizontalM: Double): Double =
            ((horizontalM - GOOD_SKY_M).coerceAtLeast(0.0) * VERTICAL_PER_HORIZONTAL).coerceAtMost(VERTICAL_CAP_M)

        const val GOOD_SKY_M = 20.0
        const val VERTICAL_PER_HORIZONTAL = 1.5
        const val VERTICAL_CAP_M = 600.0

        /** ~1100 km/h. Above this the operator is not in a car; he is in a lie. */
        const val MAX_PLAUSIBLE_SPEED_MPS = 300.0

        /** Two seconds of a fast car, generously. Beyond it: a teleport. */
        const val MAX_JUMP_M = 800.0

        /** ≈4 g — past any road vehicle, well inside a spoofer's step change. */
        const val MAX_ACCEL_MPS2 = 40.0

        /** GPS and the barometer may argue by this much before one is lying. */
        const val MAX_CLIMB_DISAGREEMENT_MPS = 8f

        /**
         * Past this much silence, the gate's reference is stale and its physics
         * rules stop discriminating: at 300 m/s, ten seconds already authorises
         * a 3 km «plausible» jump, and every second after that widens the hole.
         * Rather than pretend to judge, the gate hands the question to the
         * inertial track (see [judge]).
         *
         * Deliberately close to [CustomLocationEngine.JAM_TIMEOUT_MS] (6 s): the
         * moment the engine admits the sky is gone is the same moment the gate
         * should stop claiming it can vet the sky's return.
         */
        const val REACQUIRE_GAP_MS = 8_000L
    }
}
