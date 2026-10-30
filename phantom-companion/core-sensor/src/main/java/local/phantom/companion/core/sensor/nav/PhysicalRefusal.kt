package local.phantom.companion.core.sensor.nav

import local.phantom.companion.core.sensor.GpsSample

/**
 * ПОЛЬОВА ПОМИЛКА, 23.08 21:34 — the per-fix physical checks the display gate
 * did not have.
 *
 * The [ConsistencyGate]'s witnesses were all things a transmitter can forge or
 * out-argue: the network anchor (absent or coarse indoors), the fix's own
 * accuracy, and a kinematic ceiling of 300 m/s that a walking-pace spoof strolls
 * under. The captured episode accepted 40 hostile fixes and refused 8, and the
 * puck walked. These are the refusals that answer it, and they are FACTS —
 * air pressure and vibration — never the EW engine's verdict. Coupling the
 * display path to that verdict is the breathing-zoom disaster; see [BodyWitness].
 *
 * Refuse-only, like everything else in the gate: this class never produces a
 * position, never dead-reckons, and never moves its own references on a refused
 * fix — that «salami walk» is the standard way past a naive gate, and
 * [SpoofGate] learned it the expensive way.
 *
 * Pure and clock-injected.
 */
class PhysicalRefusal(
    private val baroContradictionMps: Float = BARO_CONTRADICTION_MPS,
    private val stillClaimMps: Float = STILL_CLAIM_MPS,
    private val stillHoldMs: Long = STILL_HOLD_MS,
    private val episodeRefusals: Int = EPISODE_REFUSALS,
    private val episodeWindowMs: Long = EPISODE_WINDOW_MS,
    private val clearStreak: Int = CLEAR_STREAK,
    private val maxHoldMs: Long = EPISODE_MAX_HOLD_MS,
) {
    /** Which fact refused the fix. [NONE] = nothing physical objects to it. */
    enum class Kind {
        NONE,

        /** GPS vertical rate contradicts the air in the room. */
        BARO,

        /** The fix claims motion a demonstrably still body cannot be doing. */
        BODY,

        /**
         * Nothing wrong with THIS fix — but the sky is still walking back in
         * after an episode and has not yet earned the display. Recovery is a
         * walk, not a teleport.
         */
        EPISODE,
    }

    // — references, moved only by an ACCEPTED fix —
    private var lastLat = 0.0
    private var lastLon = 0.0
    private var lastAccM = 0f
    private var lastAtMs = 0L
    private var haveLast = false

    /**
     * Vertical baseline — accepted fixes only, so a refused fix can never become
     * the altitude a later fix is measured against. Kept as a short trail rather
     * than a single datum: the rate must be measured over a SLIDING
     * [BARO_BASELINE_MS] window, or a fall that begins halfway through a fixed
     * baseline is averaged down to nothing.
     */
    private class Alt(val atMs: Long, val gpsM: Float, val baroM: Float, val accM: Double)

    private val altTrail = ArrayDeque<Alt>()

    // — episode memory —
    private val refusalsAtMs = ArrayDeque<Long>()
    private var raisedSinceMs = 0L
    private var cleanStreak = 0
    private var candLat = 0.0
    private var candLon = 0.0
    private var candAccM = 0f
    private var haveCand = false

    /** True while the gate is holding a raised refusal posture after an episode. */
    private var highClaim = false
    private var bodySustained = false
    val raised: Boolean get() = raisedSinceMs != 0L

    /** Consecutive fixes that have passed every physical check. For diagnostics. */
    val clean: Int get() = cleanStreak

    fun reset() {
        highClaim = false
        haveLast = false
        altTrail.clear()
        refusalsAtMs.clear()
        raisedSinceMs = 0L
        cleanStreak = 0
        haveCand = false
    }

    fun judge(fused: GpsSample, body: BodyEvidence, nowMs: Long): Kind {
        val tightened = raised
        val baroCeiling = if (tightened) baroContradictionMps * RAISED_SCALE else baroContradictionMps
        val claimCeiling = if (tightened) stillClaimMps * RAISED_SCALE else stillClaimMps
        val holdMs = if (tightened) stillHoldMs / 2 else stillHoldMs

        if (contradictsAir(fused, body, nowMs, baroCeiling)) return refuse(Kind.BARO, nowMs)
        if (contradictsBody(fused, body, nowMs, claimCeiling, holdMs)) return refuse(Kind.BODY, nowMs, counts = bodySustained)

        if (raised) {
            // The witness must be able to concede — a hold that cannot end is
            // the F1 lockout wearing a new hat, and a frozen puck eventually
            // becomes the bigger lie.
            if (nowMs - raisedSinceMs >= maxHoldMs) {
                clearEpisode()
            } else {
                val consistent = haveCand && unexplainedM(candLat, candLon, candAccM, fused) <=
                    RECOVER_CONSIST_M
                cleanStreak = if (consistent) cleanStreak + 1 else 1
                candLat = fused.lat
                candLon = fused.lon
                candAccM = fused.accuracyM
                haveCand = true
                if (cleanStreak < clearStreak) return Kind.EPISODE
                clearEpisode()
            }
        }

        accept(fused, body, nowMs)
        return Kind.NONE
    }

    /**
     * A GPS altitude rate the air contradicts. Measured over a baseline of at
     * least [BARO_BASELINE_MS] rather than fix-to-fix on purpose: honest GNSS
     * altitude noise is ±10–20 m, so a one-second baseline turns ordinary
     * jitter into a 15 m/s «climb» and the check would accuse honest phones.
     * Over four seconds the same jitter divides down to a few m/s and what is
     * left is a real vertical rate. Both sides use the same interval, so a
     * lift, a stairwell and a hill cancel out — only a DISAGREEMENT survives.
     */
    private fun contradictsAir(
        fused: GpsSample,
        body: BodyEvidence,
        nowMs: Long,
        ceilingMps: Float,
    ): Boolean {
        val gpsAlt = fused.altitudeM ?: return false
        val baroAlt = body.baroAltitudeM ?: return false
        // The newest datum old enough to measure against; nothing older than the
        // cap, because a baseline that stale no longer brackets one continuous
        // observation.
        val base = altTrail.lastOrNull {
            val age = nowMs - it.atMs
            age >= BARO_BASELINE_MS && age <= BARO_BASELINE_MAX_MS
        } ?: return false
        val dtS = (nowMs - base.atMs) / 1000.0
        val gpsRate = VerticalFact.rateMps(base.gpsM, gpsAlt, dtS)
        val baroRate = VerticalFact.rateMps(base.baroM, baroAlt, dtS)
        val disagreement = VerticalFact.disagreementMps(gpsRate, baroRate) ?: return false
        val slack = (SpoofGate.verticalDoubtM(base.accM) + SpoofGate.verticalDoubtM(SpoofGate.doubtOf(fused))) / dtS
        return disagreement > ceilingMps + slack
    }

    /**
     * The fix claims to be going somewhere while the body has demonstrably not
     * moved. The Doppler claim is the decisive half — the captured episode's
     * fixes said 6.5 m/s onto a desk — and unexplained displacement is the
     * second, after both fixes' admitted error is spent (two ±100 m fixes 150 m
     * apart are not evidence of travel; they are evidence of a poor sky).
     */
    private fun contradictsBody(
        fused: GpsSample,
        body: BodyEvidence,
        nowMs: Long,
        claimMps: Float,
        holdMs: Long,
    ): Boolean {
        if (!body.stillFor(holdMs)) {
            highClaim = false
            return false
        }
        // A bounced signal reads a metre or two of Doppler at a standstill now
        // and then; a transmitter dragging the fix claims it every second.
        val ceiling = claimMps + (fused.accuracyM * DOPPLER_PER_ACCURACY).coerceAtMost(DOPPLER_SLACK_CAP)
        val high = (fused.speedMps ?: 0f) > ceiling
        bodySustained = high && highClaim
        highClaim = high
        if (high) return true
        if (!haveLast) return false
        val dtS = (nowMs - lastAtMs) / 1000.0
        if (dtS <= 0.0) return false
        // A canyon's bounce resolving leaves tens of metres unexplained at once;
        // what a still body can refute is a displacement no multipath makes.
        val unexplained = unexplainedM(lastLat, lastLon, lastAccM, fused)
        val moved = unexplained > STILL_JUMP_FLOOR_M && unexplained / dtS > claimMps
        if (moved) bodySustained = true
        return moved
    }

    private fun unexplainedM(lat: Double, lon: Double, accM: Float, fused: GpsSample): Double {
        val jump = haversineM(lat, lon, fused.lat, fused.lon)
        return (jump - (accM + fused.accuracyM)).coerceAtLeast(0.0)
    }

    private fun refuse(kind: Kind, nowMs: Long, counts: Boolean = true): Kind {
        cleanStreak = 0
        haveCand = false
        if (!counts) return kind
        refusalsAtMs.addLast(nowMs)
        while (refusalsAtMs.isNotEmpty() && nowMs - refusalsAtMs.first() > episodeWindowMs) {
            refusalsAtMs.removeFirst()
        }
        if (refusalsAtMs.size >= episodeRefusals && raisedSinceMs == 0L) raisedSinceMs = nowMs
        return kind
    }

    private fun clearEpisode() {
        raisedSinceMs = 0L
        refusalsAtMs.clear()
        haveCand = false
        cleanStreak = 0
    }

    private fun accept(fused: GpsSample, body: BodyEvidence, nowMs: Long) {
        lastLat = fused.lat
        lastLon = fused.lon
        lastAccM = fused.accuracyM
        lastAtMs = nowMs
        haveLast = true
        cleanStreak = (cleanStreak + 1).coerceAtMost(clearStreak)

        val gpsAlt = fused.altitudeM
        val baroAlt = body.baroAltitudeM
        if (gpsAlt != null && baroAlt != null) {
            altTrail.addLast(Alt(nowMs, gpsAlt, baroAlt, SpoofGate.doubtOf(fused)))
            while (altTrail.isNotEmpty() && nowMs - altTrail.first().atMs > BARO_BASELINE_MAX_MS) {
                altTrail.removeFirst()
            }
        } else {
            altTrail.clear()
        }
    }

    companion object {
        /**
         * Vertical-rate disagreement, m/s, over a [BARO_BASELINE_MS] baseline.
         * Below [SpoofGate.MAX_CLIMB_DISAGREEMENT_MPS] (8) on purpose: that one
         * guards an ACCUSATION on the badge, where a false positive costs the
         * banner its meaning. This one guards a REFUSAL, where the cost of a
         * false positive is one held fix and the cost of a false negative is
         * the walk of 23.08 (the episode measured 12 m/s against a flat room).
         */
        const val BARO_CONTRADICTION_MPS = 6.0f

        /** The vertical rate is measured over at least this much time. */
        const val BARO_BASELINE_MS = 4_000L

        /** …and the baseline is re-datumed once it is older than this. */
        const val BARO_BASELINE_MAX_MS = 15_000L

        /**
         * A body whose accelerometer AND gyroscope have both been under the
         * still floor for [STILL_HOLD_MS] is not travelling at 1,2 m/s — that
         * is a brisk walk, and a walking phone's footfall variance sits two to
         * three orders of magnitude above the floor.
         */
        const val STILL_CLAIM_MPS = 1.2f
        const val DOPPLER_PER_ACCURACY = 0.02f
        const val DOPPLER_SLACK_CAP = 1.5f
        const val STILL_JUMP_FLOOR_M = 100.0

        /**
         * How long the body must have been still before its testimony may
         * refuse a fix. Deliberately longer than the shipped ZUPT's 3 s
         * ([DeadReckoner.ZUPT_HOLD_MS]), which already zeroes a VEHICLE's speed
         * on that evidence: this refusal is strictly more conservative than a
         * detector that has survived field drives.
         */
        const val STILL_HOLD_MS = 5_000L

        /** Inside an episode every bar tightens by this factor. */
        const val RAISED_SCALE = 0.5f

        /** This many physical refusals inside [EPISODE_WINDOW_MS] is an attack. */
        const val EPISODE_REFUSALS = 3
        const val EPISODE_WINDOW_MS = 30_000L

        /**
         * Consecutive clean, mutually-agreeing fixes before the display follows
         * the sky again. ~6–12 s of honest signal: the 21:35:02 re-acquisition
         * («сигнал повернувся за 290 м») has to walk in rather than teleport.
         */
        const val CLEAR_STREAK = 6

        /** Two fixes further apart than this are not the same sky coming back. */
        const val RECOVER_CONSIST_M = 25.0

        /** A hold this long has become the bigger lie; the witness concedes. */
        const val EPISODE_MAX_HOLD_MS = 90_000L
    }
}
