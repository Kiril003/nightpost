package local.phantom.companion.service

import local.phantom.companion.core.nav.GuidanceProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import java.util.Locale
import org.junit.Test

/**
 * NAV-FGS — the notification's remaining-line is the one piece of the
 * foreground service that is pure and worth pinning: it must render a
 * DOT-decimal «км» regardless of the JVM/device default locale (the uk_UA
 * host renders «1,2» for `%.1f` otherwise — the exact host-migration bug that
 * bit RouteWidget and MapAnalyzeTool).
 */
class NavigationServiceTest {

    private fun progress(remM: Double, remS: Double) = GuidanceProgress(
        snappedLat = 0.0,
        snappedLon = 0.0,
        bearingDeg = 0.0,
        maneuverType = "turn",
        maneuverModifier = "left",
        roadName = "Хрещатик",
        distanceToManeuverM = 100.0,
        remainingDistanceM = remM,
        remainingDurationS = remS,
        speedKmh = 30,
        limitKmh = 50,
        offRoute = false,
    )

    @Test
    fun `kilometres render with a dot under a comma-decimal locale`() {
        val prev = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("uk-UA"))
        try {
            assertEquals("1.2 км · 3 хв", NavigationService.remainingLineUk(progress(1234.0, 200.0)))
        } finally {
            Locale.setDefault(prev)
        }
    }

    @Test
    fun `short distances render in metres, rounded to ten`() {
        assertEquals("650 м · 3 хв", NavigationService.remainingLineUk(progress(648.0, 160.0)))
    }

    @Test
    fun `duration floors at one minute`() {
        assertEquals("20 м · 1 хв", NavigationService.remainingLineUk(progress(20.0, 5.0)))
    }

    /**
     * The engine publishes a NavState per fix (the pump runs at 250 ms). The
     * shade rounds distance to ten metres and duration to whole minutes, so a
     * walking operator renders the SAME two lines for many seconds at a time.
     * Re-posting each one costs a PendingIntent and a Binder hop to
     * system_server for a row that did not change.
     */
    @Test
    fun `a run of fixes inside one rounding step renders one shade`() {
        val shades = listOf(648.0, 647.2, 646.0, 645.1, 652.0).map {
            NavigationService.shadeOf(progress(it, 160.0), blind = false, waitingTitle = WAIT, locale = UK)
        }
        assertEquals(1, shades.distinct().size)
        assertEquals("650 м · 3 хв", shades.first().text)
    }

    @Test
    fun `crossing a rounding step is a new shade`() {
        val near = NavigationService.shadeOf(progress(648.0, 160.0), false, WAIT, UK)
        val next = NavigationService.shadeOf(progress(632.0, 160.0), false, WAIT, UK)
        assertNotEquals(near, next)
    }

    /** Blindness replaces the title but keeps the honest remaining line. */
    @Test
    fun `a blind fix says so without inventing a maneuver`() {
        val shade = NavigationService.shadeOf(progress(648.0, 160.0), blind = true, WAIT, UK)
        assertEquals(WAIT, shade.title)
        assertEquals("650 м · 3 хв", shade.text)
    }

    @Test
    fun `an English app gets an English shade`() {
        val p = progress(648.0, 160.0).copy(maneuverModifier = "right", roadName = "Main St")
        val shade = NavigationService.shadeOf(p, blind = false, waitingTitle = WAIT, locale = Locale.ENGLISH)
        assertEquals("Turn right onto Main St", shade.title)
        assertEquals("650 m · 3 min", shade.text)
        assertEquals(
            "1.2 km · 3 min",
            NavigationService.shadeOf(progress(1234.0, 200.0), false, WAIT, Locale.ENGLISH).text,
        )
    }

    @Test
    fun `a Ukrainian app keeps the Ukrainian shade`() {
        val shade = NavigationService.shadeOf(progress(648.0, 160.0), false, WAIT, UK)
        assertEquals("Поверніть ліворуч на Хрещатик", shade.title)
        assertEquals("650 м · 3 хв", shade.text)
    }

    @Test
    fun `a roundabout in the shade names the exit, as the banner does`() {
        val ring = progress(648.0, 160.0).copy(maneuverType = "roundabout", maneuverModifier = "right", exitNumber = 2)
        assertEquals(
            "Take the 2nd exit onto Ring Rd",
            NavigationService.shadeOf(ring.copy(roadName = "Ring Rd"), false, WAIT, Locale.ENGLISH).title,
        )
        assertEquals("На кільці — другий зʼїзд на Хрещатик", NavigationService.shadeOf(ring, false, WAIT, UK).title)
    }

    @Test
    fun `no progress at all is the waiting line with no body`() {
        val shade = NavigationService.shadeOf(null, blind = false, WAIT, UK)
        assertEquals(WAIT, shade.title)
        assertEquals(null, shade.text)
    }

    /** cs and the wave languages get km · min and an English maneuver, never the Ukrainian units. */
    @Test
    fun `a non-Ukrainian phone never sees Ukrainian units`() {
        val p = progress(1234.0, 200.0).copy(roadName = "Main St")
        for (tag in listOf("en", "cs", "de", "es", "fr", "pl", "tr", "it", "id", "pt-BR")) {
            val shade = NavigationService.shadeOf(p, false, WAIT, Locale.forLanguageTag(tag))
            assertEquals(tag, "1.2 km · 3 min", shade.text)
            assertEquals(tag, "Turn left onto Main St", shade.title)
        }
        assertEquals("1.2 км · 3 хв", NavigationService.shadeOf(p, false, WAIT, UK).text)
    }

    private companion object {
        const val WAIT = "Навігація · чекаю сигнал"
        val UK: Locale = Locale.forLanguageTag("uk")
    }
}
