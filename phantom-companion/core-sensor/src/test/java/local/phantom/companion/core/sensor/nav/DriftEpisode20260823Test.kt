package local.phantom.companion.core.sensor.nav

import local.phantom.companion.core.sensor.GpsSample
import local.phantom.companion.core.sensor.ImuSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ПОЛЬОВА ПОМИЛКА, 23.08.2026 21:33–21:35 — «точка кудись в жопу зміщується».
 *
 * The owner's phone lay on a desk indoors. `adb logcat -s PhantomEW` recorded the
 * whole episode, and this suite is that recording turned into a fixture: the
 * accuracies, the claimed speeds and the vertical rate below are the numbers
 * from the log, not invented ones.
 *
 * ```
 * 21:33:41  TRUSTED   rawAcc=10m  spd=0.0   acc/rej=32/0
 * 21:34:13  JAMMED    rawAcc=44m  spd=1.3   acc/rej=35/0
 * 21:34:15  DEGRADED  rawAcc=54m  spd=3.9   acc/rej=36/0
 * 21:34:33  SPOOFED   rawAcc=127m spd=6.5   acc/rej=40/3
 *           why=висота GPS суперечить барометру (-12 проти 0 м/с)
 * 21:34:45  JAMMED    rawAcc=119m spd=6.8   acc/rej=43/7
 *           why=прискорення 70 м/с² — так не розганяються
 * 21:34:52  DEGRADED  rawAcc=367m spd=9.1   acc/rej=44/7
 * 21:35:02  SPOOFED   rawAcc=18m  spd=9.3   acc/rej=44/8
 *           why=сигнал повернувся за 290 м від інерційного шляху (допуск 285 м)
 * ```
 *
 * Every verdict fired correctly — and the display walked anyway, because the
 * badge engine's verdict is passive by law and the DISPLAY gate had no physical
 * witness of its own. 44 accepted against 8 refused: the extremes were caught,
 * the walking-pace steps in between were not.
 *
 * Integrating the claimed speeds over the hostile window comes to ~290 m, which
 * is the very number the engine printed at 21:35:02. The fixture reproduces the
 * attack, not an idea of it.
 */
class DriftEpisode20260823Test {

    private val deskLat = 50.4501
    private val deskLon = 30.5234

    /** Seconds since 21:33:40, and the fix's own claimed speed, from the log. */
    private fun claimedSpeed(tS: Double): Float = when {
        tS < 33.6 -> 0.05f
        tS < 35.2 -> 1.3f
        tS < 53.7 -> 3.9f
        tS < 55.7 -> 6.5f
        tS < 72.9 -> 6.8f
        tS < 82.1 -> 9.1f
        tS < HOSTILE_END_S -> 9.3f
        else -> 0.05f
    }

    private fun claimedAcc(tS: Double): Float = when {
        tS < 33.6 -> 10f
        tS < 35.2 -> 44f
        tS < 53.7 -> 54f
        tS < 55.7 -> 127f
        tS < 72.9 -> 119f
        tS < HOSTILE_END_S -> 367f
        else -> 18f
    }

    /**
     * The chip's altitude. Flat while honest; falling at the 12 m/s the
     * barometer contradicted at 21:34:33 while the attack runs.
     */
    private fun claimedAltitude(tS: Double): Float =
        if (tS < HOSTILE_START_S || tS >= HOSTILE_END_S) {
            0f
        } else {
            (-12.0 * (tS - HOSTILE_START_S)).toFloat()
        }

    /**
     * The walk itself: the position a display that believes the claim ends up
     * at. After [HOSTILE_END_S] the sky is honest again and reports the desk —
     * which the gate must still make it EARN.
     */
    private fun hostileTrack(tS: Double): Pair<Double, Double> {
        if (tS < HOSTILE_START_S || tS >= HOSTILE_END_S) return deskLat to deskLon
        var travelled = 0.0
        var t = HOSTILE_START_S
        while (t < minOf(tS, HOSTILE_END_S)) {
            travelled += claimedSpeed(t) * STEP_S
            t += STEP_S
        }
        // Due east, so the whole displacement lands in longitude.
        val metresPerDegLon = 111_320.0 * Math.cos(Math.toRadians(deskLat))
        return deskLat to (deskLon + travelled / metresPerDegLon)
    }

    private fun fixAt(tS: Double): GpsSample {
        val (lat, lon) = hostileTrack(tS)
        return GpsSample(
            lat = lat,
            lon = lon,
            accuracyM = claimedAcc(tS),
            speedMps = claimedSpeed(tS),
            bearingDeg = 90f,
            altitudeM = claimedAltitude(tS),
            elapsedMs = (tS * 1000).toLong(),
        )
    }

    /**
     * A desk. Accelerometer reads gravity plus MEMS noise, the gyroscope reads
     * nothing, the pressure never changes. This is fed through the REAL
     * [BodyWitness] rather than hand-built into a [BodyEvidence] — the point is
     * partly to prove the witness itself calls a desk a desk.
     */
    private class Desk {
        val witness = BodyWitness()
        private val rnd = java.util.Random(23)
        private var t = 0L

        fun advanceTo(untilMs: Long) {
            while (t <= untilMs) {
                witness.onImu(
                    ImuSample(
                        accelX = (rnd.nextGaussian() * 0.01).toFloat(),
                        accelY = (rnd.nextGaussian() * 0.01).toFloat(),
                        accelZ = (9.81 + rnd.nextGaussian() * 0.01).toFloat(),
                        gyroX = (rnd.nextGaussian() * 0.001).toFloat(),
                        gyroY = (rnd.nextGaussian() * 0.001).toFloat(),
                        gyroZ = (rnd.nextGaussian() * 0.001).toFloat(),
                        rotW = 1f, rotX = 0f, rotY = 0f, rotZ = 0f,
                        elapsedMs = t,
                    ),
                    t,
                )
                if (t % 200L == 0L) witness.onPressure(1013.25f, t)
                t += 40L
            }
        }

        fun evidence(nowMs: Long): BodyEvidence {
            advanceTo(nowMs)
            return witness.evidence(nowMs)
        }
    }

    /** What the app renders: the gate's hold when it refuses, the raw fix otherwise. */
    private fun shown(v: ConsistencyGate.Verdict, fused: GpsSample): Pair<Double, Double> =
        if ((v.spoofed || v.refusedFix) && v.hasPosition) v.displayLat to v.displayLon else fused.lat to fused.lon

    private fun metresFromDesk(p: Pair<Double, Double>): Double {
        val dLat = (p.first - deskLat) * 111_320.0
        val dLon = (p.second - deskLon) * 111_320.0 * Math.cos(Math.toRadians(deskLat))
        return Math.hypot(dLat, dLon)
    }

    // ── 1. The failure, reproduced: no body witness, and the puck walks ───────

    @Test
    fun `without the body witness the display walks the spoofed track — the 23_08 failure`() {
        val gate = ConsistencyGate()
        var maxWanderM = 0.0
        var refusals = 0
        var accepts = 0

        var tS = 0.0
        while (tS <= HOSTILE_END_S) {
            val fused = fixAt(tS)
            val v = gate.update(fused, null, (tS * 1000).toLong(), BodyEvidence())
            if (v.spoofed) refusals++ else accepts++
            maxWanderM = maxOf(maxWanderM, metresFromDesk(shown(v, fused)))
            tS += STEP_S
        }

        println("EPISODE-BEFORE: acc/rej=$accepts/$refusals maxWander=${"%.0f".format(maxWanderM)}m")
        assertTrue(
            "the captured episode must reproduce: the display walks hundreds of metres " +
                "(got ${maxWanderM.toInt()} m)",
            maxWanderM > 200.0,
        )
    }

    // ── 2. The fix: the same fixture, with the body telling the truth ─────────

    @Test
    fun `with the body witness the display holds the desk through the whole episode`() {
        val gate = ConsistencyGate()
        val desk = Desk()
        var maxWanderM = 0.0
        var maxWanderDuringAttackM = 0.0
        var refusals = 0

        var tS = 0.0
        while (tS <= HOSTILE_END_S) {
            val nowMs = (tS * 1000).toLong()
            val fused = fixAt(tS)
            val v = gate.update(fused, null, nowMs, desk.evidence(nowMs))
            if (v.spoofed) refusals++
            val wander = metresFromDesk(shown(v, fused))
            maxWanderM = maxOf(maxWanderM, wander)
            if (tS >= HOSTILE_START_S) maxWanderDuringAttackM = maxOf(maxWanderDuringAttackM, wander)
            tS += STEP_S
        }

        println(
            "EPISODE-AFTER: refused=$refusals maxWander=${"%.1f".format(maxWanderM)}m " +
                "duringAttack=${"%.1f".format(maxWanderDuringAttackM)}m",
        )
        assertTrue("the attack must be refused, repeatedly", refusals > 20)
        assertTrue(
            "the puck must not leave the desk while the body says it never moved " +
                "(got ${maxWanderDuringAttackM.toInt()} m)",
            maxWanderDuringAttackM <= HOLD_TOLERANCE_M,
        )
    }

    // ── 3. Recovery walks in; it does not teleport back ──────────────────────

    @Test
    fun `after the attack the sky must walk back in over consecutive clean fixes`() {
        val gate = ConsistencyGate()
        val desk = Desk()

        var tS = 0.0
        while (tS <= HOSTILE_END_S) {
            val nowMs = (tS * 1000).toLong()
            gate.update(fixAt(tS), null, nowMs, desk.evidence(nowMs))
            tS += STEP_S
        }
        assertTrue("the episode must have raised the refusal posture", gate.physicallyHolding)

        // The sky comes back honest, at the desk, at rest.
        var heldFixes = 0
        var recoveredAtS = Double.NaN
        while (tS <= HOSTILE_END_S + 40.0) {
            val nowMs = (tS * 1000).toLong()
            val v = gate.update(fixAt(tS), null, nowMs, desk.evidence(nowMs))
            if (v.spoofed) {
                heldFixes++
            } else if (recoveredAtS.isNaN()) {
                recoveredAtS = tS - HOSTILE_END_S
            }
            tS += STEP_S
        }

        println("EPISODE-RECOVERY: held $heldFixes clean fixes, trusted again after ${recoveredAtS}s")
        assertTrue("recovery must not be instant — the sky has to earn it", heldFixes >= 4)
        assertTrue("but it must happen", !recoveredAtS.isNaN())
        assertTrue("and it must not take forever", recoveredAtS < 20.0)
    }

    // ── 4. The barometer half, on its own (no IMU on this device) ─────────────

    @Test
    fun `a GPS altitude the air contradicts is refused with no IMU at all`() {
        val gate = ConsistencyGate()
        var refusedAtS = Double.NaN

        var tS = 0.0
        while (tS <= 20.0) {
            val nowMs = (tS * 1000).toLong()
            // Flat room, falling GPS altitude, and a fix that claims no speed at
            // all — so only the barometer can object.
            val fused = GpsSample(
                lat = deskLat, lon = deskLon, accuracyM = 20f, speedMps = 0f,
                bearingDeg = null,
                altitudeM = if (tS < 8.0) 0f else (-12.0 * (tS - 8.0)).toFloat(),
                elapsedMs = nowMs,
            )
            val body = BodyEvidence(baroAltitudeM = 0f, baroClimbMps = 0f)
            val v = gate.update(fused, null, nowMs, body)
            if (v.spoofed && refusedAtS.isNaN()) refusedAtS = tS
            tS += STEP_S
        }

        assertTrue("the air must be allowed to refuse a forged altitude", !refusedAtS.isNaN())
        assertEquals(
            "and it must not fire on the honest, flat stretch",
            true, refusedAtS > 8.0,
        )
    }

    // ── 5. …and it must not fire on an honest drive ──────────────────────────

    @Test
    fun `an honest drive with a vibrating phone is never refused`() {
        val gate = ConsistencyGate()
        val witness = BodyWitness()
        val rnd = java.util.Random(11)
        var refusals = 0
        var imuMs = 0L

        var tS = 0.0
        while (tS <= 120.0) {
            val nowMs = (tS * 1000).toLong()
            // A car: road texture on the accelerometer, lane corrections on the
            // gyroscope, a real climb the barometer agrees with.
            while (imuMs <= nowMs) {
                witness.onImu(
                    ImuSample(
                        accelX = (rnd.nextGaussian() * 0.5).toFloat(),
                        accelY = (rnd.nextGaussian() * 0.5).toFloat(),
                        accelZ = (9.81 + rnd.nextGaussian() * 0.5).toFloat(),
                        gyroX = (rnd.nextGaussian() * 0.05).toFloat(),
                        gyroY = (rnd.nextGaussian() * 0.05).toFloat(),
                        gyroZ = (rnd.nextGaussian() * 0.05).toFloat(),
                        rotW = 1f, rotX = 0f, rotY = 0f, rotZ = 0f,
                        elapsedMs = imuMs,
                    ),
                    imuMs,
                )
                imuMs += 40L
            }
            // 1013.25 hPa minus ~0.12 hPa per second ≈ a 1 m/s climb.
            witness.onPressure((1013.25 - 0.12 * tS).toFloat(), nowMs)

            val metresPerDegLon = 111_320.0 * Math.cos(Math.toRadians(deskLat))
            val fused = GpsSample(
                lat = deskLat,
                lon = deskLon + (18.0 * tS) / metresPerDegLon,
                accuracyM = 12f,
                speedMps = 18f,
                bearingDeg = 90f,
                altitudeM = (1.0 * tS).toFloat(),
                elapsedMs = nowMs,
            )
            if (gate.update(fused, null, nowMs, witness.evidence(nowMs)).spoofed) refusals++
            tS += STEP_S
        }

        println("HONEST-DRIVE: refusals=$refusals over 120 s at 65 km/h")
        assertEquals("an honest drive must pass untouched", 0, refusals)
    }

    // ── 6. A fix may not certify its own motion to widen its own gate ────────

    /**
     * The divergence margin opens tenfold for a moving operator, because a
     * network fix lags a drive. It used to read the speed off the fix under
     * judgement — so «I am doing 6,5 m/s» bought the liar a 1,5 km gate. The
     * attestation now has to come from the last AGREEING fix or from the body.
     */
    private fun frozenSpoofRefused(anchorSpeedMps: Float): ConsistencyGate.Reason {
        val gate = ConsistencyGate()
        val metresPerDegLon = 111_320.0 * Math.cos(Math.toRadians(deskLat))
        var netElapsed = 1_000L

        fun network(t: Long) = GpsSample(
            lat = deskLat, lon = deskLon, accuracyM = 40f, speedMps = null,
            bearingDeg = null, elapsedMs = t,
        )

        // Establish the anchor: fused and network agree, at the stated speed.
        repeat(4) {
            val t = netElapsed
            gate.update(
                GpsSample(
                    lat = deskLat, lon = deskLon, accuracyM = 20f,
                    speedMps = anchorSpeedMps, bearingDeg = null, elapsedMs = t,
                ),
                network(t), t,
            )
            netElapsed += 1_000L
        }

        // Let real time pass before the frozen fix appears. Without this the
        // 400 m gap is only a second old, which is a 320 m/s teleport — the
        // KINEMATIC rule refuses it on its own and the margin under test is
        // never reached. (Caught by the gate on the first run: this pair was
        // passing and failing for reasons that had nothing to do with it.)
        netElapsed += 20_000L

        // The sky freezes 400 m away and claims to be driving, while the
        // network keeps answering, on time, from the desk.
        val frozen = GpsSample(
            lat = deskLat, lon = deskLon + 400.0 / metresPerDegLon, accuracyM = 60f,
            speedMps = 6.5f, bearingDeg = 90f, elapsedMs = netElapsed,
        )
        var refused = ConsistencyGate.Reason.NONE
        repeat(3) {
            val v = gate.update(frozen, network(netElapsed), netElapsed)
            if (v.spoofed && refused == ConsistencyGate.Reason.NONE) refused = v.reason
            netElapsed += 1_000L
        }
        return refused
    }

    @Test
    fun `a parked anchor refuses the frozen fix that claims to be driving`() {
        // STALENESS specifically: a frozen sky on a position the network
        // contradicts. Asserting merely «refused» would also pass on KINEMATIC,
        // which has nothing to do with the margin this test exists to guard.
        assertEquals(
            "the fix must not be able to widen its own margin by claiming speed",
            ConsistencyGate.Reason.STALENESS, frozenSpoofRefused(anchorSpeedMps = 0f),
        )
    }

    @Test
    fun `an attested drive keeps the wide margin — an honest lagging network is not a lie`() {
        assertEquals(
            "a drive the network already agreed with must keep its widened margin",
            ConsistencyGate.Reason.NONE, frozenSpoofRefused(anchorSpeedMps = 18f),
        )
    }

    private companion object {
        const val STEP_S = 1.0

        /** 21:34:13 in the capture — the first hostile fix. */
        const val HOSTILE_START_S = 33.6

        /** 21:35:02 — the re-acquisition 290 m off the inertial path. */
        const val HOSTILE_END_S = 82.1

        /**
         * The puck may still breathe with the accepted fixes BEFORE the attack
         * starts; what it may not do is travel. Well under the width of a room.
         */
        const val HOLD_TOLERANCE_M = 5.0
    }
}
