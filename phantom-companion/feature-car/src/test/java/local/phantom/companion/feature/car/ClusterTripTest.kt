package local.phantom.companion.feature.car

import androidx.car.app.navigation.model.TravelEstimate
import local.phantom.companion.core.nav.GuidanceProgress
import local.phantom.companion.core.nav.TransitSteps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * NF-4 — what reaches the instrument cluster / heads-up display via
 * `NavigationManager.updateTrip`. The cluster is glanced at even faster than
 * the console, so what it must NOT carry is as load-bearing as what it does:
 * no interchange look-ahead, no time nobody measured.
 */
class ClusterTripTest {

    private fun progress(
        maneuverType: String? = "turn",
        maneuverModifier: String? = "left",
        roadName: String? = "вул. Хрещатик",
        distanceToManeuverM: Double = 240.0,
        remainingDistanceM: Double = 5_000.0,
        exitNumber: Int? = null,
        nextManeuverType: String? = "turn",
    ) = GuidanceProgress(
        snappedLat = 50.4501,
        snappedLon = 30.5234,
        bearingDeg = 0.0,
        maneuverType = maneuverType,
        maneuverModifier = maneuverModifier,
        roadName = roadName,
        distanceToManeuverM = distanceToManeuverM,
        remainingDistanceM = remainingDistanceM,
        remainingDurationS = 600.0,
        speedKmh = 50,
        limitKmh = null,
        offRoute = false,
        nextManeuverType = nextManeuverType,
        exitNumber = exitNumber,
    )

    @Test
    fun `no progress yet is a loading trip, not an empty one`() {
        val trip = clusterTrip(null)
        assertTrue(trip.isLoading)
        assertTrue(trip.steps.isEmpty())
    }

    @Test
    fun `progress becomes exactly one step`() {
        val trip = clusterTrip(progress())
        assertEquals(1, trip.steps.size)
        assertEquals(1, trip.stepTravelEstimates.size)
    }

    @Test
    fun `the interchange look-ahead stays on the console, not the cluster`() {
        // routingInfo() adds a second Step for the maneuver after next so a
        // driver does not miss a fork past a ramp; the cluster is read in a
        // fraction of that glance and must not repeat it.
        val trip = clusterTrip(progress(nextManeuverType = "fork"))
        assertEquals(1, trip.steps.size)
    }

    @Test
    fun `distance on the cluster is to the manoeuvre, not to the destination`() {
        val trip = clusterTrip(progress(distanceToManeuverM = 240.0, remainingDistanceM = 12_000.0))
        val shown = trip.stepTravelEstimates.single().remainingDistance!!.displayDistance
        assertEquals(240.0, shown, 1.0)
    }

    @Test
    fun `time to the manoeuvre is left unknown, not derived from the trip total`() {
        val trip = clusterTrip(progress())
        assertEquals(
            TravelEstimate.REMAINING_TIME_UNKNOWN,
            trip.stepTravelEstimates.single().remainingTimeSeconds,
        )
    }

    @Test
    fun `the cluster arrow agrees with the console arrow`() {
        val p = progress(maneuverType = "turn", maneuverModifier = "sharp right")
        val clusterType = clusterStep(p).maneuver?.type
        val consoleType = carManeuver(p.maneuverType, p.maneuverModifier, p.exitNumber).type
        assertEquals(consoleType, clusterType)
    }

    @Test
    fun `a road name reaches the cluster as the step cue`() {
        val step = clusterStep(progress(roadName = "просп. Перемоги"))
        assertEquals("просп. Перемоги", step.cue?.toString())
    }

    @Test
    fun `an unnamed turn is cued in the app language on the cluster and the console`() {
        val p = progress(maneuverModifier = "right", roadName = null, nextManeuverType = "fork")
        assertEquals("Turn right", clusterStep(p, Locale.ENGLISH).cue?.toString())
        val en = routingInfo(p, Locale.ENGLISH)
        assertEquals("Turn right", en.currentStep?.cue?.toString())
        assertEquals("Keep straight", en.nextStep?.cue?.toString())
        val uk = routingInfo(p, Locale.forLanguageTag("uk"))
        assertEquals("Поверніть праворуч", uk.currentStep?.cue?.toString())
        assertEquals("Тримайтесь прямо", uk.nextStep?.cue?.toString())
    }

    @Test
    fun `a transit step is cued in the app language, not as the spoken Ukrainian sentence`() {
        val p = progress(
            maneuverType = TransitSteps.BOARD,
            maneuverModifier = null,
            roadName = "Сідайте на трамвай 4 у напрямку Dubina, платформа B. Їхати 4 зупинки до Hlavní nádraží.",
            nextManeuverType = TransitSteps.ALIGHT_NEXT,
        ).copy(nextManeuverRoadName = "Виходьте на наступній — Hlavní nádraží")
        assertEquals("Take tram 4 toward Dubina, platform B", clusterStep(p, Locale.ENGLISH).cue?.toString())
        val en = routingInfo(p, Locale.ENGLISH)
        assertEquals("Take tram 4 toward Dubina, platform B", en.currentStep?.cue?.toString())
        assertEquals("Get off at the next stop, Hlavní nádraží", en.nextStep?.cue?.toString())
        val uk = routingInfo(p, Locale.forLanguageTag("uk"))
        assertEquals("Сідайте на трамвай 4 у напрямку Dubina, платформа B", uk.currentStep?.cue?.toString())
    }
}
