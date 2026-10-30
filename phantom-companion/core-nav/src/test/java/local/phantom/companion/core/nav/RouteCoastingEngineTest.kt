package local.phantom.companion.core.nav

import local.phantom.companion.core.net.map.RouteProfile
import local.phantom.companion.core.net.map.RouteStep
import local.phantom.companion.core.net.map.RouteVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ATLAS-2.5 §12 — the coasting timeline on a fake clock: what the driver sees
 * when the sky dies mid-drive, and what he must never see in free roam.
 */
class RouteCoastingEngineTest {

    // ~111.2 m between points, due north.
    private val straight = (0..40).map { (50.0 + it * 0.001) to 30.0 }

    /** ~445 m north, then a square left turn and ~500 m east. */
    private val bent =
        (0..4).map { (50.0 + it * 0.001) to 30.0 } +
            (1..5).map { 50.004 to (30.0 + it * 0.0014) }

    private fun variantOf(pts: List<Pair<Double, Double>>) = RouteVariant(
        points = pts,
        distanceMeters = pts.zipWithNext().sumOf { (a, b) -> haversineM(a, b) },
        durationSeconds = 600.0,
        steps = listOf(
            RouteStep("depart", null, "Тестова", 0.0, 0.0, pts.first().first, pts.first().second),
            RouteStep("arrive", null, "Тестова", 0.0, 0.0, pts.last().first, pts.last().second),
        ),
        speedsMps = emptyList(),
        profile = RouteProfile.CAR,
    )

    private inner class Rig(pts: List<Pair<Double, Double>>) {
        val engine = RouteCoastingEngine()
        var now = 10_000L

        init {
            engine.bind(variantOf(pts))
        }

        fun fix(
            at: Pair<Double, Double>,
            speedMps: Double,
            guiding: Boolean = true,
        ): CoastState {
            now += 250L
            return engine.update(CoastFix(at.first, at.second, speedMps), guiding, now)
        }

        fun deny(guiding: Boolean = true): CoastState {
            now += 250L
            return engine.update(null, guiding, now)
        }

        /** Denies for [ms] at the 4 Hz display rate, returning every verdict. */
        fun denyFor(ms: Long, guiding: Boolean = true): List<CoastState> =
            (1..(ms / 250L)).map { deny(guiding) }
    }

    private fun CoastState.pos(): Pair<Double, Double> =
        puck!!.let { it.lat to it.lon }

    @Test
    fun `free roam never coasts, however long the sky is gone`() {
        val rig = Rig(straight)
        rig.fix(straight[0], 20.0, guiding = false)
        val states = rig.denyFor(60_000L, guiding = false)
        assertTrue(states.isNotEmpty())
        states.forEach {
            assertEquals(CoastMode.NORMAL, it.mode)
            assertNull(it.puck)
            assertEquals(CoastBadge.LIVE, it.badge)
        }
    }

    @Test
    fun `a short denial holds without fabricating a single metre`() {
        val rig = Rig(straight)
        rig.fix(straight[0], 20.0)
        // Ten 250 ms ticks: the last sits 2.25 s after the first denial, under the bar.
        rig.denyFor(2_500L).forEach {
            assertEquals(CoastMode.NORMAL, it.mode)
            assertNull(it.puck)
        }
        assertEquals(CoastMode.COASTING, rig.deny().mode)
    }

    @Test
    fun `coasting walks the corridor at the last trusted speed`() {
        val rig = Rig(straight)
        rig.fix(straight[0], 20.0)
        rig.denyFor(2_500L)
        val states = rig.denyFor(10_000L)
        val last = states.last()
        assertEquals(CoastMode.COASTING, last.mode)
        assertEquals(CoastBadge.COASTING, last.badge)
        // 10 s at ~20 m/s along the line, and still ON the line.
        val travelled = haversineM(straight[0], last.pos())
        assertTrue("travelled=$travelled", travelled in 180.0..210.0)
        assertEquals(30.0, last.pos().second, 1e-6)
        assertEquals(last.puck!!.coastedM, travelled, 2.0)
        // Never faster than the last trusted speed, and never backwards.
        var prev = 0.0
        states.forEach {
            assertTrue(it.puck!!.speedMps <= 20.0 + 1e-9)
            val d = haversineM(straight[0], it.pos())
            assertTrue(d >= prev - 1e-6)
            prev = d
        }
    }

    @Test
    fun `coasting brakes into the bend and never accelerates past entry speed`() {
        val rig = Rig(bent)
        rig.fix(bent[0], 20.0)
        rig.denyFor(2_500L)
        val states = rig.denyFor(24_000L).takeWhile { it.mode == CoastMode.COASTING }
        assertTrue(states.size > 40)
        val speeds = states.map { it.puck!!.speedMps }
        assertTrue("max=${speeds.max()}", speeds.max() <= 20.0 + 1e-9)
        // The square turn must pull the puck well below open-road speed.
        assertTrue("min=${speeds.min()}", speeds.min() < 14.0)
    }

    @Test
    fun `the time cap freezes the puck honestly instead of inventing more`() {
        val rig = Rig(straight)
        rig.fix(straight[0], 8.0)
        rig.denyFor(2_500L)
        val states = rig.denyFor(50_000L)
        val expired = states.first { it.mode == CoastMode.EXPIRED }
        assertEquals(CoastBadge.WAITING, expired.badge)
        assertEquals(0f, expired.puck!!.confidence, 1e-6f)
        assertEquals(0.0, expired.puck!!.speedMps, 1e-9)
        // 8 m/s × 45 s ≈ 360 m — the clock capped it, not the distance.
        assertTrue(expired.puck!!.coastedM < RouteCoastingEngine.MAX_COAST_M)
        // Frozen means frozen: further ticks do not move it.
        val after = rig.denyFor(5_000L)
        after.forEach {
            assertEquals(CoastMode.EXPIRED, it.mode)
            assertEquals(expired.pos().first, it.pos().first, 1e-9)
            assertEquals(expired.pos().second, it.pos().second, 1e-9)
        }
        // Confidence fell monotonically on the way there.
        val coasting = states.filter { it.mode == CoastMode.COASTING }.map { it.puck!!.confidence }
        assertTrue(coasting.zipWithNext().all { (a, b) -> b <= a + 1e-6f })
    }

    @Test
    fun `at motorway speed the distance cap binds before the clock`() {
        val rig = Rig(straight)
        rig.fix(straight[0], 30.0)
        rig.denyFor(2_500L)
        val states = rig.denyFor(45_000L)
        val expired = states.first { it.mode == CoastMode.EXPIRED }
        assertTrue(expired.puck!!.coastedMs < RouteCoastingEngine.MAX_COAST_MS)
        assertEquals(RouteCoastingEngine.MAX_COAST_M, expired.puck!!.coastedM, 12.0)
    }

    @Test
    fun `the merge slides the puck home and never steps further than a car could`() {
        val rig = Rig(straight)
        rig.fix(straight[0], 20.0)
        rig.denyFor(2_500L)
        rig.denyFor(10_000L)
        val coastedTo = rig.engine.update(null, true, rig.now).pos()

        // The sky returns ~60 m behind the coasted puck, still on the corridor.
        val backM = haversineM(straight[0], coastedTo) - 60.0
        val truth = RoutePath.of(variantOf(straight))!!.pointAtM(backM).let { it.lat to it.lon }

        var prev = coastedTo
        var merged = false
        val steps = mutableListOf<Double>()
        repeat(40) {
            val s = rig.fix(truth, 20.0)
            if (s.mode == CoastMode.MERGING) {
                assertEquals(CoastBadge.MERGING, s.badge)
                steps += haversineM(prev, s.pos())
                prev = s.pos()
            } else if (!merged) {
                merged = true
                assertEquals(CoastMode.NORMAL, s.mode)
                assertNull(s.puck)
                assertFalse(s.hardResync)
                assertFalse(s.offRouteOnReturn)
            }
        }
        assertTrue("never merged", merged)
        assertTrue(steps.isNotEmpty())
        // No frame may cover more than a plausible vehicle step (0.25 s at the
        // coast speed plus the catch-up allowance), with a metre of slack.
        val cap = (20.0 + RouteCoastingEngine.MERGE_CATCHUP_MPS) * 0.25 + 1.0
        steps.forEach { assertTrue("step=$it cap=$cap", it <= cap) }
        // And it converged: the last frame the engine still owned sat within one
        // slide of the measurement (the frame that closes the last metres is the
        // one that hands the puck back, and carries no position of its own).
        val residual = haversineM(prev, truth)
        assertTrue("residual=$residual", residual < 12.0)
    }

    @Test
    fun `a fix returning off the corridor ends coasting and defers to rerouting`() {
        val rig = Rig(straight)
        rig.fix(straight[0], 20.0)
        rig.denyFor(2_500L)
        rig.denyFor(6_000L)
        // ~280 m to the east of the line: not this corridor's business.
        val elsewhere = 50.001 to 30.004
        val s = rig.fix(elsewhere, 20.0)
        assertEquals(CoastMode.NORMAL, s.mode)
        assertNull(s.puck)
        assertTrue(s.offRouteOnReturn)
        assertEquals(CoastBadge.LIVE, s.badge)
        // And it does not sneak back into coasting from an off-corridor anchor.
        rig.denyFor(10_000L).forEach {
            assertEquals(CoastMode.NORMAL, it.mode)
            assertNull(it.puck)
        }
    }

    @Test
    fun `a gap too wide to slide is handed over in one honest frame`() {
        val rig = Rig(straight)
        rig.fix(straight[0], 30.0)
        rig.denyFor(2_500L)
        rig.denyFor(45_000L)
        // The car never moved: it was standing in the tunnel the whole time.
        val s = rig.fix(straight[0], 0.0)
        assertEquals(CoastMode.NORMAL, s.mode)
        assertNull(s.puck)
        assertTrue(s.hardResync)
    }

    @Test
    fun `guidance ending mid-coast drops the fabricated puck at once`() {
        val rig = Rig(straight)
        rig.fix(straight[0], 20.0)
        rig.denyFor(2_500L)
        assertEquals(CoastMode.COASTING, rig.deny().mode)
        val s = rig.deny(guiding = false)
        assertEquals(CoastMode.NORMAL, s.mode)
        assertNull(s.puck)
    }

    @Test
    fun `an anchor off the corridor never earns a coast`() {
        val rig = Rig(straight)
        rig.fix(50.0 to 30.004, 20.0)
        rig.denyFor(20_000L).forEach {
            assertEquals(CoastMode.NORMAL, it.mode)
            assertNull(it.puck)
        }
    }

    @Test
    fun `an unbound engine cannot coast`() {
        val rig = Rig(straight)
        rig.engine.bind(null)
        rig.fix(straight[0], 20.0)
        rig.denyFor(20_000L).forEach { assertNull(it.puck) }
    }

    @Test
    fun `the coasted fix reaches guidance as navigation-grade but never trusted`() {
        val rig = Rig(straight)
        rig.fix(straight[0], 20.0)
        rig.denyFor(2_500L)
        val fix = rig.denyFor(5_000L).last().puck
        assertNotNull(fix)
        val nav = fix!!.toNavFix()
        assertTrue(nav.navigationGrade)
        assertFalse(nav.trusted)
        // The doubt grows with every fabricated metre.
        assertTrue(nav.accuracyM!! > RouteCoastingEngine.COAST_UNCERTAINTY_BASE_M.toFloat())
    }

    @Test
    fun `every badge but LIVE says something in both languages`() {
        CoastBadge.entries.forEach { badge ->
            if (badge == CoastBadge.LIVE) {
                assertNull(coastBadgeUa(badge))
                assertNull(coastBadgeEn(badge))
            } else {
                assertTrue(coastBadgeUa(badge)!!.isNotBlank())
                assertTrue(coastBadgeEn(badge)!!.isNotBlank())
            }
        }
    }
}
