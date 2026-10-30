package local.phantom.companion.core.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NAV-5 — ThreatWatch rules on a straight ~2.2 km north-running route.
 * 0.001° lat ≈ 111 m, so the geometry is easy to reason about.
 */
class ThreatWatchTest {

    // 50.0000 → 50.0200 (~2 220 м), longitude fixed.
    private val route = (0..20).map { 50.0 + it * 0.001 to 30.0 }

    private fun watch(
        warnAheadM: Double = 700.0,
        oblastAt: ((Double, Double) -> String?)? = null,
        alertActive: (String) -> Boolean = { false },
    ) = ThreatWatch(route, warnAheadM, oblastAt, alertActive)

    @Test
    fun `camera ahead warns once within distance`() {
        val w = watch()
        // Camera at ~1 110 м along the route, right on the line.
        w.setThreats(cameras = listOf(50.010 to 30.0), marks = emptyList())

        // 2 000 м away — silent.
        assertTrue(w.onFix(50.0, 30.0).isEmpty())

        // ~555 м away — one warning.
        val cues = w.onFix(50.005, 30.0)
        assertEquals(1, cues.size)
        val cue = cues.single() as ThreatWatch.Cue.ThreatAhead
        assertEquals("камера швидкості", cue.kindUk)
        assertTrue("distance ${cue.distanceM}", cue.distanceM in 400..700)

        // Closer still — already announced, stays silent.
        assertTrue(w.onFix(50.008, 30.0).isEmpty())
    }

    @Test
    fun `camera far off the route is ignored`() {
        val w = watch()
        // ~1.1 km east of the polyline — another road entirely.
        w.setThreats(cameras = listOf(50.010 to 30.015), marks = emptyList())
        assertTrue(w.onFix(50.005, 30.0).isEmpty())
        assertTrue(w.onFix(50.009, 30.0).isEmpty())
    }

    @Test
    fun `manual mark warns with its own kind`() {
        val w = watch(warnAheadM = 500.0)
        w.setThreats(cameras = emptyList(), marks = listOf(Triple(50.010, 30.0, "патруль")))
        val cues = w.onFix(50.007, 30.0)
        assertEquals(1, cues.size)
        assertEquals("патруль", (cues.single() as ThreatWatch.Cue.ThreatAhead).kindUk)
    }

    @Test
    fun `refreshing threats keeps announced state`() {
        val w = watch()
        w.setThreats(cameras = listOf(50.010 to 30.0), marks = emptyList())
        assertEquals(1, w.onFix(50.005, 30.0).size)

        // A later intel refresh re-projects the same camera — no re-fire.
        w.setThreats(cameras = listOf(50.010 to 30.0), marks = emptyList())
        assertTrue(w.onFix(50.006, 30.0).isEmpty())
    }

    @Test
    fun `entering an alert oblast warns once, starting oblast is not news`() {
        var oblast = "Житомирська область"
        val active = setOf("Київська область")
        val w = watch(
            oblastAt = { _, _ -> oblast },
            alertActive = { it in active },
        )
        // First fix initialises the current oblast — silent.
        assertTrue(w.onFix(50.0, 30.0).isEmpty())

        // Crossing into an oblast under alert — one cue.
        oblast = "Київська область"
        val cues = w.onFix(50.001, 30.0)
        assertEquals(1, cues.size)
        assertTrue(cues.single() is ThreatWatch.Cue.AlertZoneEntered)

        // Staying inside — silent.
        assertTrue(w.onFix(50.002, 30.0).isEmpty())
    }

    @Test
    fun `entering a quiet oblast stays silent`() {
        var oblast = "Житомирська область"
        val w = watch(oblastAt = { _, _ -> oblast }, alertActive = { false })
        w.onFix(50.0, 30.0)
        oblast = "Київська область"
        assertTrue(w.onFix(50.001, 30.0).isEmpty())
    }

    @Test
    fun `spoken cue text is Ukrainian and concrete`() {
        val cue = ThreatWatch.Cue.ThreatAhead("камера швидкості", 400)
        assertTrue(cue.spoken.contains("камера швидкості"))
        assertTrue(cue.spoken.contains("400 метрів"))
    }

    @Test
    fun `barrier on route triggers warning cue`() {
        val w = watch(warnAheadM = 600.0)
        w.setThreats(
            cameras = emptyList(),
            marks = emptyList(),
            barriers = listOf(Triple(50.010, 30.0, "checkpoint")),
        )
        val cues = w.onFix(50.006, 30.0)
        assertEquals(1, cues.size)
        val cue = cues.single() as ThreatWatch.Cue.ThreatAhead
        assertEquals("пункт пропуску", cue.kindUk)
        assertTrue(cue.distanceM in 350..550)
    }

    @Test
    fun `empty route never crashes and never cues threats`() {
        val w = ThreatWatch(emptyList(), 700.0)
        w.setThreats(cameras = listOf(50.0 to 30.0), marks = emptyList())
        assertTrue(w.onFix(50.0, 30.0).isEmpty())
    }
}
