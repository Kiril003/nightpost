package local.phantom.companion.feature.stream.ui.map.mount

import local.phantom.companion.core.nav.GuidanceProgress
import local.phantom.companion.core.nav.GuidanceState
import local.phantom.companion.core.nav.RoutePoint
import local.phantom.companion.core.nav.RouteRegister
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MountModeModelTest {

    private fun progress(offRoute: Boolean = false, toManeuver: Double = 240.0) = GuidanceProgress(
        snappedLat = 50.45,
        snappedLon = 30.52,
        bearingDeg = 90.0,
        maneuverType = "turn",
        maneuverModifier = "right",
        roadName = null,
        distanceToManeuverM = toManeuver,
        remainingDistanceM = 8_000.0,
        remainingDurationS = 600.0,
        speedKmh = 60,
        limitKmh = 90,
        offRoute = offRoute,
    )

    private fun point(register: RouteRegister) = RoutePoint(50.0, 30.0, register)

    @Test
    fun `no route falls back to the device compass, never to a blank countdown`() {
        val g = deriveMountGlance(GuidanceState.Idle, emptyList(), bearingDeg = 47f, hasFix = true)
        assertEquals(MountHeading.Compass(47f), g.heading)
        assertEquals(MountDistance.NoRoute, g.distance)
        assertNull(g.worstRegister)
    }

    @Test
    fun `no fix and no route says so twice, not once`() {
        val g = deriveMountGlance(GuidanceState.Idle, emptyList(), bearingDeg = 47f, hasFix = false)
        assertEquals(MountHeading.NoFix, g.heading)
        assertEquals(MountDistance.NoRoute, g.distance)
    }

    @Test
    fun `guiding without a first fix waits instead of counting down from nothing`() {
        val g = deriveMountGlance(GuidanceState.Guiding(null), emptyList(), 0f, hasFix = false)
        assertEquals(MountDistance.Waiting, g.distance)
    }

    @Test
    fun `off route outranks the distance it would otherwise show`() {
        val g = deriveMountGlance(GuidanceState.Guiding(progress(offRoute = true)), emptyList(), 0f, true)
        assertEquals(MountDistance.OffRoute, g.distance)
    }

    @Test
    fun `arrival outranks a still-live progress`() {
        val g = deriveMountGlance(GuidanceState.Arrived, emptyList(), 0f, hasFix = true)
        assertEquals(MountDistance.Arrived, g.distance)
    }

    @Test
    fun `the maneuver replaces the compass the moment there is one`() {
        val p = progress()
        val g = deriveMountGlance(GuidanceState.Guiding(p), emptyList(), bearingDeg = 47f, hasFix = true)
        assertEquals(MountHeading.Maneuver(p), g.heading)
        assertEquals(MountDistance.ToManeuver(240.0), g.distance)
    }

    /** Один здогад на всю лінію тягне вниз усю лінію — інакше екран обіцяв би
     *  впевненість, якої в маршруті нема. */
    @Test
    fun `the worst register on the line wins, and never rounds upward`() {
        val line = listOf(point(RouteRegister.MEASURED), point(RouteRegister.GUESSED))
        assertEquals(RouteRegister.GUESSED, worstRouteRegister(line))

        assertEquals(
            RouteRegister.REMEMBERED,
            worstRouteRegister(listOf(point(RouteRegister.DERIVED), point(RouteRegister.REMEMBERED))),
        )
        assertEquals(
            RouteRegister.DERIVED,
            worstRouteRegister(listOf(point(RouteRegister.MEASURED), point(RouteRegister.DERIVED))),
        )
        assertEquals(
            RouteRegister.MEASURED,
            worstRouteRegister(listOf(point(RouteRegister.MEASURED))),
        )
    }

    @Test
    fun `an empty line has no register at all, not a measured one`() {
        assertNull(worstRouteRegister(emptyList()))
    }
}
