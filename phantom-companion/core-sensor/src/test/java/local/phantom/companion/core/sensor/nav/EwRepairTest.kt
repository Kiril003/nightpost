package local.phantom.companion.core.sensor.nav

import local.phantom.companion.core.sensor.GpsSample
import local.phantom.companion.core.sensor.ImuSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHANTOM-EW — the repairs of 2026-07-14, pinned.
 *
 * These are the tests that would have caught F1 and F2 in the first place, and
 * the one that catches the trap the F1 repair opens if it is done naively. If
 * any of them ever goes red, the engine has regressed to shipping a confident
 * dot in the wrong street.
 */
class EwRepairTest {

    private val lat0 = 50.4501
    private val lon0 = 30.5234

    private fun northOf(m: Double) = lat0 + Math.toDegrees(m / 6_371_000.0)

    private fun fix(northM: Double, speed: Float = 11f, acc: Float = 5f) = GpsSample(
        lat = northOf(northM),
        lon = lon0,
        accuracyM = acc,
        speedMps = speed,
        bearingDeg = 0f,
    )

    /** Driving due north, phone flat and aligned. */
    private fun imu(alongAccel: Float = 0f) = ImuSample(
        accelX = 0f, accelY = alongAccel, accelZ = 9.81f,
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
     * **F2** — the operator is cruising, not stopped. The reckoner must not
     * decide otherwise just because a steady speed produces no acceleration.
     *
     * Before the repair: 30 m/s decayed to 1.10 m/s in ten seconds, and dead
     * reckoning "drifted" at exactly the speed of the car — because the
     * estimate was standing still.
     */
    @Test
    fun `F2 - a cruising operator is not reckoned to a standstill`() {
        val dr = DeadReckoner()
        dr.seed(speedMps = 30f, bearingDeg = 0f, nowMs = 0L)

        var t = 0L
        repeat(500) { // 10 s at 50 Hz, steady cruise
            t += 20
            dr.onImu(imu(), t)
        }

        assertTrue(
            "a cruising operator was reckoned down to ${dr.speed} m/s (truth 30)",
            dr.speed > 25f,
        )
    }

    /**
     * **F2b** — but a real stop must still stop. Braking is large and
     * unmistakable, and it is what actually announces a halt.
     */
    @Test
    fun `F2 - braking still brings the reckoning to rest`() {
        val dr = DeadReckoner()
        dr.seed(speedMps = 20f, bearingDeg = 0f, nowMs = 0L)

        var t = 0L
        repeat(400) { // 8 s of firm braking at −3 m/s²
            t += 20
            dr.onImu(imu(alongAccel = -3f), t)
        }

        assertEquals("braking must reach a standstill", 0f, dr.speed, 0.5f)
    }

    /**
     * **F1** — a tunnel must not lock the engine out of the sky forever.
     *
     * Before the repair, the first honest fix after a 120 s tunnel was judged
     * "a teleport" against a 120-second-stale reference, and since a refused fix
     * never becomes the new reference, every fix after it was refused too. 60 of
     * 60 genuine fixes rejected, permanently.
     */
    @Test
    fun `F1 - the sky is re-acquired after a long tunnel`() {
        val engine = CustomLocationEngine()
        var t = 1_000L

        // 30 s of honest driving north at 11 m/s.
        repeat(30) { i ->
            engine.onGps(fix(11.0 * i), t)
            t = coast(engine, t, 1_000)
        }
        assertEquals(LocationTrust.TRUSTED, engine.trust.value)
        val beforeTunnel = 11.0 * 29

        // A 120 s tunnel. The operator keeps driving; no fixes at all.
        t = coast(engine, t, 120_000)
        assertEquals("a tunnel is a blackout", LocationTrust.JAMMED, engine.trust.value)

        // The sky returns, telling the truth: he is 1.3 km further north.
        val travelled = 11.0 * 120
        val accepted0 = engine.acceptedFixes
        repeat(20) { i ->
            engine.onGps(fix(beforeTunnel + travelled + 11.0 * i), t)
            t = coast(engine, t, 1_000)
        }

        assertTrue(
            "the real sky was refused after the tunnel — F1 has regressed",
            engine.acceptedFixes > accepted0,
        )
        assertEquals(
            "the engine must return to trusting a sky that is telling the truth",
            LocationTrust.TRUSTED,
            engine.trust.value,
        )
    }

    /**
     * **Польова помилка, 16.08 17:54:10** — обвинувачення, яке спростовують його
     * ж числа.
     *
     * Телефон лежав нерухомо на столі. Небо помовчало, повернулося з власною
     * похибкою **±699 м** і лягло за **356 м** від інерційного сліду. Двигун
     * порівняв 356 м із жорстким допуском 200 м і написав на весь екран
     * «ПІДМІНА КООРДИНАТ — супутники чутно, але не вірю».
     *
     * 356 м — це пів сигми тієї самої похибки, яку небо саме про себе заявило.
     * Суперечності не було: був фікс, який чесно сказав, що знає своє місце
     * погано, і слід, який із цим не сперечався. Хибне звинувачення тут коштує
     * дорожче за пропущене: оператор, якого двічі даремно налякали підміною,
     * перестає читати єдине попередження, якого не дає жоден цивільний
     * навігатор.
     */
    @Test
    fun `a returning fix inside its OWN admitted error is not a spoof`() {
        val engine = CustomLocationEngine()
        var t = 1_000L

        // Стоїть на місці з добрим небом.
        repeat(20) {
            engine.onGps(fix(0.0, speed = 0f, acc = 5f), t)
            t = coast(engine, t, 1_000)
        }
        assertEquals(LocationTrust.TRUSTED, engine.trust.value)

        // Небо мовчить — так само нерухомо, тож інерція нікуди не пішла.
        t = coast(engine, t, 30_000)

        // І повертається: за 356 м, із зізнанням «моя похибка ±699 м».
        engine.onGps(fix(356.0, speed = 0f, acc = 699f), t)

        assertTrue(
            "фікс у межах ВЛАСНОЇ заявленої похибки названо підміною — " +
                "допуск знову міряє лише інерцію: ${engine.lastRejection.value}",
            engine.trust.value != LocationTrust.SPOOFED,
        )
    }

    /**
     * **Стенд, що лежить нерухомо, не є нападом — навіть коли небо бреше про точність.**
     *
     * Емулятор (і дешевий приймач) віддає `accuracy = 0` — фікс, що заявляє
     * ДОСКОНАЛУ точність. Допуск воріт множиться саме на цю заявлену похибку
     * (`REACQUIRE_ACCURACY_SCALE * min(acc, cap)`), тож при нулі він вироджується
     * в саму інерційну частину: будь-яке тремтіння останнього знака координати
     * читалось би як суперечність. Оператор при цьому просто поклав телефон.
     *
     * Хибне звинувачення тут коштує дорожче за пропущене (див. сусідній тест):
     * пігулка «підміна координат» на столі — це та сама втрачена довіра до
     * єдиного попередження, якого не дає жоден цивільний навігатор.
     */
    @Test
    fun `a still stand whose sky claims perfect accuracy is never accused`() {
        val engine = CustomLocationEngine()
        var t = 1_000L
        // Пʼять хвилин на столі: те саме місце, «±0 м», тремтіння останнього знака.
        repeat(300) { i ->
            val jitterM = if (i % 2 == 0) 0.0 else 0.35
            engine.onGps(fix(jitterM, speed = 0f, acc = 0f), t)
            t = coast(engine, t, 1_000)
            assertTrue(
                "нерухомий стенд названо підміною на кроці $i: ${engine.lastRejection.value}",
                engine.trust.value != LocationTrust.SPOOFED,
            )
        }
        // І після паузи без неба той самий стіл лишається столом.
        t = coast(engine, t, 30_000)
        engine.onGps(fix(0.0, speed = 0f, acc = 0f), t)
        assertTrue(
            "повернення на те саме місце названо підміною: ${engine.lastRejection.value}",
            engine.trust.value != LocationTrust.SPOOFED,
        )
    }

    /**
     * Зворотний бік тієї самої правки, і без нього вона була б просто
     * послабленням: ворота розширює РІВНО стільки, скільки небо визнало за
     * собою саме. Той самий стрибок від фікса, що заявляє ±5 м, — це
     * суперечність, і вона мусить лишитися суперечністю.
     */
    @Test
    fun `the same jump from a fix that claims to be precise is still refused`() {
        val engine = CustomLocationEngine()
        var t = 1_000L

        repeat(20) {
            engine.onGps(fix(0.0, speed = 0f, acc = 5f), t)
            t = coast(engine, t, 1_000)
        }
        t = coast(engine, t, 30_000)

        engine.onGps(fix(356.0, speed = 0f, acc = 5f), t)

        assertEquals(
            "точний фікс за 356 м від нерухомого сліду більше нікого не турбує",
            LocationTrust.SPOOFED,
            engine.trust.value,
        )
    }

    /**
     * **The trap.** F1's naive repair — "after a long gap, just believe the sky
     * again" — hands an adversary the standard chain: jam until the gate gives
     * up asking questions, then present a false origin.
     *
     * The engine must refuse a re-acquisition that its own inertial track flatly
     * contradicts. This is the test that makes the F1 fix defensible; without it
     * the repair is a vulnerability with good intentions.
     */
    @Test
    fun `F1 - jam-then-spoof is refused, not welcomed back`() {
        val engine = CustomLocationEngine()
        var t = 1_000L

        repeat(30) { i ->
            engine.onGps(fix(11.0 * i), t)
            t = coast(engine, t, 1_000)
        }
        assertEquals(LocationTrust.TRUSTED, engine.trust.value)

        // Jam for one minute — long enough to hurt, short enough that the
        // inertial witness still has STANDING (its own admitted error stays
        // inside WITNESS_STANDING_M; past that, the standing rule takes over
        // and is pinned by its own test below).
        t = coast(engine, t, 60_000)
        assertEquals(LocationTrust.JAMMED, engine.trust.value)

        // ...then the "sky" comes back claiming he is 8 km away. He is not: the
        // accelerometer in his pocket has been running the whole time, and it
        // says he drove ~660 m north.
        val refusedBefore = engine.refusedFixes
        repeat(10) {
            engine.onGps(fix(8_000.0), t)
            t = coast(engine, t, 1_000)
        }

        assertEquals(
            "the engine walked into a jam-then-spoof — the F1 repair is a hole",
            LocationTrust.SPOOFED,
            engine.trust.value,
        )
        assertTrue(
            "a spoofed re-acquisition must be refused, loudly",
            engine.refusedFixes > refusedBefore && engine.lastRejection.value != null,
        )

        // Ride out the rest of the grace window. On the fix that exhausts it the
        // engine CONCEDES — an inertial track that has itself drifted for two
        // minutes cannot go on testifying forever, and holding out would strand
        // the operator permanently (F1 in a third costume).
        //
        // But it must concede to DEGRADED, never straight to TRUSTED, and it
        // must say why. Being navigated by a sky your own body disagrees with is
        // survivable; not being TOLD is not.
        repeat(CustomLocationEngine.REACQUIRE_GRACE_FIXES - 9) {
            engine.onGps(fix(8_000.0), t)
            t = coast(engine, t, 1_000)
        }

        assertEquals(
            "conceding must land on DEGRADED, never on TRUSTED",
            LocationTrust.DEGRADED,
            engine.trust.value,
        )
        assertTrue(
            "the concession must be spoken aloud, not made in silence",
            engine.lastRejection.value?.contains("інерц") == true,
        )

        // After conceding, the DriftAudit still watches the sky against the
        // body — but by the owner's 2026-07 recalibration it may no longer
        // SEIZE a GOOD sky on an inertial disagreement. That exact seizure,
        // on a never-jammed operator whose MEMS witness had misfired, was the
        // field failure («мітку тягне куди хоче»); the engine cannot tell its
        // own body's error from the sky's lie, so on a good sky it now follows
        // the satellites, says its doubt out loud, and does NOT dead-reckon.
        //
        // Re-catching a self-consistent post-jam capture (the adversary MIMICS
        // the operator's motion behind a fixed offset — invisible to inertia by
        // construction, as this block always noted) moves to the third authority
        // the design always named: map-matching, "is he on a road that exists?"
        // — the Layer 6 road-witness / OSRM `/match`, dormant until the Re-Drive
        // QA. See docs/EW_DRIFT_MATRIX.md. What this proves now: after conceding
        // the engine keeps the operator ON the satellites and keeps SPEAKING its
        // doubt, rather than seizing a sky it cannot prove is lying.
        var warnedAfterConcession = false
        repeat(90) {
            engine.onGps(fix(8_000.0), t)
            if (engine.lastRejection.value?.contains("інерц") == true) {
                warnedAfterConcession = true
            }
            t = coast(engine, t, 1_000)
        }
        assertTrue(
            "after conceding to a good sky the engine must keep watching aloud, " +
                "not fall silent",
            warnedAfterConcession,
        )
        assertTrue(
            "a conceded good sky is followed, never dead-reckoned",
            engine.fix.value?.dead == false,
        )
        assertTrue(
            "a good sky must never be seized as SPOOFED — the field failure",
            engine.trust.value != LocationTrust.SPOOFED,
        )
    }

    /**
     * **The mount** — a phone in a cupholder is not a compass.
     *
     * The platform's rotation vector reports the DEVICE's yaw, not the course
     * over ground. The reckoner must propagate the course by the device's yaw
     * *delta* and never adopt its absolute yaw, or a phone rotated 90° from the
     * road steers dead reckoning 90° into a field.
     */
    @Test
    fun `the mount orientation does not steer the dead reckoning`() {
        // Phone yawed 90° from the direction of travel: rotW/rotZ = 45° half-angle.
        val half = Math.toRadians(90.0) / 2
        val rotated = ImuSample(
            accelX = 0f, accelY = 0f, accelZ = 9.81f,
            gyroX = 0f, gyroY = 0f, gyroZ = 0f,
            rotW = Math.cos(half).toFloat(), rotX = 0f, rotY = 0f,
            rotZ = Math.sin(half).toFloat(),
        )

        val dr = DeadReckoner()
        // GPS says: travelling due north (bearing 0) at 20 m/s.
        dr.seed(speedMps = 20f, bearingDeg = 0f, nowMs = 0L)

        var t = 0L
        repeat(250) {
            t += 20
            dr.onImu(rotated, t)
        }

        val heading = dr.heading!!
        assertTrue(
            "the phone's mount yaw leaked into the course (heading=$heading, expected ~0°)",
            heading < 5f || heading > 355f,
        )
    }

    /**
     * **F3 — the patient drag, and the owner's 2026-07 recalibration.**
     *
     * A plausible drag (5 m/s sideways, good accuracy) is invisible to physics:
     * every fix passes the gate. The inertial witness CAN see it — but only
     * while the witness itself is honest. In the field it is not: on a clean,
     * never-jammed sky the SAME DriftAudit DIVERGE fired on a ZUPT misfire or a
     * re-gripped phone and dead-reckoned the operator off his own road («мітку
     * тягне куди хоче»). The engine cannot tell its own body's error from the
     * sky's lie, and its false alarms were the harm.
     *
     * So by owner directive the trade is reset: on a GOOD sky the engine no
     * longer seizes the position on an inertial disagreement. It FOLLOWS the
     * satellites, says the disagreement out loud, and drops to a warning — dead
     * reckoning is reserved for PROVEN degradation (poor accuracy, a gate
     * violation, a jam dropout). Catching a drag that forges good accuracy moves
     * to the third authority the DriftAudit doc always named as the real answer:
     * map-matching / the OSRM `/match` road-witness (Layer 6, ATLAS §7).
     *
     * What must hold: the drag is FLAGGED (the operator is told), the puck
     * FOLLOWS the sky (fused, never dead-reckoned), and a good sky is never
     * seized as SPOOFED.
     */
    @Test
    fun `F3 - a plausible drag on a good sky is flagged but never dead-reckoned`() {
        val engine = CustomLocationEngine()
        var t = 1_000L

        // A minute of honest driving, so the audit has a calibrated baseline.
        repeat(60) { i ->
            engine.onGps(fix(11.0 * i), t)
            t = coast(engine, t, 1_000)
        }
        assertEquals(LocationTrust.TRUSTED, engine.trust.value)

        // Now the sky starts lying — gently. Position drifts sideways at 5 m/s,
        // which is entirely plausible physics and passes the gate every time.
        var north = 11.0 * 60
        var warned = false
        var everSpoofed = false
        var everDead = false
        repeat(120) { i ->
            north += 11.0
            val dragged = GpsSample(
                lat = northOf(north),
                lon = lon0 + Math.toDegrees(
                    (5.0 * i) / (6_371_000.0 * Math.cos(Math.toRadians(lat0))),
                ),
                accuracyM = 5f,
                speedMps = 11f,
                bearingDeg = 0f,
            )
            engine.onGps(dragged, t)
            if (engine.lastRejection.value?.contains("інерц") == true) warned = true
            if (engine.trust.value == LocationTrust.SPOOFED) everSpoofed = true
            if (engine.fix.value?.dead == true) everDead = true
            t = coast(engine, t, 1_000)
        }

        assertTrue("the inertial disagreement must be surfaced to the operator", warned)
        assertTrue("a good sky must never be seized as SPOOFED", !everSpoofed)
        assertTrue("a good sky must never be dead-reckoned — the field failure", !everDead)
        // The puck followed the satellites east with the drag, not a frozen
        // inertial track: a dead-reckoned engine would have held near lon0.
        val f = engine.fix.value!!
        assertTrue(
            "the fix must follow the sky east, not freeze inertially (lon=${f.lon})",
            f.lon > lon0 + 0.005,
        )
    }

    /** An honest drive must NOT trip the drift audit. A false alarm here would
     *  make the engine cry spoof on every clean commute. */
    @Test
    fun `an honest drive never trips the drift audit`() {
        val engine = CustomLocationEngine()
        var t = 1_000L
        repeat(240) { i ->
            engine.onGps(fix(11.0 * i), t)
            t = coast(engine, t, 1_000)
        }
        assertEquals(
            "the drift audit cried wolf on a clean four-minute drive",
            LocationTrust.TRUSTED,
            engine.trust.value,
        )
        assertEquals("a clean drive must never be refused", 0L, engine.refusedFixes)
    }

    /**
     * **KYIV FIELD FAILURE, 2026-07-16** — the witness-standing rule.
     *
     * Four hours of urban EW: each blackout dead-reckoned the track further
     * off (17 km by the end), and each returning HONEST sky was refused for
     * the full grace window because it disagreed with an inertial path that
     * was itself the most wrong thing in the system — 277 refusals, σ 346 km,
     * the operator navigated by a hallucination all day.
     *
     * Past [CustomLocationEngine.WITNESS_STANDING_M] of admitted inertial
     * error, the witness has no standing and the grace counter must not
     * apply: two returning fixes that corroborate EACH OTHER are accepted —
     * as DEGRADED, said out loud — on the second fix, not the twenty-first.
     */
    @Test
    fun `a witness without standing concedes to a self-consistent sky in two fixes`() {
        val engine = CustomLocationEngine()
        var t = 1_000L

        repeat(30) { i ->
            engine.onGps(fix(11.0 * i), t)
            t = coast(engine, t, 1_000)
        }
        assertEquals(LocationTrust.TRUSTED, engine.trust.value)
        val beforeTunnel = 11.0 * 29

        // Two minutes of blackout: the inertial error budget passes standing
        // (0.8 × ~1320 m + 200 m ≈ 1.3 km > WITNESS_STANDING_M).
        t = coast(engine, t, 120_000)
        assertEquals(LocationTrust.JAMMED, engine.trust.value)

        // The sky returns telling a truth far outside the inertial budget —
        // the drive turned in a way the blind reckoner never saw.
        val refusedBefore = engine.refusedFixes
        val acceptedBefore = engine.acceptedFixes
        engine.onGps(fix(beforeTunnel + 4_000.0), t)
        t = coast(engine, t, 1_000)
        engine.onGps(fix(beforeTunnel + 4_011.0), t)

        assertEquals(
            "exactly one refusal — the first fix is the candidate, the second corroborates it",
            refusedBefore + 1,
            engine.refusedFixes,
        )
        assertTrue(
            "the corroborated sky must be accepted on the second fix",
            engine.acceptedFixes > acceptedBefore,
        )
        assertEquals(
            "conceding past standing lands on DEGRADED, never TRUSTED",
            LocationTrust.DEGRADED,
            engine.trust.value,
        )
    }

    /**
     * **F2c — the parked glide.** A jam begins while the operator is moving;
     * he then stops and stays stopped. The reckoner, seeded with a speed and
     * fed no measurement, glided that speed for kilometres on the Kyiv drive.
     * Linear acceleration cannot tell cruise from rest — but VIBRATION can:
     * the ZUPT arms on the shaking of real movement and fires when it ceases.
     */
    @Test
    fun `F2c - when armed vibration ceases, the reckoning stops`() {
        val dr = DeadReckoner()
        dr.seed(speedMps = 15f, bearingDeg = 0f, nowMs = 0L)

        // 6 s of driving: LATERAL vibration (accelX), which arms the ZUPT
        // without touching the along-track integral.
        var t = 0L
        var shake = true
        repeat(300) {
            t += 20
            val s = if (shake) 1.5f else 0f
            shake = !shake
            dr.onImu(
                ImuSample(
                    accelX = s, accelY = 0f, accelZ = 9.81f,
                    gyroX = 0f, gyroY = 0f, gyroZ = 0f,
                    rotW = 1f, rotX = 0f, rotY = 0f, rotZ = 0f,
                ),
                t,
            )
        }
        assertTrue("vibration must not slow the reckoning", dr.speed > 13f)

        // The platform goes physically silent for 6 s — parked, not cruising.
        repeat(300) {
            t += 20
            dr.onImu(imu(), t)
        }
        assertEquals("a silent platform must be reckoned at rest", 0f, dr.speed, 0.01f)
    }

    /** And the arming requirement itself: quiet from birth proves nothing —
     *  an unarmed ZUPT zeroing a clean synthetic cruise would be F2 again. */
    @Test
    fun `F2c - a ZUPT that never armed never fires`() {
        val dr = DeadReckoner()
        dr.seed(speedMps = 30f, bearingDeg = 0f, nowMs = 0L)
        var t = 0L
        repeat(500) {
            t += 20
            dr.onImu(imu(), t)
        }
        assertTrue("an unarmed ZUPT stopped a clean cruise", dr.speed > 25f)
    }
}
