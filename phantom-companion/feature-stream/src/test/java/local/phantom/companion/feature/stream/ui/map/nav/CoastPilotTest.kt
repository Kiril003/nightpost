package local.phantom.companion.feature.stream.ui.map.nav

import local.phantom.companion.core.ai.context.FixGrade
import local.phantom.companion.core.ai.context.FixRegister
import local.phantom.companion.core.ai.context.LocationFix
import local.phantom.companion.core.nav.CoastBadge
import local.phantom.companion.core.nav.CoastMode
import local.phantom.companion.core.nav.CoastState
import local.phantom.companion.core.nav.CoastedFix
import local.phantom.companion.core.nav.haversineM
import local.phantom.companion.core.net.map.RouteProfile
import local.phantom.companion.core.net.map.RouteStep
import local.phantom.companion.core.net.map.RouteVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ATLAS-2.5 §12, WIRING level — the same timeline the engine's own tests walk,
 * but through what the display pump actually does with it: the fix the gate
 * handed over, the position that ends up drawn, the word the chip says.
 *
 * The one assertion the owner's field pain reduces to: across an entire denial
 * and recovery, the DRAWN position never steps further in one frame than a car
 * could have moved. «Кидає точку» is exactly that assertion failing.
 */
class CoastPilotTest {

    /** Due north, ~111.2 m between points — 4.5 km of corridor. */
    private val corridor = (0..40).map { (50.0 + it * 0.001) to 30.0 }

    private val variant = RouteVariant(
        points = corridor,
        distanceMeters = corridor.zipWithNext().sumOf { (a, b) -> haversineM(a, b) },
        durationSeconds = 240.0,
        steps = listOf(
            RouteStep("depart", null, "Тестова", 0.0, 0.0, corridor.first().first, corridor.first().second),
            RouteStep("arrive", null, "Тестова", 0.0, 0.0, corridor.last().first, corridor.last().second),
        ),
        speedsMps = emptyList(),
        profile = RouteProfile.CAR,
    )

    /** 4 Hz — the contract's floor, and the rate the paced loop coasts down to. */
    private val frameMs = 250L
    private val speedMps = 20f
    private val metresPerFrame = speedMps * frameMs / 1000.0
    private val degPerMetre = 1.0 / 111_320.0

    private class Rig(
        private val variant: RouteVariant?,
        var guiding: Boolean = true,
        var revision: Int = 1,
    ) {
        val fed = mutableListOf<CoastedFix>()
        val journal = mutableListOf<String>()
        val badges = mutableListOf<CoastBadge>()
        val steps = mutableListOf<Double>()
        var displayed: Pair<Double, Double>? = null
        var now = 10_000L

        val pilot = CoastPilot(
            guiding = { guiding },
            planRevision = { revision },
            variant = { variant },
            feedGuidance = { fed += it },
            journal = { journal += it },
        )

        /** One display frame, and the bookkeeping the real loop does with it. */
        fun frame(fix: LocationFix?, stepMs: Long = 250L): CoastState {
            now += stepMs
            val state = pilot.frame(fix, now)
            // Contract §5, in code: puck replaces the gate's position, or the
            // gate's position stands unchanged. There is no third case.
            val here = state.puck?.let { it.lat to it.lon } ?: fix?.let { it.lat to it.lon }
            if (here != null) {
                displayed?.let { steps += haversineM(it, here) }
                displayed = here
            }
            if (badges.lastOrNull() != state.badge) badges += state.badge
            return state
        }
    }

    private fun navFix(lat: Double, lon: Double, speed: Float = 20f) = LocationFix(
        lat = lat,
        lon = lon,
        bearing = 0f,
        accuracyM = 8f,
        speedMps = speed,
        grade = FixGrade.NAVIGATION,
        dead = false,
        register = FixRegister.MEASURED,
    )

    // ── the timeline ──────────────────────────────────────────────────────

    @Test
    fun `trusted then denied then coasting then merged never teleports the drawn dot`() {
        val rig = Rig(variant)
        var travelledM = 0.0

        // 5 s of believed sky, moving up the corridor.
        repeat(20) {
            travelledM += metresPerFrame
            rig.frame(navFix(50.0 + travelledM * degPerMetre, 30.0))
        }
        assertEquals(listOf(CoastBadge.LIVE), rig.badges)
        assertTrue("живий фікс не має годувати ведення коустом", rig.fed.isEmpty())

        // 12 s of denial. The car keeps moving; the sky does not say so.
        val deniedFrames = 48
        repeat(deniedFrames) {
            travelledM += metresPerFrame
            rig.frame(null)
        }
        assertEquals(CoastBadge.COASTING, rig.badges.last())

        // The sky returns 40 m BEHIND where the coast put the puck — the car
        // slowed in the tunnel, which is the ordinary case, not the exception.
        val returnM = travelledM - 40.0
        repeat(40) {
            rig.frame(navFix(50.0 + returnM * degPerMetre, 30.0, speed = 18f))
        }

        assertEquals(
            "бейдж мусить пройти рівно цю послідовність",
            listOf(CoastBadge.LIVE, CoastBadge.COASTING, CoastBadge.MERGING, CoastBadge.LIVE),
            rig.badges,
        )
        val worst = rig.steps.maxOrNull() ?: 0.0
        assertTrue(
            "точку кинуло на $worst м за один кадр — це і є «кидає точку»",
            worst <= MAX_FRAME_STEP_M,
        )
    }

    @Test
    fun `a coast feeds guidance, and only while it owns the puck`() {
        val rig = Rig(variant)
        var travelledM = 0.0
        repeat(20) {
            travelledM += metresPerFrame
            rig.frame(navFix(50.0 + travelledM * degPerMetre, 30.0))
        }
        assertTrue(rig.fed.isEmpty())

        // 10 s of denial at the loop's ACTIVE pace (~30 fps), which is where a
        // per-frame feed would drown the session.
        repeat(300) { rig.frame(null, stepMs = 33L) }
        assertTrue("коуст мусить вести сесію крізь тишу", rig.fed.isNotEmpty())
        // Contract §6 — navigation-grade, never trusted.
        assertTrue(rig.fed.all { !it.toNavFix().trusted && it.toNavFix().navigationGrade })
        // §6 again: never faster than NavigationEngine's own 4 Hz pump — ~7.4 s
        // of coasting after the 2.5 s the engine refuses to fabricate.
        assertTrue("ведення годують ${rig.fed.size} разів за ~7.4 с", rig.fed.size in 25..32)

        // §6's other half — «ніколи двічі за одну мить». Небо повернулось,
        // власна помпа NavigationEngine знову говорить, і поки точка ще
        // сповзає (MERGING) цей шлях мусить мовчати.
        val fedByBlackout = rig.fed.size
        // ~7.4 s of coasting at ~20 m/s puts the puck ~148 m past the anchor;
        // the sky returns a few metres short of it, which is a merge.
        val returnM = travelledM + 140.0
        repeat(40) { rig.frame(navFix(50.0 + returnM * degPerMetre, 30.0, speed = 18f)) }
        assertEquals(
            "коуст годував ведення, поки помпа вже говорила сама",
            fedByBlackout,
            rig.fed.size,
        )
    }

    @Test
    fun `a degraded sky coasts the dot but never speaks over the pump`() {
        val rig = Rig(variant)
        var travelledM = 0.0
        repeat(20) {
            travelledM += metresPerFrame
            rig.frame(navFix(50.0 + travelledM * degPerMetre, 30.0))
        }
        // Grade NAVIGATION, accuracy past the trusted ceiling: a denial to the
        // coast (it must not anchor to it) and an ordinary fix to the pump,
        // which is still calling GuidanceSession itself.
        var last: CoastState = CoastPilot.LIVE
        repeat(60) {
            travelledM += metresPerFrame
            last = rig.frame(navFix(50.0 + travelledM * degPerMetre, 30.0).copy(accuracyM = 400f))
        }
        assertNotNull("розпухле небо — це відмова для коуста", last.puck)
        assertTrue("коуст говорив, поки помпа говорила сама", rig.fed.isEmpty())
    }

    // ── the invariant ─────────────────────────────────────────────────────

    @Test
    fun `free roam never coasts, whatever the wiring hands the engine`() {
        val rig = Rig(variant, guiding = false)
        repeat(20) { rig.frame(navFix(50.0 + it * 0.0001, 30.0)) }
        val denied = (1..240).map { rig.frame(null) }

        assertTrue("вільний рух не має права на вигадану точку", denied.all { it.puck == null })
        assertTrue(denied.all { it.badge == CoastBadge.LIVE })
        assertTrue("поза веденням журнал мовчить", rig.journal.isEmpty())
        assertTrue(rig.fed.isEmpty())
    }

    @Test
    fun `an unplanned drive cannot coast either`() {
        val rig = Rig(variant = null)
        repeat(20) { rig.frame(navFix(50.0 + it * 0.0001, 30.0)) }
        val denied = (1..240).map { rig.frame(null) }
        assertTrue(denied.all { it.puck == null })
    }

    // ── contract §1: the rebind is a trap ─────────────────────────────────

    @Test
    fun `re-offering the same plan every frame does not kill the coast`() {
        val rig = Rig(variant)
        repeat(20) { rig.frame(navFix(50.0 + it * metresPerFrame * degPerMetre, 30.0)) }
        repeat(40) { rig.frame(null) }
        // bind() resets the engine; re-binding the same variant per frame would
        // have made coasting structurally impossible.
        assertNotNull(rig.frame(null).puck)
    }

    @Test
    fun `a new plan revision drops the coast it no longer describes`() {
        val rig = Rig(variant)
        repeat(20) { rig.frame(navFix(50.0 + it * metresPerFrame * degPerMetre, 30.0)) }
        repeat(40) { rig.frame(null) }
        assertNotNull(rig.frame(null).puck)

        rig.revision = 2
        assertNull("перепланований маршрут не успадковує чужий коуст", rig.frame(null).puck)
    }

    @Test
    fun `the drive ending unbinds the engine`() {
        val rig = Rig(variant)
        repeat(20) { rig.frame(navFix(50.0 + it * metresPerFrame * degPerMetre, 30.0)) }
        repeat(40) { rig.frame(null) }
        assertNotNull(rig.frame(null).puck)

        rig.guiding = false
        assertNull(rig.frame(null).puck)
    }

    // ── contract §3: what counts as a believed measurement ────────────────

    @Test
    fun `only a believed satellite fix reaches the engine`() {
        assertNull(CoastPilot.believedCoastFix(null))
        assertNull(
            "мережеве утримання — це не супутникова точка",
            CoastPilot.believedCoastFix(navFix(50.0, 30.0).copy(grade = FixGrade.COARSE)),
        )
        assertNull(
            "інерційна точка вже вигадана — коуст від неї коустить брехню",
            CoastPilot.believedCoastFix(navFix(50.0, 30.0).copy(dead = true)),
        )
        assertNull(
            "розпухла похибка — це DEGRADED, а DEGRADED за контрактом відмова",
            CoastPilot.believedCoastFix(navFix(50.0, 30.0).copy(accuracyM = 400f)),
        )
        assertNull(
            "точка без похибки нічого про себе не стверджує",
            CoastPilot.believedCoastFix(navFix(50.0, 30.0).copy(accuracyM = null)),
        )
        assertNotNull(CoastPilot.believedCoastFix(navFix(50.0, 30.0)))
    }

    // ── the instrument ────────────────────────────────────────────────────

    @Test
    fun `the journal writes one line a second, in one shape`() {
        val rig = Rig(variant)
        var travelledM = 0.0
        repeat(40) {
            travelledM += metresPerFrame
            rig.frame(navFix(50.0 + travelledM * degPerMetre, 30.0))
        }
        repeat(40) { rig.frame(null) }

        // 20 s of frames at 4 Hz → 20 lines, ±1 for the boundary.
        assertTrue("рядків ${rig.journal.size}", rig.journal.size in 19..21)
        assertTrue(
            "перший рядок: ${rig.journal.first()}",
            Regex("""^\d+,trusted,NORMAL,50\.\d{6},30\.\d{6},20\.0$""").matches(rig.journal.first()),
        )
        assertTrue(
            "останній рядок мусить зізнатись, що небо мовчить",
            rig.journal.last().contains(",null,${CoastMode.COASTING.name},"),
        )
    }

    private companion object {
        /**
         * 15 m at 4 Hz = 60 m/s apparent. The merge's own rate cap
         * (coast speed + 28 m/s) is what actually binds; this is the outer
         * fence that says «a car, not a teleport».
         */
        const val MAX_FRAME_STEP_M = 15.0
    }
}
