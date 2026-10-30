package local.phantom.companion.core.nav

import local.phantom.companion.core.net.map.RouteProfile
import local.phantom.companion.core.net.map.RouteStep
import local.phantom.companion.core.net.map.RouteVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NAV-2 — the guidance state machine over the same synthetic due-north
 * route as [GuidanceMathTest]: cue tiers fire exactly once, off-route
 * needs three consecutive bad fixes, arrival closes the session, and the
 * overspeed nudge waits out its hold and repeat windows.
 */
class GuidanceSessionTest {

    private val points = (0 until 20).map { (50.0 + it * 0.001) to 30.0 }
    private val stepMeters = haversineM(points[0], points[1]) // ~111.2

    private fun variant(profile: RouteProfile = RouteProfile.FOOT) = RouteVariant(
        points = points,
        distanceMeters = stepMeters * 19,
        durationSeconds = 1000.0,
        steps = listOf(
            RouteStep("depart", null, "", 0.0, 0.0, points[0].first, points[0].second),
            RouteStep("turn", "right", "Хрещатик", 0.0, 0.0, points[10].first, points[10].second),
            RouteStep("arrive", null, "", 0.0, 0.0, points[19].first, points[19].second),
        ),
        speedsMps = emptyList(),
        profile = profile,
    )

    private val baseMs = 1_000_000_000L

    @Test
    fun `far from the maneuver no cue is spoken`() {
        val session = GuidanceSession.of(variant()) { null }!!
        val u = session.onFix(points[2].first, points[2].second, 1.4f, baseMs)
        assertTrue(u.cues.isEmpty())
        assertFalse(u.arrived)
        assertFalse(u.requestReroute)
        assertEquals("Поверніть праворуч", maneuverActionUk(
            u.progress.maneuverType!!, u.progress.maneuverModifier,
        ))
    }

    @Test
    fun `approach tier speaks once with distance then the immediate tier speaks bare`() {
        val session = GuidanceSession.of(variant()) { null }!!
        // ~111 m before the turn — still outside the pedestrian's 65 m tier,
        // which is the point: 111 m is 85 s of walking to forget it in.
        assertTrue(session.onFix(50.009, 30.0, 1.4f, baseMs).cues.isEmpty())
        // ~56 m before — the approach tier, ~40 s out.
        val u1 = session.onFix(50.0095, 30.0, 1.4f, baseMs + 1_000)
        assertEquals(1, u1.cues.size)
        assertEquals("Через 60 метрів поверніть праворуч на Хрещатик", u1.cues[0].text)
        // A wider tier prepares, it does not preempt.
        assertEquals(NavCue.Urgency.SOON, u1.cues[0].urgency)
        assertEquals("step-1", u1.cues[0].key)
        // Same place again — already announced, silent.
        val u2 = session.onFix(50.0095, 30.0, 1.4f, baseMs + 2_000)
        assertTrue(u2.cues.isEmpty())
        // ~17 m before — the immediate tier, no distance prefix, and the
        // authority to preempt whatever is mid-utterance.
        val u3 = session.onFix(50.00985, 30.0, 1.4f, baseMs + 3_000)
        assertEquals(listOf("Поверніть праворуч на Хрещатик"), u3.cues.map { it.text })
        assertEquals(NavCue.Urgency.NOW, u3.cues[0].urgency)
    }

    @Test
    fun `a car route walked is paced for the walker`() {
        val session = GuidanceSession.of(variant(RouteProfile.CAR)) { null }!!
        // ~334 m before the turn: deep inside the car's 600 m tier, and
        // nowhere near the walker's. The kinematic verdict decides.
        val car = session.onFix(50.007, 30.0, 1.4f, baseMs)
        assertEquals(1, car.cues.size)

        val walked = GuidanceSession.of(variant(RouteProfile.CAR)) { null }!!
        val quiet = walked.onFix(50.007, 30.0, 1.4f, baseMs, gait = NavGait.ON_FOOT)
        assertTrue("a walker was warned 4 minutes out", quiet.cues.isEmpty())
        val spoken = walked.onFix(50.0095, 30.0, 1.4f, baseMs + 1_000, gait = NavGait.ON_FOOT)
        assertEquals("Через 60 метрів поверніть праворуч на Хрещатик", spoken.cues.single().text)
    }

    @Test
    fun `the eta a walker reads is a walking eta`() {
        val driven = GuidanceSession.of(variant(RouteProfile.CAR)) { null }!!
            .onFix(points[0].first, points[0].second, 20f, baseMs, gait = NavGait.DRIVING)
        assertEquals(1000.0, driven.progress.remainingDurationS, 5.0)

        val walked = GuidanceSession.of(variant(RouteProfile.CAR)) { null }!!
            .onFix(points[0].first, points[0].second, 1.3f, baseMs, gait = NavGait.ON_FOOT)
        // 2.1 km on foot is half an hour, whatever the car plan promised.
        assertTrue(
            "walker was promised ${walked.progress.remainingDurationS / 60} min",
            walked.progress.remainingDurationS > 1_500.0,
        )
    }

    @Test
    fun `stepping out of a car mid-route does not re-speak a spoken turn`() {
        val session = GuidanceSession.of(variant(RouteProfile.CAR)) { null }!!
        // The final car tier fires at speed…
        val fast = session.onFix(50.00847, 30.0, 20f, baseMs, gait = NavGait.DRIVING)
        assertEquals(NavCue.Urgency.NOW, fast.cues.single().urgency)
        // …and the same turn, now walked, says nothing more.
        val walked = session.onFix(50.0095, 30.0, 1.3f, baseMs + 60_000, gait = NavGait.ON_FOOT)
        assertTrue(walked.cues.isEmpty())
    }

    // ── ON-TIME VOICE (operator drive 2026-07-18) — the trigger leads the
    // synthesis pipeline, so the words CLEAR the speaker before the wheel. ──

    @Test
    fun `car tiers fire early enough to cover the tts pipeline`() {
        val session = GuidanceSession.of(variant(RouteProfile.CAR)) { null }!!
        // ~170 m before the maneuver at 72 km/h: the final tier's reach is
        // v × (8 s + pipeline 1.5 s) = 190 m — already crossed, spoken NOW,
        // and distance-free (the wheel's cue is an imperative, not a report).
        val u = session.onFix(50.00847, 30.0, 20f, baseMs)
        assertEquals(1, u.cues.size)
        assertEquals(NavCue.Urgency.NOW, u.cues[0].urgency)
        assertEquals("Поверніть праворуч на Хрещатик", u.cues[0].text)
    }

    @Test
    fun `a step becoming next emits its final phrase once for prewarming`() {
        val session = GuidanceSession.of(variant()) { null }!!
        // First fix: the turn is the next maneuver — its exact final words
        // are announced for the synthesis cache, once.
        val u1 = session.onFix(points[2].first, points[2].second, 1.4f, baseMs)
        assertEquals("Поверніть праворуч на Хрещатик", u1.prewarm)
        val u2 = session.onFix(points[3].first, points[3].second, 1.4f, baseMs + 1_000)
        assertEquals(null, u2.prewarm)
        // Past the turn, the arrival becomes next — one more hint.
        val u3 = session.onFix(points[12].first, points[12].second, 1.4f, baseMs + 2_000)
        assertEquals("Фініш попереду", u3.prewarm)
    }

    @Test
    fun `off-route needs three consecutive moving fixes`() {
        val session = GuidanceSession.of(variant(RouteProfile.CAR)) { null }!!
        // ~140 m east of the corridor — far beyond the 45 m CAR threshold.
        val offLon = 30.002
        val u1 = session.onFix(50.005, offLon, 20f, baseMs)
        assertTrue(u1.progress.offRoute)
        assertFalse(u1.requestReroute)
        val u2 = session.onFix(50.005, offLon, 20f, baseMs + 1_000)
        assertFalse(u2.requestReroute)
        val u3 = session.onFix(50.005, offLon, 20f, baseMs + 2_000)
        assertTrue(u3.requestReroute)
        // The spoken «перепрокладаю» belongs to the controller, at the act of
        // replanning — a session verdict is not an announcement.
        assertTrue(u3.cues.isEmpty())
        // Still off — reroute stays requested.
        val u4 = session.onFix(50.005, offLon, 20f, baseMs + 3_000)
        assertTrue(u4.requestReroute)
    }

    @Test
    fun `a 4 Hz burst of off-route fixes does not hair-trigger a reroute`() {
        val session = GuidanceSession.of(variant(RouteProfile.CAR)) { null }!!
        val offLon = 30.002 // ~140 m east — genuinely off the corridor
        // Nav-grade chips may deliver 4 Hz: three fixes arrive inside 500 ms.
        // The streak fills, but 0.5 s of departure is one corner of
        // multipath — the verdict needs sustained TIME as well.
        var verdicts = 0
        repeat(10) { i ->
            val u = session.onFix(50.005, offLon, 20f, baseMs + i * 250L)
            if (u.requestReroute) verdicts++
            if (i * 250 < 2_000) {
                assertFalse("reroute fired after only ${i * 250} ms", u.requestReroute)
            }
        }
        // ...and once the departure has lasted ≥2 s, the reroute is honest.
        assertTrue(verdicts > 0)
    }

    // ── A pedestrian crosses roads, cuts corners, swaps pavements ────────

    @Test
    fun `crossing the street is not leaving the route`() {
        val session = GuidanceSession.of(variant()) { null }!!
        // ~36 m east of the corridor — the far pavement of a wide street.
        // Under the old 35 m foot corridor this was a departure.
        repeat(30) { i ->
            val u = session.onFix(50.005, 30.0005, 1.3f, baseMs + i * 1_000L)
            assertFalse("a crossing read as off-route (fix $i)", u.progress.offRoute)
            assertFalse(u.requestReroute)
        }
    }

    @Test
    fun `a walker off the corridor is given longer to come back than a driver`() {
        val session = GuidanceSession.of(variant()) { null }!!
        val offLon = 30.002 // ~140 m east — genuinely off
        // Three seconds off would already have rerouted a car.
        repeat(4) { i ->
            val u = session.onFix(50.005, offLon, 1.3f, baseMs + i * 1_000L)
            assertTrue(u.progress.offRoute)
            assertFalse("a walker was rerouted after ${i}s", u.requestReroute)
        }
        // Committed: still off eight seconds later, the replan is honest.
        val fired = session.onFix(50.005, offLon, 1.3f, baseMs + 8_000)
        assertTrue(fired.requestReroute)
    }

    @Test
    fun `arrival on foot is called within a few paces, not half a block`() {
        val session = GuidanceSession.of(variant()) { null }!!
        // ~22 m short of the destination: a driver has arrived, a walker has
        // not — 22 m on foot is still a different doorway.
        val short = session.onFix(50.0188, 30.0, 1.3f, baseMs)
        assertFalse(short.arrived)
        // ~11 m short — now he is there.
        val there = session.onFix(50.0189, 30.0, 1.3f, baseMs + 1_000)
        assertTrue(there.arrived)
    }

    // ── QA FIELD FAILURE («стою вдома — перепрокладаю в циклі») ──────────

    @Test
    fun `a stationary operator never accumulates an off-route streak`() {
        val session = GuidanceSession.of(variant()) { null }!!
        val offLon = 30.002 // ~140 m east — would be off-route if moving
        repeat(30) { i ->
            val u = session.onFix(50.005, offLon, 0.0f, baseMs + i * 1_000L)
            assertFalse("a standing operator was rerouted (fix $i)", u.requestReroute)
        }
        // He starts walking: once the departure is both counted and sustained,
        // the reroute is honest.
        session.onFix(50.005, offLon, 1.4f, baseMs + 40_000)
        session.onFix(50.005, offLon, 1.4f, baseMs + 41_000)
        val moving = session.onFix(50.005, offLon, 1.4f, baseMs + 48_000)
        assertTrue(moving.requestReroute)
    }

    @Test
    fun `reported accuracy inflates the off-route corridor`() {
        val session = GuidanceSession.of(variant()) { null }!!
        // ~72 m east of the corridor: past the 35 m FOOT threshold, but the
        // fix admits ±80 m of doubt — scatter, not a departure.
        val u = session.onFix(50.005, 30.001, 1.4f, baseMs, accuracyM = 80f)
        assertFalse(u.progress.offRoute)
        assertFalse(u.requestReroute)
        // The same offset with a confident fix IS off-route.
        val sharp = GuidanceSession.of(variant()) { null }!!
        val v = sharp.onFix(50.005, 30.001, 1.4f, baseMs, accuracyM = 5f)
        assertTrue(v.progress.offRoute)
    }

    @Test
    fun `an out-and-back route does not teleport progress to the return leg`() {
        // North 10 points, then back south along a parallel street ~55 m east:
        // close enough that a global nearest-point match would jump to the
        // return leg mid-walk.
        val out = (0 until 10).map { (50.0 + it * 0.001) to 30.0 }
        val back = (0 until 10).map { (50.009 - it * 0.001) to 30.00077 }
        val pts = out + back
        val hairpin = RouteVariant(
            points = pts,
            distanceMeters = 2200.0,
            durationSeconds = 1500.0,
            steps = listOf(
                RouteStep("depart", null, "", 0.0, 0.0, pts[0].first, pts[0].second),
                RouteStep("uturn", null, "", 0.0, 0.0, pts[9].first, pts[9].second),
                RouteStep("arrive", null, "", 0.0, 0.0, pts[19].first, pts[19].second),
            ),
            speedsMps = emptyList(),
            profile = RouteProfile.FOOT,
        )
        val session = GuidanceSession.of(hairpin) { null }!!
        // Establish progress early on the outbound leg.
        val early = session.onFix(50.002, 30.0, 1.4f, baseMs)
        val earlyRemaining = early.progress.remainingDistanceM
        // A fix drifting ~28 m east — nearer the RETURN leg than the outbound
        // one for a global matcher. Progress must stay on the outbound leg.
        val drift = session.onFix(50.0025, 30.0004, 1.4f, baseMs + 1_000)
        assertTrue(
            "progress teleported to the return leg",
            drift.progress.remainingDistanceM > earlyRemaining - 200,
        )
        assertFalse(drift.progress.offRoute)
    }

    @Test
    fun `reaching the destination arrives and says so`() {
        val session = GuidanceSession.of(variant()) { null }!!
        val end = points.last()
        val u = session.onFix(end.first, end.second, 1.4f, baseMs)
        assertTrue(u.arrived)
        assertTrue(u.cues.any { it.text.startsWith("Ви на місці") })
    }

    @Test
    fun `sustained overspeed nudges once per window`() {
        val session = GuidanceSession.of(variant(RouteProfile.CAR)) { 50 }!!
        val at = points[2]
        // 72 km/h at limit 50 — over, but the hold window hasn't passed.
        val u1 = session.onFix(at.first, at.second, 20f, baseMs)
        assertTrue(u1.cues.none { it.text.startsWith("Обережно") })
        // 5 s later, still speeding → one nudge — a nudge, never a preempt.
        val u2 = session.onFix(at.first, at.second, 20f, baseMs + 5_000)
        assertEquals(1, u2.cues.count { it.text.startsWith("Обережно") })
        assertTrue(u2.cues.first { it.text.startsWith("Обережно") }.urgency == NavCue.Urgency.INFO)
        // 5 s more — inside the 30 s repeat window, silent.
        val u3 = session.onFix(at.first, at.second, 20f, baseMs + 10_000)
        assertTrue(u3.cues.none { it.text.startsWith("Обережно") })
        assertEquals(72, u3.progress.speedKmh)
        assertEquals(50, u3.progress.limitKmh)
    }

    @Test
    fun `limit is read from the corridor fraction`() {
        var asked = -1.0
        val session = GuidanceSession.of(variant()) { f -> asked = f; 30 }!!
        val u = session.onFix(50.0095, 30.0, 1.4f, baseMs)
        assertEquals(0.5, asked, 0.02)
        assertEquals(30, u.progress.limitKmh)
    }

    @Test
    fun `spoken distances are coarse and TTS-friendly`() {
        assertEquals("50 метрів", spokenDistanceUk(47))
        assertEquals("100 метрів", spokenDistanceUk(111))
        assertEquals("250 метрів", spokenDistanceUk(260))
        assertEquals("1 кілометр", spokenDistanceUk(1_010))
        assertEquals("1 кома 2 кілометра", spokenDistanceUk(1_230))
        assertEquals("2 кілометри", spokenDistanceUk(2_040))
    }

    @Test
    fun `maneuver phrases cover the OSRM vocabulary`() {
        assertEquals("Рушаймо", maneuverActionUk("depart", null))
        assertEquals("Фініш попереду", maneuverActionUk("arrive", null))
        assertEquals("На кільці праворуч", maneuverActionUk("roundabout", "right"))
        // With an exit number the direction word is dropped: a roundabout has
        // no «right», and saying one contradicted a glyph drawing the ring.
        assertEquals(
            "На кільці — другий зʼїзд",
            maneuverActionUk("roundabout", "right", exitNumber = 2),
        )
        assertEquals(
            "На кільці — третій зʼїзд",
            maneuverActionUk("rotary", "straight", exitNumber = 3),
        )
        // Власник, 16.08, під час маршруту: «а як це повернути прямо?». OSRM
        // ставить type=turn на кожному вузлі з вибором, і modifier=straight там
        // означає «з цього перехрестя — прямо». «Поверніть прямо» — команда,
        // якої не виконати; вона не описує дію, а суперечить їй.
        assertEquals("Далі прямо", maneuverActionUk("turn", "straight"))
        assertEquals("У кінці дороги — прямо", maneuverActionUk("end of road", "straight"))
        assertEquals("Поверніть ліворуч", maneuverActionUk("turn", "left"))
        assertEquals("Тримайтесь плавно ліворуч", maneuverActionUk("fork", "slight left"))
        assertEquals("Розверніться", maneuverActionUk("uturn", null))
        assertEquals("Продовжуйте прямо", maneuverActionUk("new name", null))
    }

    @Test
    fun `session refuses a variant without geometry`() {
        val bad = variant().copy(points = emptyList())
        assertEquals(null, GuidanceSession.of(bad) { null })
        assertNotNull(GuidanceSession.of(variant()) { null })
    }

    // ── CRITICAL FIELD FAILURE (Kyiv 2026-07-16) — trust-gated guidance ──
    // Four hours of EW: dead-reckoned estimates kilometres adrift satisfied
    // the 3-fix off-route streak trivially, and guidance rerouted the
    // operator away from correct routes every cooldown, all day.

    @Test
    fun `untrusted fixes far from the corridor hold progress and never reroute`() {
        val session = GuidanceSession.of(variant()) { null }!!
        // One believed fix establishes real progress.
        val believed = session.onFix(points[5].first, points[5].second, 1.4f, baseMs)
        assertFalse(believed.progress.offRoute)

        // A runaway estimate wanders 2 km east for twenty fixes.
        var t = baseMs
        repeat(20) { i ->
            t += 1_000
            val u = session.onFix(50.005, 30.03, 1.4f, t, trusted = false)
            assertFalse("an untrusted estimate requested a reroute (fix $i)", u.requestReroute)
            assertTrue("no cue may be spoken from a runaway estimate", u.cues.isEmpty())
            assertEquals(
                "progress must HOLD at the last believed position",
                believed.progress.remainingDistanceM,
                u.progress.remainingDistanceM,
                0.1,
            )
        }

        // Trust returns on the corridor: guidance resumes without a reroute.
        val back = session.onFix(points[6].first, points[6].second, 1.4f, t + 1_000)
        assertFalse(back.requestReroute)
        assertFalse(back.progress.offRoute)
    }

    @Test
    fun `untrusted fixes near the corridor still advance progress - dead reckoning working`() {
        val session = GuidanceSession.of(variant()) { null }!!
        val early = session.onFix(points[2].first, points[2].second, 1.4f, baseMs)
        // The tunnel: inertial fixes, ON the corridor, walking forward.
        val mid = session.onFix(
            points[8].first, points[8].second, 1.4f, baseMs + 60_000, trusted = false,
        )
        assertTrue(
            "dead reckoning along the corridor must advance progress",
            mid.progress.remainingDistanceM < early.progress.remainingDistanceM - 100,
        )
        assertFalse(mid.requestReroute)
    }

    @Test
    fun `off-route streak counts only trusted fixes`() {
        val session = GuidanceSession.of(variant()) { null }!!
        val offLon = 30.002 // ~140 m east — off-route, but within nothing-to-hold range
        // Two trusted off-route fixes...
        session.onFix(50.005, offLon, 1.4f, baseMs)
        session.onFix(50.005, offLon, 1.4f, baseMs + 1_000)
        // ...then untrusted ones: the streak must freeze, not fire.
        repeat(5) { i ->
            val u = session.onFix(50.005, offLon, 1.4f, baseMs + 2_000 + i * 1_000L, trusted = false)
            assertFalse("untrusted fix completed the off-route streak", u.requestReroute)
        }
        // A third TRUSTED off-route fix completes it.
        val fired = session.onFix(50.005, offLon, 1.4f, baseMs + 10_000)
        assertTrue(fired.requestReroute)
    }

    @Test
    fun `arrival requires a trusted fix`() {
        val session = GuidanceSession.of(variant()) { null }!!
        session.onFix(points[18].first, points[18].second, 1.4f, baseMs)
        // An inertial estimate drifting across the destination must not end the drive.
        val ghost = session.onFix(
            points[19].first, points[19].second, 1.4f, baseMs + 1_000, trusted = false,
        )
        assertFalse("a drive ended by a dead-reckoned ghost", ghost.arrived)
        // The sky confirms: now it is an arrival.
        val real = session.onFix(points[19].first, points[19].second, 1.4f, baseMs + 2_000)
        assertTrue(real.arrived)
    }
}
