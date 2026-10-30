package local.phantom.companion.core.sensor.nav

import local.phantom.companion.core.sensor.GpsSample
import local.phantom.companion.core.sensor.ImuSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHANTOM-EW — the engine's promises, as tests. Every one of these is a
 * scenario an operator can actually be put in by an adversary.
 */
class CustomLocationEngineTest {

    private fun fix(lat: Double, lon: Double, acc: Float = 8f, speed: Float? = 10f) =
        GpsSample(lat = lat, lon = lon, accuracyM = acc, speedMps = speed, bearingDeg = 0f)

    /** A still phone, with a valid rotation quaternion (identity = facing north). */
    private fun imuStill() = ImuSample(
        accelX = 0f, accelY = 0f, accelZ = 9.81f,
        gyroX = 0f, gyroY = 0f, gyroZ = 0f,
        rotW = 1f, rotX = 0f, rotY = 0f, rotZ = 0f,
    )

    @Test
    fun `clean fixes are trusted and tracked`() {
        val engine = CustomLocationEngine()
        var t = 0L
        // Walking north up Khreshchatyk, one fix a second.
        repeat(5) { i ->
            engine.onGps(fix(50.4500 + i * 0.0001, 30.5230), t)
            t += 1_000
        }
        assertEquals(LocationTrust.TRUSTED, engine.trust.value)
        val f = assertNotNull(engine.fix.value).let { engine.fix.value!! }
        assertEquals(50.4504, f.lat, 0.0005)
        assertTrue("a trusted fix is not dead-reckoned", !f.dead)
    }

    @Test
    fun `a 15km leap is refused and never becomes the new reference`() {
        val engine = CustomLocationEngine()
        engine.onGps(fix(50.4500, 30.5230), 0L)
        engine.onGps(fix(50.4501, 30.5230), 1_000L)

        // The spoofer drags the receiver 15 km east, then keeps talking from
        // there as if nothing happened.
        engine.onGps(fix(50.4501, 30.7330), 2_000L)
        assertTrue(engine.lastRejection.value!!.contains("стрибок"))

        engine.onGps(fix(50.4501, 30.7331), 3_000L)
        engine.onGps(fix(50.4501, 30.7332), 4_000L)

        // Three impossible fixes in a row: this is a campaign, not a glitch.
        assertEquals(LocationTrust.SPOOFED, engine.trust.value)

        // And critically — PHANTOM is still standing where the operator really
        // is, not halfway to the spoofer's chosen position. A gate that let the
        // rejected fix update its reference would have walked there.
        val f = engine.fix.value!!
        assertEquals(30.5230, f.lon, 0.005)
    }

    @Test
    fun `the session tally separates what was believed from what was refused`() {
        val engine = CustomLocationEngine()
        engine.onGps(fix(50.4500, 30.5230), 0L)
        engine.onGps(fix(50.4501, 30.5230), 1_000L)
        // One teleport, refused.
        engine.onGps(fix(50.4501, 30.7330), 2_000L)

        assertEquals(2L, engine.acceptedFixes)
        assertEquals(1L, engine.refusedFixes)
    }

    @Test
    fun `impossible speed is refused`() {
        val gate = SpoofGate()
        gate.judge(fix(50.4500, 30.5230), 0L)
        // 500 m in 1 s = 1800 km/h.
        val v = gate.judge(fix(50.4545, 30.5230), 1_000L)
        assertTrue(!v.accepted)
    }

    @Test
    fun `a GPS altitude the barometer contradicts is refused`() {
        val gate = SpoofGate()
        gate.judge(fix(50.4500, 30.5230), 0L, gpsAltitudeM = 180f, baroClimbMps = 0f)
        // GPS claims a 100 m climb in one second while the air says: flat.
        val v = gate.judge(fix(50.4501, 30.5230), 1_000L, gpsAltitudeM = 280f, baroClimbMps = 0f)
        assertEquals(SpoofGate.Reject.ALTITUDE_DISAGREES, v.reject)
    }

    @Test
    fun `silence arms dead reckoning and the doubt grows`() {
        val engine = CustomLocationEngine()
        var t = 0L
        repeat(4) { i ->
            engine.onGps(fix(50.4500 + i * 0.0001, 30.5230), t)
            t += 1_000
        }
        val certain = engine.fix.value!!.uncertaintyM

        // The sky goes out. The host keeps ticking.
        repeat(20) {
            t += 1_000
            engine.onTick(t)
            engine.onImu(imuStill(), t)
        }
        assertEquals(LocationTrust.JAMMED, engine.trust.value)

        val blind = engine.fix.value!!
        assertTrue("a jammed fix must confess it is inertial", blind.dead)
        assertTrue(
            "the doubt must GROW while blind (was $certain, now ${blind.uncertaintyM})",
            blind.uncertaintyM > certain * 2,
        )
    }

    @Test
    fun `the barometer separates the road from the stairwell`() {
        val alt = Altimeter()
        var t = 0L
        alt.onPressure(1013.0f, t)
        // Climbing: ~0.6 hPa is ~5 m. Held for four seconds — a stairwell.
        repeat(8) {
            t += 500
            alt.onPressure(1013.0f - (it + 1) * 0.12f, t)
        }
        assertEquals(VerticalContext.VERTICAL_TRANSIT, alt.context)
        assertTrue("climbed some metres, got ${alt.altitudeM}", alt.altitudeM > 3f)
    }

    @Test
    fun `a still operator does not glide across the map while jammed`() {
        val engine = CustomLocationEngine()
        engine.onGps(fix(50.4500, 30.5230, speed = 0f), 0L)
        engine.onGps(fix(50.4500, 30.5230, speed = 0f), 1_000L)

        var t = 1_000L
        repeat(30) {
            t += 200
            engine.onImu(imuStill(), t)
            engine.onTick(t)
        }
        val f = engine.fix.value!!
        // He stood still. The estimate stands still with him — the position is
        // held, and only the uncertainty grows.
        assertEquals(50.4500, f.lat, 0.0002)
        assertEquals(30.5230, f.lon, 0.0002)
    }

    // ── STATION-KEEPING (field report 2026-07-18) — «стоячи вдома карта
    // стрибає щосекунди»: 1 Hz indoor multipath under a TRUSTED sky. ──────

    @Test
    fun `a still body's dot does not chase indoor scatter`() {
        val engine = CustomLocationEngine()
        var t = 0L
        // Settle at home: clean fixes, the chip reporting a standstill.
        repeat(3) {
            engine.onGps(fix(50.4500, 30.5230, acc = 10f, speed = 0f), t)
            t += 1_000
        }
        // Indoor multipath: fixes leaping ~8 m side to side once a second,
        // each claiming decent accuracy, all reporting ~zero speed.
        var maxWanderM = 0.0
        repeat(30) { i ->
            val lon = 30.5230 + (if (i % 2 == 0) 0.00011 else -0.00011)
            engine.onGps(fix(50.4500, lon, acc = 12f, speed = 0f), t)
            t += 1_000
            val f = engine.fix.value!!
            maxWanderM = maxOf(
                maxWanderM,
                Math.hypot(
                    latToMeters(f.lat - 50.4500),
                    lonToMeters(f.lon - 30.5230, 50.4500),
                ),
            )
        }
        assertTrue("parked dot wandered ${"%.1f".format(maxWanderM)} m", maxWanderM < 4.0)
    }

    @Test
    fun `station-keeping releases the instant the body moves`() {
        val engine = CustomLocationEngine()
        var t = 0L
        repeat(10) {
            engine.onGps(fix(50.4500, 30.5230, acc = 10f, speed = 0f), t)
            t += 1_000
        }
        // The car pulls away north at ~14 m/s; the chip says so.
        var lat = 50.4500
        repeat(6) {
            lat += 0.000126 // ~14 m north per second
            engine.onGps(fix(lat, 30.5230, acc = 8f, speed = 14f), t)
            t += 1_000
        }
        val f = engine.fix.value!!
        // Six seconds into the launch the estimate must be riding with the
        // car, not parked at home by a sticky standstill gate.
        assertTrue(
            "estimate lagged the launch by ${"%.1f".format(latToMeters(lat - f.lat))} m",
            latToMeters(lat - f.lat) < 25.0,
        )
        assertTrue("speed must be adopted (got ${f.speedMps})", f.speedMps > 8f)
    }

    // ── FIELD DEADLOCK (home, 2026-07-18 20:09) — a deep-indoor chip
    // answers once per 10–30 s at ±100 m. The old two-fix consistency test
    // (6 s / 120 m, calibrated for a 1 Hz sky) could never be passed, and
    // the engine sat in SPOOFED·DR at a kitchen table with σ at 600 km,
    // «чекаю другого підтвердження» forever. ──────────────────────────────

    @Test
    fun `a sparse indoor sky recovers a drifted inertial track`() {
        val engine = CustomLocationEngine()
        var t = 0L
        // A clean drive north at 20 m/s for a minute.
        var lat = 50.4500
        repeat(60) {
            engine.onGps(fix(lat, 30.5230, acc = 8f, speed = 20f), t)
            lat += 0.00018 // ~20 m north per second
            t += 1_000
        }
        // The signal dies for two minutes; the filter carries the motion,
        // the inertial error budget grows past witness standing.
        repeat(120) {
            t += 1_000
            engine.onTick(t)
        }
        assertEquals(LocationTrust.JAMMED, engine.trust.value)

        // The sky returns the way a kitchen table gets it: one ±80 m fix
        // per 20 seconds, scattered ~100 m apart, far from the drifted
        // inertial track (the car actually stopped where the signal died).
        val homeLat = lat
        engine.onGps(fix(homeLat, 30.5230, acc = 80f, speed = 0f), t)
        assertEquals(
            "first far-from-track return must be refused pending corroboration",
            LocationTrust.SPOOFED,
            engine.trust.value,
        )
        t += 20_000
        engine.onGps(fix(homeLat + 0.0009, 30.5230, acc = 80f, speed = 0f), t)

        // Two mutually consistent fixes — 20 s and ~100 m apart, both
        // admitting ±80 m — are the sky corroborating itself. The engine
        // must yield to DEGRADED and stand where the operator stands, not
        // hold a hallucination another hour.
        assertEquals(LocationTrust.DEGRADED, engine.trust.value)
        val f = engine.fix.value!!
        assertEquals(homeLat + 0.0009, f.lat, 0.003)
        assertTrue("the fix is sky-fused again, not dead-reckoned", !f.dead)
    }

    // ── NAV P0 (owner field failure, 2026-07) — «ніколи не був під глушінням,
    // а мітку тягне куди хоче». On a CLEAN, good-accuracy sky the displayed
    // puck must never leave the raw coordinates: no spurious dead reckoning.
    // Dead reckoning is for PROVEN degradation only. ─────────────────────────

    @Test
    fun `a clean GPS track is never dead-reckoned off its own coordinates`() {
        val engine = CustomLocationEngine()

        val lat0 = 50.4500
        val lon0 = 30.5230
        val speedMps = 25.0
        val metersPerDegLat = 111_320.0

        // The regression is EXERCISED, not assumed. The IMU is the field's worst
        // honest case: the phone slowly re-gripped in its cradle, its yaw
        // swinging 0→90° across a minute. The DriftAudit's inertial witness
        // steers by that yaw delta (a cupholder is not a compass), so its track
        // curves away from the dead-straight sky and the 60 s window closes on a
        // DIVERGE — the exact trigger that used to seize the position. Pure yaw
        // about the vertical injects no phantom acceleration; it only swings the
        // reckoned course. Before the recalibration this dead-reckons the
        // operator off a clean sky; after it, the puck stays glued to raw GPS.
        fun imuYawed(yawDeg: Double): ImuSample {
            val half = Math.toRadians(yawDeg) / 2.0
            return ImuSample(
                accelX = 0f, accelY = 0f, accelZ = 9.81f,
                gyroX = 0f, gyroY = 0f, gyroZ = 0f,
                rotW = Math.cos(half).toFloat(), rotX = 0f, rotY = 0f,
                rotZ = Math.sin(half).toFloat(),
            )
        }

        var t = 0L
        var lastGpsMs = -1_000L
        var lastTickMs = 0L
        // Cross-track = lateral wander off the dead-straight road line, the exact
        // «тягне куди хоче» the operator sees. A dead-reckoned puck curves EAST
        // with the yawed inertial track (hundreds of metres); a fused one holds
        // the line. Along-track = the constant-velocity filter's honest update
        // lag at speed (it trails a fraction of a second behind, invisible on a
        // map) — bounded, but not what «drift» means.
        var worstCrossTrackM = 0.0
        var worstTotalM = 0.0
        var everDead = false
        var everSpoofed = false
        // 150 s — past two full 60 s DriftAudit windows.
        repeat(150 * 50) {
            val elapsedS = t / 1000.0
            val north = speedMps * elapsedS
            val lat = lat0 + north / metersPerDegLat
            val yaw = (elapsedS / 60.0 * 90.0).coerceAtMost(90.0)

            engine.onImu(imuYawed(yaw), t)

            if (t - lastGpsMs >= 1_000) {
                lastGpsMs = t
                // Exact, clean, good-accuracy sky — the raw coordinates the
                // puck is forbidden to drift from.
                engine.onGps(
                    GpsSample(
                        lat = lat, lon = lon0,
                        accuracyM = 8f, speedMps = speedMps.toFloat(), bearingDeg = 0f,
                    ),
                    t,
                )
                val f = engine.fix.value!!
                if (elapsedS > 5.0) { // let the cold-start filter settle
                    worstCrossTrackM =
                        maxOf(worstCrossTrackM, Math.abs(lonToMeters(f.lon - lon0, lat)))
                    worstTotalM = maxOf(
                        worstTotalM,
                        Math.hypot(latToMeters(f.lat - lat), lonToMeters(f.lon - lon0, lat)),
                    )
                }
                if (f.dead) everDead = true
                if (f.trust == LocationTrust.SPOOFED) everSpoofed = true
            }

            if (t - lastTickMs >= 1_000) {
                lastTickMs = t
                engine.onTick(t)
            }
            t += 20
        }

        assertTrue("a clean sky must never be dead-reckoned", !everDead)
        assertTrue("a clean sky must never be seized as SPOOFED", !everSpoofed)
        // The load-bearing assertion: the puck never wanders off the road line.
        // Not even a metre — a dead-reckoned puck would be hundreds off.
        assertTrue(
            "the puck wandered ${"%.2f".format(worstCrossTrackM)} m off the clean " +
                "straight track — the engine dead-reckoned off a good sky",
            worstCrossTrackM < 1.0,
        )
        // And the total offset stays within the filter's honest lag, nowhere
        // near the kilometres a curved inertial track would accrue.
        assertTrue(
            "total offset ${"%.2f".format(worstTotalM)} m exceeds honest filter lag",
            worstTotalM < 5.0,
        )
        assertEquals("a clean sky must never be refused", 0L, engine.refusedFixes)
    }
}
