package local.phantom.companion.core.sensor.nav.sim

import kotlin.math.max
import kotlin.random.Random
import local.phantom.companion.core.sensor.nav.CustomLocationEngine
import local.phantom.companion.core.sensor.nav.LocationTrust

/**
 * PHANTOM-EW — the adversary, and the scoreboard.
 *
 * Each scenario is something the sky can do to an operator. The engine is fed
 * the sky's version of events; the simulator holds the truth and grades it.
 *
 * The headline metric is not drift. It is **honesty**: `FusedFix.uncertaintyM`
 * is the engine's own sworn statement about how wrong it might be, and the
 * whole design rests on that number being trustworthy («a navigator that says
 * ±300 m is useful; one that draws a confident dot in the wrong street is how
 * people die»). So the question the matrix asks, over and over, is: *was the
 *真 error ever much larger than the doubt you admitted to?*
 */

sealed interface Sky {
    /** GPS is fine. */
    data class Clear(val accuracyM: Float = 8f) : Sky

    /** Fixes land, but poor — an urban canyon. */
    data class Canyon(val accuracyM: Float = 45f) : Sky

    /** No fixes at all: a tunnel, an underpass, a jammer. */
    data class Blackout(val seconds: Int) : Sky

    /** The crude spoof: a confident fix, somewhere else entirely. */
    data class Teleport(val offsetM: Double = 5_000.0, val seconds: Int = 60) : Sky

    /**
     * The patient spoof — the one that should frighten us.
     *
     * No teleport, no impossible speed: the adversary simply adds a small,
     * physically plausible offset that grows. Every individual fix passes the
     * SpoofGate, because every individual fix is *possible*. The gate rejects
     * the impossible, not the merely false.
     */
    data class Drag(val dragMps: Double, val seconds: Int) : Sky

    /** GPS altitude climbs while the air pressure says otherwise. */
    data class AltitudeLie(val climbMps: Double = 20.0, val seconds: Int = 30) : Sky
}

data class Scenario(
    val name: String,
    val profile: DriveProfile,
    val sky: Sky,
    /** Degrees the phone is rotated from the direction of travel. */
    val mountYawDeg: Double = 0.0,
    val warmupS: Int = 60,
    val seed: Long = 1L,
)

/** What the run found. All metres. */
data class Verdict(
    val scenario: String,
    val samples: Int,
    val meanErrorM: Double,
    val maxErrorM: Double,
    /** Error at the end of the event — how lost the operator was left. */
    val finalErrorM: Double,
    /** Mean of the engine's own claimed 1σ. */
    val meanClaimedM: Double,
    /**
     * The contract breach: the largest ratio of true error to the engine's own
     * admitted doubt. ≤1 means it never claimed more than it knew. Large means
     * it was confidently wrong, which is the one thing it must never be.
     */
    val worstHonestyRatio: Double,
    /** Fraction of fixes where true error exceeded the claimed 1σ. */
    val beyond1Sigma: Double,
    val refused: Long,
    val accepted: Long,
    /** Seconds from the attack starting to the engine calling it. Null = never. */
    val timeToDetectS: Double?,
    /** How far the adversary moved the operator's believed position. */
    val spoofDisplacementM: Double,
    val endTrust: LocationTrust,
    /** Worst layer-5 disagreement seen, and the budget it was judged against. */
    val maxDriftResidualM: Double = 0.0,
    val maxDriftBudgetM: Double = 0.0,
)

class EwSimulator(private val s: Scenario) {

    private val rng = Random(s.seed)
    private val track = TruthTrack(s.profile, rng)
    private val engine = CustomLocationEngine()

    fun run(): Verdict {
        val dtS = IMU_PERIOD_MS / 1000.0
        var tMs = 1_000L

        val eventS = when (val sky = s.sky) {
            is Sky.Blackout -> sky.seconds
            is Sky.Teleport -> sky.seconds
            is Sky.Drag -> sky.seconds
            is Sky.AltitudeLie -> sky.seconds
            else -> 120
        }
        val totalS = s.warmupS + eventS + TAIL_S
        val steps = (totalS * 1000L / IMU_PERIOD_MS).toInt()

        var n = 0
        var sumErr = 0.0
        var maxErr = 0.0
        var sumClaimed = 0.0
        var worstRatio = 0.0
        var beyond = 0
        var finalErr = 0.0
        var detectAtS: Double? = null
        var spoofDisplacement = 0.0
        var maxResidual = 0.0
        var maxBudget = 0.0

        var lastGpsMs = 0L
        var lastTickMs = 0L
        var lastBaroMs = 0L

        for (i in 0 until steps) {
            val truth = track.step(dtS, tMs)
            val elapsedS = (tMs - 1_000L) / 1000.0
            val inEvent = elapsedS >= s.warmupS && elapsedS < s.warmupS + eventS

            // — IMU, always. It is what carries the operator when the sky dies.
            engine.onImu(imuOf(truth, track.lastAlongAccel, s.mountYawDeg, rng), tMs)

            // — Barometer, 2 Hz. Under an altitude spoof it is the only witness.
            if (tMs - lastBaroMs >= 500) {
                lastBaroMs = tMs
                engine.onPressure(pressureOf(truth.altitudeM), tMs)
            }

            // — GPS, 1 Hz, as the sky chooses to tell it.
            if (tMs - lastGpsMs >= GPS_PERIOD_MS) {
                lastGpsMs = tMs
                feedGps(truth, tMs, inEvent, elapsedS)
            }

            // — The host's liveness tick. Without it a blackout is silence, and
            //   silence is indistinguishable from a quiet moment.
            if (tMs - lastTickMs >= 1_000) {
                lastTickMs = tMs
                engine.onTick(tMs)
            }

            if (engine.driftResidualM > maxResidual) {
                maxResidual = engine.driftResidualM
                maxBudget = engine.driftBudgetM
            }

            val fix = engine.fix.value
            if (fix != null && elapsedS >= s.warmupS) {
                val err = haversineM(
                    track.latOf(truth), track.lonOf(truth),
                    fix.lat, fix.lon,
                )
                val claimed = max(fix.uncertaintyM.toDouble(), 1.0)
                n++
                sumErr += err
                sumClaimed += claimed
                if (err > maxErr) maxErr = err
                val ratio = err / claimed
                if (ratio > worstRatio) worstRatio = ratio
                if (err > claimed) beyond++
                finalErr = err

                if (detectAtS == null && isAttack() &&
                    (fix.trust == LocationTrust.SPOOFED || fix.trust == LocationTrust.JAMMED)
                ) {
                    detectAtS = elapsedS - s.warmupS
                }
                if (inEvent && isSpoof()) spoofDisplacement = max(spoofDisplacement, err)
            }

            tMs += IMU_PERIOD_MS
        }

        return Verdict(
            scenario = s.name,
            samples = n,
            meanErrorM = if (n > 0) sumErr / n else 0.0,
            maxErrorM = maxErr,
            finalErrorM = finalErr,
            meanClaimedM = if (n > 0) sumClaimed / n else 0.0,
            worstHonestyRatio = worstRatio,
            beyond1Sigma = if (n > 0) beyond.toDouble() / n else 0.0,
            refused = engine.refusedFixes,
            accepted = engine.acceptedFixes,
            timeToDetectS = detectAtS,
            spoofDisplacementM = spoofDisplacement,
            endTrust = engine.trust.value,
            maxDriftResidualM = maxResidual,
            maxDriftBudgetM = maxBudget,
        )
    }

    private fun isAttack(): Boolean = s.sky !is Sky.Clear && s.sky !is Sky.Canyon

    private fun isSpoof(): Boolean =
        s.sky is Sky.Teleport || s.sky is Sky.Drag || s.sky is Sky.AltitudeLie

    /** How the sky reports this instant. */
    private fun feedGps(truth: Truth, tMs: Long, inEvent: Boolean, elapsedS: Double) {
        val baseAcc = when (val sky = s.sky) {
            is Sky.Canyon -> sky.accuracyM
            is Sky.Clear -> sky.accuracyM
            else -> 8f
        }

        if (!inEvent) {
            engine.onGps(
                gpsOf(track, truth, baseAcc, rng, truth.altitudeM.toFloat()),
                tMs,
                truth.altitudeM.toFloat(),
            )
            return
        }

        when (val sky = s.sky) {
            is Sky.Clear, is Sky.Canyon ->
                engine.onGps(
                    gpsOf(track, truth, baseAcc, rng, truth.altitudeM.toFloat()),
                    tMs,
                    truth.altitudeM.toFloat(),
                )

            // The sky simply stops talking. Nothing is fed at all — which is
            // the honest simulation of a tunnel: no fix, not a bad fix.
            is Sky.Blackout -> Unit

            is Sky.Teleport -> {
                val lied = truth.copy(east = truth.east + sky.offsetM)
                engine.onGps(
                    gpsOf(track, lied, 6f, rng, truth.altitudeM.toFloat()),
                    tMs,
                    truth.altitudeM.toFloat(),
                )
            }

            is Sky.Drag -> {
                // Plausible physics, false position: the offset grows at a walking
                // pace the gate has no grounds to call impossible.
                val into = elapsedS - s.warmupS
                val lied = truth.copy(east = truth.east + sky.dragMps * into)
                engine.onGps(
                    gpsOf(track, lied, 6f, rng, truth.altitudeM.toFloat()),
                    tMs,
                    truth.altitudeM.toFloat(),
                )
            }

            is Sky.AltitudeLie -> {
                val into = elapsedS - s.warmupS
                val fakeAlt = (truth.altitudeM + sky.climbMps * into).toFloat()
                engine.onGps(
                    gpsOf(track, truth, 6f, rng, fakeAlt),
                    tMs,
                    fakeAlt,
                )
            }
        }
    }

    companion object {
        /** 50 Hz — a normal Android game-rate IMU. */
        const val IMU_PERIOD_MS = 20L
        const val GPS_PERIOD_MS = 1_000L

        /** Time after the event, to watch the engine recover (or not). */
        const val TAIL_S = 30
    }
}
