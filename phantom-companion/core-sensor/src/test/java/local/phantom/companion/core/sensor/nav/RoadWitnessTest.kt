package local.phantom.companion.core.sensor.nav

import local.phantom.companion.core.sensor.GpsSample
import local.phantom.companion.core.sensor.ImuSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ATLAS §7 — the road witness's contract, pinned BEFORE activation:
 *
 *  1. Dormant by default: a witness-less engine behaves exactly as the
 *     field-repaired baseline (the Re-Drive QA baseline).
 *  2. The witness may vouch: a returning sky that rides a connected road
 *     chain is believed (as DEGRADED) after [CustomLocationEngine
 *     .ROAD_WITNESS_MIN_CHAIN] fixes, not after twenty seconds of grace.
 *  3. The witness may never veto: off-road testimony leaves every refusal
 *     rule exactly as it was — «off the road» is a courtyard, not an attack.
 */
class RoadWitnessTest {

    private val lat0 = 50.4501
    private val lon0 = 30.5234

    private fun northOf(m: Double) = lat0 + Math.toDegrees(m / 6_371_000.0)
    private fun eastOf(m: Double) =
        lon0 + Math.toDegrees(m / (6_371_000.0 * Math.cos(Math.toRadians(lat0))))

    private fun fix(northM: Double, eastM: Double = 0.0, speed: Float = 11f, acc: Float = 5f) =
        GpsSample(
            lat = northOf(northM),
            lon = eastOf(eastM),
            accuracyM = acc,
            speedMps = speed,
            bearingDeg = 0f,
        )

    /** Driving due north, phone flat and aligned. */
    private fun imu() = ImuSample(
        accelX = 0f, accelY = 0f, accelZ = 9.81f,
        gyroX = 0f, gyroY = 0f, gyroZ = 0f,
        rotW = 1f, rotX = 0f, rotY = 0f, rotZ = 0f,
    )

    /** Feed IMU at 50 Hz across a span, ticking the engine every second. */
    private fun coast(engine: CustomLocationEngine, fromMs: Long, forMs: Long): Long {
        var t = fromMs
        val end = fromMs + forMs
        var nextTick = fromMs + 1_000
        while (t < end) {
            t += 20
            engine.onImu(imu(), t)
            if (t >= nextTick) {
                engine.onTick(t)
                nextTick += 1_000
            }
        }
        return t
    }

    /**
     * Ten trusted seconds driving north, then a 30 s blackout the inertial
     * track coasts through. Returns the time cursor; the engine's admitted
     * error stays well inside witness standing, so the grace machinery —
     * not the two-consistent-fixes concession — is what the returning sky
     * faces.
     */
    private fun driveIntoBlackout(engine: CustomLocationEngine): Long {
        var t = 0L
        for (i in 0..10) {
            engine.onGps(fix(northM = i * 11.0), t)
            t += 1_000
        }
        assertEquals(LocationTrust.TRUSTED, engine.trust.value)
        t = coast(engine, t, 30_000)
        assertEquals(LocationTrust.JAMMED, engine.trust.value)
        return t
    }

    /** Returning fixes: same latitude story, but two kilometres east of the
     *  inertial track — far beyond any reacquisition budget. */
    private fun returningFix(i: Int) = fix(northM = 110.0 + i * 11.0, eastM = 2_000.0)

    // ── 1. Dormancy ────────────────────────────────────────────────────────

    @Test
    fun `without a witness, the returning sky is refused through the grace window`() {
        val engine = CustomLocationEngine()
        var t = driveIntoBlackout(engine)

        repeat(CustomLocationEngine.ROAD_WITNESS_MIN_CHAIN) { i ->
            engine.onGps(returningFix(i), t)
            t += 1_000
        }

        assertEquals(
            "the baseline must keep refusing a disagreeing sky this early in the grace window",
            LocationTrust.SPOOFED,
            engine.trust.value,
        )
    }

    // ── 2. The witness may vouch ───────────────────────────────────────────

    @Test
    fun `a road-coherent returning sky is believed as DEGRADED at chain length`() {
        var calls = 0
        val engine = CustomLocationEngine(
            roadWitness = { _, _, _, _, _ ->
                calls++
                RoadTestimony(onRoad = true, confidence = 0.9f, chainLength = calls)
            },
        )
        var t = driveIntoBlackout(engine)

        repeat(CustomLocationEngine.ROAD_WITNESS_MIN_CHAIN) { i ->
            engine.onGps(returningFix(i), t)
            t += 1_000
        }

        assertEquals(
            "topology corroborated the sky; the engine must concede — as DEGRADED",
            LocationTrust.DEGRADED,
            engine.trust.value,
        )
        assertEquals(
            "every disagreeing fix must have fed the witness's chain",
            CustomLocationEngine.ROAD_WITNESS_MIN_CHAIN,
            calls,
        )
        assertTrue(
            "the concession must say out loud that the ROADS vouched",
            engine.lastRejection.value.orEmpty().contains("дорожньої мережі"),
        )
    }

    @Test
    fun `an immature chain does not vouch`() {
        val engine = CustomLocationEngine(
            roadWitness = { _, _, _, _, _ ->
                RoadTestimony(
                    onRoad = true,
                    confidence = 0.9f,
                    chainLength = CustomLocationEngine.ROAD_WITNESS_MIN_CHAIN - 1,
                )
            },
        )
        var t = driveIntoBlackout(engine)

        repeat(CustomLocationEngine.ROAD_WITNESS_MIN_CHAIN) { i ->
            engine.onGps(returningFix(i), t)
            t += 1_000
        }

        assertEquals(LocationTrust.SPOOFED, engine.trust.value)
    }

    // ── 3. The witness may never veto ──────────────────────────────────────

    @Test
    fun `off-road testimony changes nothing — same refusals as the bare baseline`() {
        val bare = CustomLocationEngine()
        val offRoad = CustomLocationEngine(
            roadWitness = { _, _, _, _, _ ->
                RoadTestimony(onRoad = false, confidence = 0f, chainLength = 0)
            },
        )

        for (engine in listOf(bare, offRoad)) {
            var t = driveIntoBlackout(engine)
            repeat(CustomLocationEngine.ROAD_WITNESS_MIN_CHAIN) { i ->
                engine.onGps(returningFix(i), t)
                t += 1_000
            }
        }

        assertEquals(bare.trust.value, offRoad.trust.value)
        assertEquals(bare.refusedFixes, offRoad.refusedFixes)
        assertEquals(bare.acceptedFixes, offRoad.acceptedFixes)
    }

    // ── MatcherRoadWitness — the production adapter ───────────────────────

    /** One north-south avenue at e=0, long enough to ride for a while. */
    private fun avenue(): RoadGraph = RoadGraph(
        listOf(
            RoadWay(
                id = 1L,
                name = "Проспект",
                points = listOf(
                    northOf(0.0) to eastOf(0.0),
                    northOf(200.0) to eastOf(0.0),
                    northOf(400.0) to eastOf(0.0),
                ),
            ),
        ),
    )

    @Test
    fun `the adapter's chain grows on connected matches and resets off-network`() {
        val witness = MatcherRoadWitness(graph = { avenue() })

        var t = 0L
        for (i in 1..3) {
            val testimony = witness.testify(northOf(i * 11.0), eastOf(3.0), 5f, 0f, t)
            checkNotNull(testimony)
            assertTrue("fix $i rides the avenue", testimony.onRoad)
            assertEquals("chain must count consecutive matches", i, testimony.chainLength)
            t += 1_000
        }

        // A courtyard fix, 60 m off the only road: the story breaks.
        val off = witness.testify(northOf(44.0), eastOf(60.0), 5f, 0f, t)
        checkNotNull(off)
        assertEquals(false, off.onRoad)
        assertEquals(0, off.chainLength)

        // Back on the road: the chain starts over, it does not resume.
        val resumed = witness.testify(northOf(55.0), eastOf(3.0), 5f, 0f, t + 1_000)
        checkNotNull(resumed)
        assertEquals(1, resumed.chainLength)
    }

    @Test
    fun `no graph, no testimony`() {
        val witness = MatcherRoadWitness(graph = { null })
        assertNull(witness.testify(lat0, lon0, 5f, 0f, 0L))
    }
}
