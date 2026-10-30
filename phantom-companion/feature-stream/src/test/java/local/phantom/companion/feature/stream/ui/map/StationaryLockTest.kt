package local.phantom.companion.feature.stream.ui.map

import local.phantom.companion.core.ai.context.FixGrade
import local.phantom.companion.core.ai.context.LocationFix
import local.phantom.companion.core.nav.haversineM
import local.phantom.companion.core.sensor.nav.FixFlux
import local.phantom.companion.feature.stream.ui.map.nav.CameraDirector
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ARCH: PRECISION PIVOT (owner, 2026-07-21 — «головне щоб точка не ковзала і
 * була неймовірно точною, напрям теж»).
 *
 * The pivot took the EW dead-reckoner out of the position path; the displayed
 * position is now the raw system-fused fix, snapped when moving, and — the
 * subject of this suite — FROZEN when parked by [StationaryLock].
 *
 * «Точка ковзає» is the thing the owner can see, and a screenshot is not proof:
 * he caught an earlier "healthy" claim with a live capture ten minutes later.
 * So these tests are a TIME SERIES. The camera cannot be looked at either, so it
 * is measured: parked input runs the REAL chain the device runs — the lock, then
 * [FixFlux]'s spring, then [CameraDirector]'s envelope — and we assert the two
 * properties the eye reports on: the puck holds position, and the map does not
 * rotate. No production class is mocked; only the fix stream is synthetic.
 */
class StationaryLockTest {

    private val frameMs = 33L // the canvas loop's ~30 fps

    private fun fix(
        lat: Double,
        lon: Double,
        speed: Float,
        bearing: Float? = null,
        acc: Float = 18f,
    ) = LocationFix(
        lat = lat, lon = lon, bearing = bearing, accuracyM = acc,
        speedMps = speed, grade = FixGrade.NAVIGATION,
    )

    private fun distM(a: LocationFix, b: LocationFix): Double =
        haversineM(a.lat to a.lon, b.lat to b.lon)

    // ── 1. The whole point: parked 5 min, the puck does not slide ────────────

    @Test
    fun `parked five minutes — the display fix freezes and never wanders again`() {
        val lock = StationaryLock()
        val rnd = java.util.Random(42)
        val lat0 = 50.4501
        val lon0 = 30.5234
        var t = 1_000L

        var frozenAtLat = Double.NaN
        var frozenAtLon = Double.NaN
        var maxPostFreezeWanderM = 0.0
        var frozenTicks = 0
        var everFrozen = false
        val total = 300 // five minutes at 1 Hz

        repeat(total) {
            // A parked, indoor raw fused fix: a few metres of multipath scatter
            // and speed noise that stays under a slow walk. No bearing — a
            // standing chip reports none.
            val raw = fix(
                lat = lat0 + rnd.nextGaussian() * 0.00004, // ~4–5 m σ
                lon = lon0 + rnd.nextGaussian() * 0.00006,
                speed = abs(rnd.nextGaussian() * 0.2).toFloat(), // ≪ STILL_ENTER_MPS
                bearing = null,
            )
            val out = lock.step(raw, t)
            if (lock.isFrozen) {
                frozenTicks++
                if (!everFrozen) {
                    everFrozen = true
                    frozenAtLat = out.lat
                    frozenAtLon = out.lon
                }
                val wander = haversineM(frozenAtLat to frozenAtLon, out.lat to out.lon)
                maxPostFreezeWanderM = maxOf(maxPostFreezeWanderM, wander)
                // A frozen fix carries zero speed — nothing downstream may read
                // it as motion.
                assertEquals("a frozen fix must report zero speed", 0f, out.speedMps)
            }
            t += 1_000L
        }

        println(
            "PARKED-5MIN: frozen $frozenTicks/$total ticks | " +
                "maxWanderAfterFreeze=${"%.3f".format(maxPostFreezeWanderM)}m",
        )

        assertTrue("a sustained-still phone must freeze", everFrozen)
        // Sustain is 2.5 s; the rest of five minutes is frozen.
        assertTrue("the vast majority of a parked run must be frozen", frozenTicks >= total - 4)
        assertEquals(
            "once frozen, the displayed position must not move a millimetre",
            0.0, maxPostFreezeWanderM, 1e-6,
        )
    }

    // ── 2. …and the camera parked on that fix does not slide or rotate ───────

    @Test
    fun `parked — the follow camera holds still and does not rotate`() {
        val lock = StationaryLock()
        val flux = FixFlux()
        val director = CameraDirector()
        val rnd = java.util.Random(7)
        val lat0 = 50.4501
        val lon0 = 30.5234
        var now = 1_000L
        var seeded = false

        val poses = mutableListOf<CameraDirector.Directive>()
        repeat(120) { // two minutes of 1 Hz fixes
            val raw = fix(
                lat = lat0 + rnd.nextGaussian() * 0.00004,
                lon = lon0 + rnd.nextGaussian() * 0.00006,
                speed = abs(rnd.nextGaussian() * 0.2).toFloat(),
                bearing = null,
            )
            val display = lock.step(raw, now)
            flux.onTarget(
                lat = display.lat, lon = display.lon,
                speedMps = display.speedMps ?: 0f,
                bearingDeg = display.bearing,
                uncertaintyM = display.accuracyM ?: 0f,
                atMs = now, trusted = true,
            )
            repeat(1000 / frameMs.toInt()) {
                now += frameMs
                val eased = flux.step(now) ?: return@repeat
                // The canvas freezes course below walking speed; mirror it.
                val moving = (display.speedMps ?: 0f) >= HEADING_FLOOR_MPS
                if (!seeded) {
                    director.seed(
                        lat = eased.lat, lon = eased.lon, zoom = 16.0,
                        tiltDeg = 0.0, bearingDeg = 0.0,
                        speedMps = display.speedMps ?: 0f,
                    )
                    seeded = true
                }
                poses += director.step(
                    nowMs = now, lat = eased.lat, lon = eased.lon,
                    speedMps = display.speedMps ?: 0f,
                    courseBearingDeg = if (moving) eased.bearingDeg else null,
                )
            }
        }

        // Drop the settle-in transient; measure the steady state.
        val tail = poses.takeLast(600)
        val maxStepM = tail.zipWithNext { a, b ->
            haversineM(a.targetLat to a.targetLon, b.targetLat to b.targetLon)
        }.maxOrNull() ?: 0.0
        val maxBearingStepDeg = tail.zipWithNext { a, b ->
            abs(((b.bearingDeg - a.bearingDeg + 540.0) % 360.0) - 180.0)
        }.maxOrNull() ?: 0.0

        println(
            "PARKED-CAMERA: maxTargetStep=${"%.4f".format(maxStepM)}m/frame " +
                "maxBearingStep=${"%.4f".format(maxBearingStepDeg)}°/frame",
        )

        assertTrue("a parked camera must not slide (${maxStepM}m/frame)", maxStepM < 0.02)
        assertTrue(
            "a parked camera must not rotate (${maxBearingStepDeg}°/frame)",
            maxBearingStepDeg < 0.05,
        )
    }

    // ── 3. Drive → stop → depart: the lock arms on the stop, releases on travel

    @Test
    fun `drive then stop then depart — freezes at the stop and lets go into travel`() {
        val lock = StationaryLock()
        var t = 1_000L
        val lat0 = 50.4500
        val lon0 = 30.5200

        // Phase A — driving north at 12 m/s. Never frozen; the pin tracks.
        var lat = lat0
        var lastOut: LocationFix? = null
        repeat(20) {
            lat += 12.0 / 111_320.0
            val out = lock.step(fix(lat, lon0, speed = 12f, bearing = 0f), t)
            assertFalse("a moving vehicle must never freeze", lock.isFrozen)
            lastOut = out
            t += 1_000L
        }
        assertTrue("a driven pin tracks the body", distM(lastOut!!, fix(lat, lon0, 0f)) < 1.0)

        // Phase B — stop for 12 s. Freezes after the sustain window.
        val stopLat = lat
        repeat(12) {
            val out = lock.step(fix(stopLat, lon0, speed = 0.05f, bearing = null), t)
            if (lock.isFrozen) {
                assertEquals("held at the stop point", 0.0, distM(out, fix(stopLat, lon0, 0f)), 0.5)
            }
            t += 1_000L
        }
        assertTrue("a sustained stop must freeze", lock.isFrozen)

        // Phase C — depart north at 6 m/s. The lock lets go and the pin tracks.
        var released = false
        var departLat = stopLat
        repeat(8) {
            departLat += 6.0 / 111_320.0
            lock.step(fix(departLat, lon0, speed = 6f, bearing = 0f), t)
            if (!lock.isFrozen) released = true
            t += 1_000L
        }
        assertTrue("departure must release the freeze", released)
        assertFalse("a departed pin must not still be frozen", lock.isFrozen)
    }

    // ── 4. Heading gate — no rotation from low-speed noise ───────────────────

    @Test
    fun `heading is held below walking pace and flows above it`() {
        val lock = StationaryLock()
        var t = 1_000L
        // Establish a travel heading at speed.
        lock.step(fix(50.45, 30.52, speed = 8f, bearing = 90f), t); t += 1_000L
        // Now crawl below the walking floor while the raw bearing swings wildly
        // (compass noise at a standstill). The output heading must not follow it.
        val held = mutableListOf<Float?>()
        repeat(10) { i ->
            val noisyBearing = (i * 47f) % 360f
            val out = lock.step(fix(50.45, 30.52, speed = 0.3f, bearing = noisyBearing), t)
            held += out.bearing
            t += 1_000L
        }
        // Every low-speed output kept the last travel heading (~90°), never the
        // swinging raw bearing.
        held.forEach { b ->
            assertTrue("low-speed heading must stay ~90°, was $b", b != null && abs(b - 90f) < 1f)
        }
        // Back above the floor, a fresh travel bearing flows through.
        val moving = lock.step(fix(50.4502, 30.52, speed = 6f, bearing = 180f), t)
        assertEquals("above the floor the fix's own bearing flows", 180f, moving.bearing)
    }

    // ── 5. Robustness — one noisy sample never releases the freeze ───────────

    @Test
    fun `a single noisy speed spike does not release the freeze`() {
        val lock = StationaryLock()
        var t = 1_000L
        val lat = 50.45
        val lon = 30.52
        // Freeze first.
        repeat(6) { lock.step(fix(lat, lon, speed = 0.1f), t); t += 1_000L }
        assertTrue(lock.isFrozen)
        // One isolated spike: speed reads 2.0 m/s for a single tick, but the
        // body has not actually moved (same position). Must stay frozen.
        lock.step(fix(lat, lon, speed = 2.0f), t); t += 1_000L
        assertTrue("a lone speed spike with no displacement must not release", lock.isFrozen)
        // …and settling back to still keeps it frozen.
        lock.step(fix(lat, lon, speed = 0.1f), t)
        assertTrue(lock.isFrozen)
    }

    // ── 6. The device finding — multipath drift at zero speed never releases ─

    /**
     * DEVICE CAPTURE (A56, parked indoors 5.5 min, 2026-07-21): the raw fused fix
     * drifted >40 m from the anchor while the chip reported spd=0.0, and an earlier
     * displacement-only safety net released the freeze — the puck slid for 14 s.
     * This is the exact «точка ковзає» the lock exists to prevent. Speed (Doppler)
     * reads a clean 0.0 while parked, so it is the only trustworthy release signal;
     * displacement, on this hardware, is not. A parked phone must stay frozen no
     * matter how far multipath throws the raw fix.
     */
    @Test
    fun `a parked phone whose fix drifts far on multipath never releases`() {
        val lock = StationaryLock()
        var t = 1_000L
        val lat0 = 50.4501
        val lon0 = 30.5234
        // Freeze first, standing still.
        repeat(6) { lock.step(fix(lat0, lon0, speed = 0.0f), t); t += 1_000L }
        assertTrue("a sustained-still phone must freeze", lock.isFrozen)

        // Now the raw fix wanders far and STAYS far for a full minute — a sustained
        // >50 m multipath excursion — but the chip still reports a standstill.
        var maxHeldWanderM = 0.0
        repeat(60) {
            // ~55 m north-east of the anchor, jittering, speed pinned at 0.
            val drift = fix(
                lat = lat0 + 0.00045 + (it % 3) * 0.00002,
                lon = lon0 + 0.00060,
                speed = 0.0f,
            )
            val out = lock.step(drift, t)
            assertTrue("multipath drift at zero speed must NOT release", lock.isFrozen)
            // The DISPLAYED fix stays pinned to the anchor, not the drifted raw.
            val heldWander = haversineM(lat0 to lon0, out.lat to out.lon)
            maxHeldWanderM = maxOf(maxHeldWanderM, heldWander)
            t += 1_000L
        }
        println("PARKED-MULTIPATH: held ${"%.3f".format(maxHeldWanderM)}m off anchor over 60 drifted fixes")
        assertEquals(
            "the displayed puck must not follow multipath drift a single metre",
            0.0, maxHeldWanderM, 1e-6,
        )
    }
}

/**
 * ПОЛЬОВИЙ ЗВІТ (власник, траса, 2 серпня 2026): «точку показувало, що застила,
 * а я їхав по трасі».
 *
 * Причина була в одному символі: `speedMps ?: 0f`. Відсутня швидкість ставала
 * твердим нулем, замерзання спрацьовувало, а звільнення вимагало швидкості —
 * якої вже ніколи не буде. Тести тримають нову межу і, головне, стару обіцянку:
 * припаркований телефон віддає чистий 0.0, а не null, тож він і далі не ковзає.
 */
class StationaryLockBlindSpeedTest {

    private fun fix(
        lat: Double,
        lon: Double,
        speed: Float?,
    ) = LocationFix(
        lat = lat, lon = lon, bearing = null, accuracyM = 18f,
        speedMps = speed, grade = FixGrade.NAVIGATION,
    )

    /** ~1 м широти = 9e-6 градуса; рахуємо приблизно, точності досить. */
    private fun northOf(lat: Double, metres: Double) = lat + metres * 9.0e-6

    @Test
    fun `без швидкості замок не спрацьовує взагалі`() {
        val lock = StationaryLock()
        var t = 0L
        repeat(600) {
            val out = lock.step(fix(50.45, 30.52, null), t)
            assertFalse("замерз на браку даних, а не на доказі стоянки", lock.isFrozen)
            assertEquals(50.45, out.lat, 1e-9)
            t += 100
        }
    }

    @Test
    fun `замерзлий стан відпускає по зміщенню, коли Doppler зник`() {
        val lock = StationaryLock()
        var t = 0L
        // Спершу чесно паркуємось: швидкість Є і вона нульова.
        repeat(40) { lock.step(fix(50.45, 30.52, 0f), t); t += 100 }
        assertTrue("не замерз там, де мав", lock.isFrozen)

        // Провайдер перестав давати швидкість, і ми поїхали.
        var out = lock.step(fix(northOf(50.45, 20.0), 30.52, null), t)
        assertTrue("20 м — ще в межах розкиду стоянки", lock.isFrozen)
        assertEquals(50.45, out.lat, 1e-9)

        t += 1000
        out = lock.step(fix(northOf(50.45, 120.0), 30.52, null), t)
        assertFalse("120 м без Doppler — це поїздка, не розкид", lock.isFrozen)
        assertEquals(northOf(50.45, 120.0), out.lat, 1e-9)
    }

    @Test
    fun `нуль від Doppler і далі морозить — стара обіцянка ціла`() {
        val lock = StationaryLock()
        var t = 0L
        repeat(40) { lock.step(fix(50.45, 30.52, 0f), t); t += 100 }
        assertTrue(lock.isFrozen)

        // Кімнатне багатопроменне зміщення на 60 м при чесному нулі швидкості.
        repeat(30) {
            val out = lock.step(fix(northOf(50.45, 60.0), 30.52, 0f), t)
            assertTrue("розкид у приміщенні не має звільняти замок", lock.isFrozen)
            assertEquals(50.45, out.lat, 1e-9)
            t += 100
        }
    }

    @Test
    fun `пропуск швидкості посеред руху не накопичує стоянку`() {
        val lock = StationaryLock()
        var t = 0L
        repeat(60) {
            // Кожен третій фікс без швидкості — типова поведінка провайдера.
            val s: Float? = if (it % 3 == 0) null else 22f
            lock.step(fix(northOf(50.45, it * 20.0), 30.52, s), t)
            assertFalse(lock.isFrozen)
            t += 900
        }
    }
}

/**
 * ПОЛЬОВА ПОМИЛКА, 23.08 — the lock released on the fix's own Doppler speed, and
 * a spoofed fix carries speed like any other: 6,5 m/s onto a phone lying on a
 * desk. The body now has a veto, and the veto only counts when the IMU is
 * CONFIDENT — «no reading» must leave every old rule exactly where it was, or a
 * phone with no IMU lease gets frozen shut.
 */
class StationaryLockBodyVetoTest {

    private fun fix(lat: Double, lon: Double, speed: Float?) = LocationFix(
        lat = lat, lon = lon, bearing = null, accuracyM = 18f,
        speedMps = speed, grade = FixGrade.NAVIGATION,
    )

    private fun northOf(lat: Double, metres: Double) = lat + metres * 9.0e-6

    private fun park(lock: StationaryLock, from: Long = 0L): Long {
        var t = from
        repeat(40) { lock.step(fix(50.45, 30.52, 0f), t); t += 100 }
        assertTrue("не замерз там, де мав", lock.isFrozen)
        return t
    }

    @Test
    fun `спуфнута швидкість не звільняє замок, поки тіло стоїть`() {
        val lock = StationaryLock(imuMoving = { false })
        var t = park(lock)

        // Рівно та підміна: швидкість росте 1,3 → 9,1 м/с, точка «їде» на схід.
        var travelled = 0.0
        listOf(1.3f, 3.9f, 6.5f, 6.8f, 9.1f, 9.3f).forEach { speed ->
            repeat(8) {
                travelled += speed
                val out = lock.step(fix(northOf(50.45, travelled), 30.52, speed), t)
                assertTrue("тіло стоїть — замок мусить триматись", lock.isFrozen)
                assertEquals("точка не має зрушити ні на метр", 50.45, out.lat, 1e-9)
                assertEquals(0f, out.speedMps)
                t += 1000
            }
        }
        println("SPOOF-VETO: ${travelled.toInt()} m of claimed travel, 0 m displayed")
    }

    @Test
    fun `справжній виїзд звільняє замок, бо тіло теж рушило`() {
        val lock = StationaryLock(imuMoving = { true })
        var t = park(lock)

        lock.step(fix(northOf(50.45, 1.0), 30.52, 2.0f), t)
        t += 600
        lock.step(fix(northOf(50.45, 3.0), 30.52, 2.0f), t)
        assertFalse("рух є і в небі, і в тілі — тримати нема підстав", lock.isFrozen)
    }

    @Test
    fun `без свідчень від IMU поведінка лишається доспуфною`() {
        val withNoOpinion = StationaryLock(imuMoving = { null })
        val withNoWitness = StationaryLock()
        val t = park(withNoOpinion)
        park(withNoWitness)

        listOf(withNoOpinion, withNoWitness).forEach { lock ->
            var tt = t
            lock.step(fix(northOf(50.45, 1.0), 30.52, 2.0f), tt)
            tt += 600
            lock.step(fix(northOf(50.45, 3.0), 30.52, 2.0f), tt)
            assertFalse("немає доказу — немає й вето", lock.isFrozen)
        }
    }
}
