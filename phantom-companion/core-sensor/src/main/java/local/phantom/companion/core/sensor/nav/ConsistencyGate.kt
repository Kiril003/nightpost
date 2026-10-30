package local.phantom.companion.core.sensor.nav

import local.phantom.companion.core.sensor.GpsSample

/**
 * PRECISION ENGINE, Section 4 — the cross-provider consistency gate.
 *
 * The precision pivot (58366b9d) put the **system fused fix** on the display
 * path and took our hand-rolled dead reckoning off it — correctly, because a
 * parked phone was drawing fabricated motion. But it left the display trusting
 * the fused provider *blindly*, and the live Kyiv capture of 2026-07-21 proved
 * that provider is not spoof-safe: it fused a strong spoofed GPS and walked the
 * operator to **Lima, Peru** while the independent **network** provider held
 * true in Kyiv the entire time (`reb-rich.tsv`).
 *
 * This gate is the amendment the spoof brief called for: keep fused primary in
 * the normal case, but judge every fused fix against the one witness a GNSS RF
 * spoofer cannot reach — the Wi-Fi/cell **network anchor** — plus two checks
 * that need no second provider at all. It is the *opposite* of the EW engine it
 * sits beside: in the normal case it does **nothing** — the fused fix passes
 * through untouched, so the pivot's parked-still behaviour is preserved exactly.
 * It intervenes only on **positive evidence of a false position**, and then it
 * holds the last trusted place and leads by the network anchor rather than
 * rendering the teleport.
 *
 * The one rule everything below serves: **a spoof verdict requires evidence of
 * a lie, never mere uncertainty.** «I cannot see» and «someone is deceiving me»
 * are different states, and an app that merges them teaches its operator to
 * ignore the alarm.
 *
 * Signals:
 *
 *  1. **Contradiction** — fused and the network anchor are further apart than
 *     BOTH of them admit they could be, so they cannot both be true. In the
 *     capture the disagreement was ~10 000 km against a few hundred metres of
 *     admitted error. The tight margin (not "a few km") is what also catches
 *     slow meaconing, per the spoof brief. Bare divergence measured against the
 *     network's error ALONE stays a logged reason only — see the note at the
 *     hard-trigger block.
 *  2. **Staleness on a contradiction** — the fused fix's own `elapsedMs` frozen
 *     while the network keeps advancing = GNSS captured or dead. On its own it
 *     is NOT an alarm (a phone indoors answers once per 10–30 s); it declares
 *     SPOOFED only when the frozen position is one the network contradicts.
 *     This is what catches the owner's 15-hour-old Peru fix — hAcc 39 m, six
 *     satellites, entirely healthy-looking.
 *  3. **Kinematic impossibility** — fused implies a jump past the profile
 *     ceiling versus the last trusted position, after both fixes' own error
 *     budgets are spent. Catches jam-then-teleport with no network anchor.
 *  4. **Fused-hAcc explosion** — accuracy in the kilometres (the capture:
 *     19 497 → 998 389 m as the stale spoof aged). NOT a trigger on its own:
 *     it is what a cold start indoors looks like, and it latched SPOOFED on
 *     honest phones until 2026-08-16. It may only corroborate (1).
 *
 * Pure: no Android types, no clock of its own — feed it samples and a `nowMs`,
 * and every rule is a unit test. Reuses [GpsSample] for both providers (a plain
 * data class); the provider a sample came from is the caller's business.
 */
class ConsistencyGate(
    /** A fused fix admitting more doubt than this (metres, 1σ) is treated as no-fix. */
    private val fusedHAccCeilingM: Double = FUSED_HACC_CEILING_M,
    /** Fused may sit this far past the network's own admitted error before it is a lie. */
    private val divergenceMarginM: Double = DIVERGENCE_MARGIN_M,
    /** Nothing on this earth carries an operator faster than this (m/s). */
    private val maxSpeedMps: Double = MAX_PLAUSIBLE_SPEED_MPS,
    /** Consecutive fused fixes that must re-agree with the network before trust returns. */
    private val recoverAgreeStreak: Int = RECOVER_AGREE_STREAK,
    /** How long a hold stays frozen before the network anchor may lead it. */
    private val networkLeadAfterMs: Long = NETWORK_LEAD_AFTER_MS,
    /**
     * ПОЛЬОВА ПОМИЛКА, 23.08 — the per-fix physical checks. Every signal above
     * is something the transmitter can forge or out-argue; these two (air
     * pressure, vibration) are not. See [PhysicalRefusal].
     */
    private val physical: PhysicalRefusal = PhysicalRefusal(),
) {
    /** Where the displayed position in a [Verdict] came from. */
    enum class Source {
        /** Trusted normal case: the fused fix, passed through unchanged. */
        FUSED,

        /** Spoofed: fused refused, the coarse network anchor is the display truth. */
        NETWORK_HOLD,

        /** Spoofed with no network anchor available: frozen at the last trusted place. */
        HELD,

        /** Cold, nothing to show yet: no fused, no network, no trusted history. */
        NONE,
    }

    /** Why a fix was refused. [NONE] = the fused fix was trusted. */
    enum class Reason {
        NONE, HACC_EXPLOSION, DIVERGENCE, KINEMATIC, STALENESS,

        /** GPS vertical rate contradicts the barometer. */
        BARO_CONTRADICTION,

        /** The fix claims motion a demonstrably still body is not performing. */
        BODY_CONTRADICTION,

        /** Physically clean, but the sky is still walking back in after an episode. */
        EPISODE_HOLD,
    }

    /**
     * The three things that can be wrong, which are not degrees of each other.
     *
     * «I cannot see» / «I see badly» / «I am being lied to» demand different
     * actions, and merging them produces the worst outcome of the three: an
     * operator who has been told he is under attack learns to ignore the one
     * banner that must never be ignored.
     */
    enum class Status {
        /** The fused fix is believed and displayed unchanged. */
        TRUSTED,

        /** No fix arrived. Nothing is being claimed about motion since. */
        NO_FIX,

        /** A fix arrived but admits more error than a fix is worth. Not a lie. */
        DEGRADED,

        /** Positive evidence that the position is false. */
        SPOOFED,
    }

    data class Verdict(
        val spoofed: Boolean,
        val source: Source,
        val displayLat: Double,
        val displayLon: Double,
        val displayHAccM: Float,
        val reason: Reason,
        /** Operator-facing sentence, Ukrainian. Null only while trusted. */
        val message: String?,
        /** Fused-vs-network separation in metres, when a network anchor was present. */
        val divergenceM: Double?,
        val fusedHAccM: Float?,
        val netHAccM: Float?,
        val status: Status = Status.TRUSTED,
        /** The same sentence in English. */
        val messageEn: String? = null,
        /**
         * False when [displayLat]/[displayLon] mean nothing. They are 0,0 in
         * that case, which is a real place in the Gulf of Guinea — a consumer
         * that renders without checking this draws the operator there.
         */
        val hasPosition: Boolean = true,
        val refusedFix: Boolean = false,
    )

    // — last trusted display anchor (a real fused fix, or an adopted network fix) —
    private var trustLat = 0.0
    private var trustLon = 0.0
    private var trustHAccM = 0f
    private var trustAtMs = 0L

    /** Speed of the last AGREEING fix — the only speed allowed to widen a margin. */
    private var trustSpeedMps = 0f
    private var haveTrusted = false

    /** Currently holding because a spoof was caught; cleared only by [recoverAgreeStreak]. */
    private var spoofedLatched = false
    private var agreeStreak = 0

    /** When the current hold began, or 0 while the fused fix is being trusted. */
    private var holdSinceMs = 0L

    // — staleness bookkeeping —
    private var lastFusedElapsedMs = Long.MIN_VALUE
    private var trustedElapsedMs = Long.MIN_VALUE
    private var lastPhysical = PhysicalRefusal.Kind.NONE
    private var lastNetElapsedMs = Long.MIN_VALUE

    fun reset() {
        haveTrusted = false
        trustAtMs = 0L
        trustSpeedMps = 0f
        spoofedLatched = false
        agreeStreak = 0
        holdSinceMs = 0L
        lastFusedElapsedMs = Long.MIN_VALUE
        trustedElapsedMs = Long.MIN_VALUE
        lastPhysical = PhysicalRefusal.Kind.NONE
        lastNetElapsedMs = Long.MIN_VALUE
        physical.reset()
    }

    /** True while the physical checks are holding a raised posture after an episode. */
    val physicallyHolding: Boolean get() = physical.raised

    /** True while the gate is holding on a caught spoof — for the passive badge/UI. */
    val spoofed: Boolean get() = spoofedLatched

    /**
     * Judge one fused fix (nullable — the chip may deliver nothing) against the
     * latest network anchor (nullable — no network provider, or none yet).
     */
    fun update(
        fused: GpsSample?,
        network: GpsSample?,
        nowMs: Long,
        body: BodyEvidence = BodyEvidence(),
    ): Verdict {
        val netAdvanced = network != null &&
            network.elapsedMs > lastNetElapsedMs && lastNetElapsedMs != Long.MIN_VALUE
        if (network != null) lastNetElapsedMs = network.elapsedMs

        // No fused fix arrived at all. Lean on whatever anchor we have; never
        // invent motion (the pivot's law) — a missing fix is not an attack.
        if (fused == null) return holdVerdict(network, Reason.STALENESS, nowMs)

        val divergenceM = network?.let { haversineM(fused.lat, fused.lon, it.lat, it.lon) }

        val haccExploded = fused.accuracyM > fusedHAccCeilingM
        // The network fix is minutes old on the move; the distance a body covered
        // since then, at the last AGREEING speed, is lag, not disagreement.
        val netLagM = network?.let {
            trustSpeedMps * ((nowMs - it.elapsedMs).coerceIn(0L, NET_LAG_MAX_MS) / 1000.0)
        } ?: 0.0
        val diverged = divergenceM != null &&
            divergenceM > (network!!.accuracyM + divergenceMarginM + netLagM)

        // The margin widens for a moving operator (a network fix lags a drive by
        // hundreds of metres, and the tight margin would call that honest lag a
        // lie). ПОЛЬОВА ПОМИЛКА, 23.08 — it used to read that speed off THE FIX
        // UNDER JUDGEMENT, so claiming 6,5 m/s bought the liar a 1,5 km gate.
        // Motion must be attested by something the fix does not control: the last
        // AGREEING fix's speed, or the operator's own body.
        val movingAttested = trustSpeedMps > DRIVING_SPEED_MPS ||
            body.movingFor(BodyWitness.MOVING_VERDICT_HOLD_MS)
        val dynamicMargin = if (movingAttested) {
            divergenceMarginM * DRIVING_MARGIN_SCALE
        } else {
            divergenceMarginM
        }
        val contradicts = divergenceM != null &&
            divergenceM > (network!!.accuracyM + fused.accuracyM + dynamicMargin)

        // A jump the network corroborates is a provider handoff, not an attack
        // (measured 446 m in one second at 0 m divergence, 2026-08-06).
        val corroborated = divergenceM != null && !diverged
        val kinematic = haveTrusted && !corroborated && impossibleKinematics(fused, nowMs)

        // Staleness counts only alongside a contradiction — a sky that froze on a
        // position the network says is impossible. Frozen-but-agreeing is an
        // indoor slow sky, and alarming on it is exactly the parked-phone false
        // positive the pivot removed.
        val fusedFrozen = fused.elapsedMs == lastFusedElapsedMs && lastFusedElapsedMs != Long.MIN_VALUE
        lastFusedElapsedMs = fused.elapsedMs
        // A frozen fix that was believed while fresh is a lost signal (a tunnel
        // outruns it), not a captured one.
        val grossly = divergenceM != null &&
            divergenceM > STALE_SIGMAS * (network!!.accuracyM + fused.accuracyM) + dynamicMargin
        val staleOnLie = fusedFrozen && netAdvanced && grossly && fused.elapsedMs != trustedElapsedMs

        // PHYSICAL refusal, ahead of everything above — the checks that need no
        // second provider and that a transmitter cannot answer. Deliberately NOT
        // latching [spoofedLatched]: that latch clears on agreement with the
        // NETWORK, and the network anchor was absent for the whole 23.08 episode.
        // The physical posture carries its own memory and its own recovery.
        // Judged once, when it arrived: re-reading a stale fix's speed against a
        // body that has since stopped (a queue in a tunnel) is not new evidence.
        val physicalKind = if (fusedFrozen) lastPhysical else physical.judge(fused, body, nowMs)
        lastPhysical = physicalKind
        if (physicalKind != PhysicalRefusal.Kind.NONE && !physical.raised) {
            // One refusal is a fix that lied about its accuracy (a canyon's
            // bounce); only a repeated episode is a spoof worth an alarm.
            agreeStreak = 0
            val (lat, lon, hAcc, source) = heldDisplay(network, nowMs)
            return Verdict(
                spoofed = false,
                source = source,
                displayLat = lat,
                displayLon = lon,
                displayHAccM = hAcc,
                reason = Reason.NONE,
                message = UNCERTAIN_UA,
                divergenceM = divergenceM,
                fusedHAccM = fused.accuracyM,
                netHAccM = network?.accuracyM,
                status = Status.DEGRADED,
                messageEn = UNCERTAIN_EN,
                hasPosition = source != Source.NONE,
                refusedFix = true,
            )
        }
        if (physicalKind != PhysicalRefusal.Kind.NONE) {
            agreeStreak = 0
            val reason = when (physicalKind) {
                PhysicalRefusal.Kind.BARO -> Reason.BARO_CONTRADICTION
                PhysicalRefusal.Kind.BODY -> Reason.BODY_CONTRADICTION
                else -> Reason.EPISODE_HOLD
            }
            return spoofVerdict(reason, network, divergenceM, fused.accuracyM, nowMs)
        }

        // HARD triggers demote the display to the network hold and latch SPOOFED.
        // Bare divergence is deliberately NOT one of them: a NETWORK_PROVIDER fix
        // lags a moving operator by hundreds of metres while still reporting a tight
        // accuracy, so demoting on divergence alone pins the puck to a stale coarse
        // fix and fails routing on an honest drive. It survives only as a logged
        // reason (below).
        //
        // Nor is hAcc explosion alone, any longer. It fired on its own until
        // 2026-08-16, and a cold start indoors — where the fused provider answers
        // with kilometres of admitted error before it has a satellite — was enough
        // to latch SPOOFED and put the accusation on screen. Uncertainty is not a
        // lie; it is now DEGRADED (below), and it may only CORROBORATE. The ceiling
        // is untouched: what changed is that the kilometres must be accompanied by
        // a fix that contradicts an independent witness, which is precisely the
        // Kyiv capture's own signature (19 497 m of admitted error, 12 270 km away
        // from a network anchor holding true).
        val hardReason = when {
            haccExploded && contradicts -> Reason.HACC_EXPLOSION
            staleOnLie -> Reason.STALENESS
            kinematic -> Reason.KINEMATIC
            else -> Reason.NONE
        }

        if (hardReason != Reason.NONE) {
            spoofedLatched = true
            agreeStreak = 0
            // The anchor is NOT refreshed to the network fix — doing that moved
            // the puck by exactly the distance the gate had just refused.
            return spoofVerdict(hardReason, network, divergenceM, fused.accuracyM, nowMs)
        }

        // Latched on an earlier hard trip: the sky must genuinely re-agree with the
        // network before trust returns.
        if (spoofedLatched) {
            // A fix that still diverges is not the sky coming back — hold, and do
            // not let it count toward recovery.
            if (diverged) {
                return spoofVerdict(Reason.DIVERGENCE, network, divergenceM, fused.accuracyM, nowMs)
            }
            // A spoof does not clear on one plausible fix — that is how a spoofer
            // hands the sky back one honest-looking step at a time. Require a run
            // of agreement before trusting the sky again. A fix too vague to be
            // believed is not agreement either; it must not shorten the run.
            if (haccExploded) {
                return spoofVerdict(Reason.HACC_EXPLOSION, network, divergenceM, fused.accuracyM, nowMs)
            }
            agreeStreak++
            if (agreeStreak < recoverAgreeStreak) {
                return spoofVerdict(Reason.DIVERGENCE, network, divergenceM, fused.accuracyM, nowMs)
            }
            spoofedLatched = false
            agreeStreak = 0
        }

        // Trusted, normal case: the fused fix passes through UNCHANGED — no hold,
        // no offset, no dead reckoning. This is the pivot, preserved. A bare
        // divergence rides along as a logged reason ONLY (never spoofed, never a
        // demote, never a banner): on a moving operator it is the network provider
        // lagging, not the sky lying, and the blackbox `guard` line keeps it for
        // the time-series that alone can tell honest lag from slow meaconing.
        // Anchor = last AGREEMENT, not last fix shown. A diverging fix is still
        // displayed, but must never become the place a later hold freezes on.
        // Nor may a fix too vague to be believed: a hold rendered at a 2 km-wide
        // anchor would present uncertainty as the last thing we were sure of.
        if (!diverged && !haccExploded) {
            trustLat = fused.lat
            trustLon = fused.lon
            trustHAccM = fused.accuracyM
            trustSpeedMps = fused.speedMps ?: 0f
            trustAtMs = nowMs
            trustedElapsedMs = fused.elapsedMs
            haveTrusted = true
        }
        holdSinceMs = 0L

        // Displayed either way — it is the only measurement of this device we
        // have, and its own radius states the doubt. What changes is what the
        // operator is TOLD: a wide circle and «приблизно», never an accusation.
        return Verdict(
            spoofed = false,
            source = Source.FUSED,
            displayLat = fused.lat,
            displayLon = fused.lon,
            displayHAccM = fused.accuracyM,
            reason = when {
                haccExploded -> Reason.HACC_EXPLOSION
                diverged -> Reason.DIVERGENCE
                else -> Reason.NONE
            },
            message = if (haccExploded) UNCERTAIN_UA else null,
            divergenceM = divergenceM,
            fusedHAccM = fused.accuracyM,
            netHAccM = network?.accuracyM,
            status = if (haccExploded) Status.DEGRADED else Status.TRUSTED,
            messageEn = if (haccExploded) UNCERTAIN_EN else null,
        )
    }

    /**
     * Fused implies a jump past the ceiling versus the last trusted place —
     * after the two positions' own error budgets are spent. Two fixes each
     * admitting a kilometre may legitimately sit two kilometres apart; charging
     * that to the speed ceiling is reading uncertainty as a teleport.
     */
    private fun impossibleKinematics(fused: GpsSample, nowMs: Long): Boolean {
        val dtS = ((nowMs - trustAtMs).coerceAtLeast(1L)) / 1000.0
        val jumpM = haversineM(trustLat, trustLon, fused.lat, fused.lon)
        val unexplainedM = jumpM - (trustHAccM + fused.accuracyM)
        return unexplainedM > 0.0 && unexplainedM / dtS > maxSpeedMps
    }

    private fun spoofVerdict(
        reason: Reason,
        network: GpsSample?,
        divergenceM: Double?,
        fusedHAccM: Float,
        nowMs: Long,
    ): Verdict {
        val (lat, lon, hAcc, source) = heldDisplay(network, nowMs)
        return Verdict(
            spoofed = true,
            source = source,
            displayLat = lat,
            displayLon = lon,
            displayHAccM = hAcc,
            reason = reason,
            message = spoofUa(source),
            divergenceM = divergenceM,
            fusedHAccM = fusedHAccM,
            netHAccM = network?.accuracyM,
            status = Status.SPOOFED,
            messageEn = spoofEn(source),
            hasPosition = source != Source.NONE,
        )
    }

    /** No fused fix at all: hold the anchor, invent nothing. */
    private fun holdVerdict(network: GpsSample?, reason: Reason, nowMs: Long): Verdict {
        if (!spoofedLatched && network == null && !haveTrusted) {
            return Verdict(
                spoofed = false,
                source = Source.NONE,
                displayLat = 0.0,
                displayLon = 0.0,
                displayHAccM = 0f,
                reason = Reason.NONE,
                message = NO_FIX_UNKNOWN_UA,
                divergenceM = null,
                fusedHAccM = null,
                netHAccM = null,
                status = Status.NO_FIX,
                messageEn = NO_FIX_UNKNOWN_EN,
                hasPosition = false,
            )
        }
        val (lat, lon, hAcc, source) = heldDisplay(network, nowMs)
        return Verdict(
            spoofed = spoofedLatched,
            source = source,
            displayLat = lat,
            displayLon = lon,
            displayHAccM = hAcc,
            reason = if (spoofedLatched) reason else Reason.NONE,
            message = if (spoofedLatched) spoofUa(source) else noFixUa(source),
            divergenceM = null,
            fusedHAccM = null,
            netHAccM = network?.accuracyM,
            status = if (spoofedLatched) Status.SPOOFED else Status.NO_FIX,
            messageEn = if (spoofedLatched) spoofEn(source) else noFixEn(source),
            hasPosition = source != Source.NONE,
        )
    }

    /**
     * Freeze on the last place fused and network agreed — snapping to the coarse
     * anchor would render a teleport of the gate's own making. The anchor leads
     * only after [networkLeadAfterMs], when a frozen puck is the bigger lie.
     */
    private fun heldDisplay(network: GpsSample?, nowMs: Long): Quad {
        if (holdSinceMs == 0L) holdSinceMs = nowMs
        if (!haveTrusted) {
            return network?.let { Quad(it.lat, it.lon, it.accuracyM, Source.NETWORK_HOLD) }
                ?: Quad(0.0, 0.0, 0f, Source.NONE)
        }
        val lead = network?.takeIf {
            nowMs - holdSinceMs >= networkLeadAfterMs &&
                haversineM(trustLat, trustLon, it.lat, it.lon) > (it.accuracyM + divergenceMarginM)
        }
        return lead?.let { Quad(it.lat, it.lon, it.accuracyM, Source.NETWORK_HOLD) }
            ?: Quad(trustLat, trustLon, trustHAccM, Source.HELD)
    }

    private data class Quad(val a: Double, val b: Double, val c: Float, val d: Source)

    // The operator-facing sentences live in [GateSentences]; nothing there decides.

    companion object {
        /**
         * A fused hAcc above this (metres, 1σ) is not worth steering by. The
         * capture climbed from 278 m through 20 km; 1500 m is comfortably above
         * any honest urban fused fix (tens of metres).
         *
         * The ceiling is unchanged since it was set — what changed is what it
         * MEANS. It marks a fix DEGRADED, and may corroborate a contradiction.
         * It is not on its own evidence of anything but a cold chip.
         */
        const val FUSED_HACC_CEILING_M = 1_500.0

        /**
         * Fused may sit this far past the network's OWN admitted error before it
         * is called a lie. Deliberately tight (not "a few km"): the wide version
         * misses slow meaconing, which eases the operator off by tens of metres
         * while staying kinematically plausible. One capture cannot tune this
         * for the slow-drag case — that is why varied captures matter — so this
         * starts conservative and is the first knob to revisit with more data.
         */
        const val DIVERGENCE_MARGIN_M = 150.0

        /** ~1100 km/h. Above this the operator is not in a vehicle; he is in a lie. */
        const val MAX_PLAUSIBLE_SPEED_MPS = 300.0

        /** Past this the operator is travelling, and a network fix lags him. */
        const val DRIVING_SPEED_MPS = 4.0f

        /** How far the margin opens for an ATTESTED drive. */
        const val DRIVING_MARGIN_SCALE = 10.0

        /**
         * ~5 fixes at 1 Hz. Long enough that a spoofer cannot hand the sky back
         * with one plausible frame; short enough that a real tunnel exit is not
         * punished for seconds. Recovery is to a trusted fused fix, not forever.
         */
        const val RECOVER_AGREE_STREAK = 5

        /** Every false trip measured lasted under 10 s; a real capture lasts minutes. */
        const val NETWORK_LEAD_AFTER_MS = 20_000L
        const val NET_LAG_MAX_MS = 60_000L
        // A frozen fix may sit anywhere inside its own radius, which is only 68 %.
        const val STALE_SIGMAS = 2.0

        /** Nothing to show and nothing to accuse: say so. */
        const val NO_FIX_UNKNOWN_UA = "Де ти — поки не знаю"
        const val NO_FIX_UNKNOWN_EN = "Where you are is unknown yet"
    }
}
