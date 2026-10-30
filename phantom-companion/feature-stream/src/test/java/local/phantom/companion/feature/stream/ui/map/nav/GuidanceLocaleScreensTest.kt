package local.phantom.companion.feature.stream.ui.map.nav

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import local.phantom.companion.core.data.transit.AccessMode
import local.phantom.companion.core.data.transit.Itinerary
import local.phantom.companion.core.data.transit.raptor.TransitMode
import local.phantom.companion.core.design.motion.LocalMotionScale
import local.phantom.companion.core.design.theme.PhantomTheme
import local.phantom.companion.core.design.theme.SunriseWarm
import local.phantom.companion.core.nav.GuidanceProgress
import local.phantom.companion.core.nav.GuidanceSession
import local.phantom.companion.core.nav.Landmark
import local.phantom.companion.core.nav.LandmarkField
import local.phantom.companion.core.nav.LandmarkKind
import local.phantom.companion.core.nav.RoutePath
import local.phantom.companion.core.nav.TransitSteps
import local.phantom.companion.core.nav.TurnLandmarks
import local.phantom.companion.core.net.map.RouteProfile
import local.phantom.companion.core.net.map.RouteStep
import local.phantom.companion.core.net.map.RouteVariant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The banner and the step list rendered in the English app (the default) carry no Ukrainian. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-w411dp-h891dp-xhdpi")
class GuidanceLocaleScreensTest {

    private val cyrillic = Regex("[\\u0400-\\u04FF]")

    private val north = (0..10).map { (50.0 + it * 0.001) to 30.0 }
    private val east = (1..9).map { 50.010 to 30.0 + it * 0.001 }
    private val points = north + east

    private fun footVariant() = RouteVariant(
        points = points,
        distanceMeters = 1700.0,
        durationSeconds = 1000.0,
        steps = listOf(
            RouteStep("depart", null, "", 0.0, 0.0, points[0].first, points[0].second),
            RouteStep("turn", "right", "Main St", 1100.0, 800.0, points[10].first, points[10].second),
            RouteStep("arrive", null, "", 600.0, 200.0, points.last().first, points.last().second),
        ),
        speedsMps = emptyList(),
        profile = RouteProfile.FOOT,
    )

    /** A real session near a pharmacy at the turn: the banner shows its landmark line. */
    private fun landmarkProgress(): GuidanceProgress {
        val v = footVariant()
        val session = GuidanceSession.of(v) { null }!!
        val pharmacy = Landmark(LandmarkKind.PHARMACY, null, 50.0097, 30.0002)
        session.attachLandmarks(TurnLandmarks.plan(v, RoutePath.of(v)!!, LandmarkField(listOf(pharmacy))))
        val base = 1_000_000_000L
        session.onFix(50.009, 30.0, 1.4f, base)
        return session.onFix(50.0095, 30.0, 1.4f, base + 1_000).progress!!
    }

    private fun transitSteps(): List<RouteStep> {
        val walk = Itinerary.Leg.Access(
            mode = AccessMode.WALK,
            fromLat = 49.80, fromLon = 18.25, toLat = 49.802, toLon = 18.25,
            toStopName = "Náměstí", seconds = 150, meters = 222,
            points = emptyList(),
        )
        val ride = Itinerary.Leg.Ride(
            routeLabel = "4", mode = TransitMode.TRAM, colour = -1,
            fromStopName = "Náměstí", toStopName = "Hlavní nádraží",
            fromLat = 49.802, fromLon = 18.25, toLat = 49.818, toLon = 18.25,
            departSec = 50_000, arriveSec = 50_600, intermediateStops = 3,
            points = emptyList(), timesAreModelled = false,
            headsign = "Dubina", boardPlatform = "B",
            stops = listOf(Itinerary.Leg.RideStop("S1", 49.81, 18.25)),
        )
        return TransitSteps.of(Itinerary(listOf(walk, ride), 49_850, 50_600, 0, false))
    }

    private fun progressOf(step: RouteStep) = GuidanceProgress(
        snappedLat = step.lat,
        snappedLon = step.lon,
        bearingDeg = 0.0,
        maneuverType = step.type,
        maneuverModifier = step.modifier,
        roadName = step.roadName.takeIf { it.isNotBlank() },
        distanceToManeuverM = 120.0,
        remainingDistanceM = 2400.0,
        remainingDurationS = 900.0,
        speedKmh = null,
        limitKmh = null,
        offRoute = false,
    )

    @Test
    fun `the landmark line under an English action is English`() {
        val shown = shown { ManeuverBanner(progress = landmarkProgress()) }
        assertTrue(shown.toString(), "Turn right" in shown)
        assertNotNull(shown.toString(), shown.firstOrNull { it.contains("pharmacy") })
        assertNoUkrainian(shown)
    }

    @Test
    @Config(qualifiers = "uk-w411dp-h891dp-xhdpi")
    fun `the Ukrainian banner keeps its landmark line`() {
        val shown = shown { ManeuverBanner(progress = landmarkProgress()) }
        assertTrue(shown.toString(), "за аптекою" in shown)
    }

    @Test
    fun `a transit step on the banner reads as the English instruction`() {
        val board = transitSteps().first { it.type == TransitSteps.BOARD }
        val shown = shown { ManeuverBanner(progress = progressOf(board)) }
        assertTrue(shown.toString(), "Take tram 4 toward Dubina, platform B" in shown)
        assertFalse(shown.toString(), "Continue straight" in shown)
        assertNoUkrainian(shown)
    }

    @Test
    fun `the step list of a drive is English throughout`() {
        val v = footVariant().copy(profile = RouteProfile.CAR, distanceMeters = 3200.0, durationSeconds = 720.0)
        val shown = shown { ManeuverListSheet(variant = v, onClose = {}) }
        assertTrue(shown.toString(), "Turn right" in shown)
        assertTrue(shown.toString(), "Main St" in shown)
        assertNoUkrainian(shown)
    }

    @Test
    fun `the step list of a transit route is English throughout`() {
        val steps = transitSteps()
        val v = RouteVariant(
            points = steps.map { it.lat to it.lon },
            distanceMeters = 2400.0,
            durationSeconds = 900.0,
            steps = steps,
            speedsMps = emptyList(),
            profile = RouteProfile.TRANSIT,
        )
        val shown = shown { ManeuverListSheet(variant = v, onClose = {}) }
        assertTrue(shown.toString(), "Take tram 4 toward Dubina, platform B" in shown)
        assertFalse(shown.toString(), "Continue straight" in shown)
        assertNoUkrainian(shown)
    }

    private fun assertNoUkrainian(shown: List<String>) {
        val leaked = shown.filter { cyrillic.containsMatchIn(it) }
        assertTrue("Ukrainian on an English screen: $leaked", leaked.isEmpty())
    }

    private fun shown(content: @Composable () -> Unit): List<String> {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        activity.setContent {
            PhantomTheme(spec = SunriseWarm) {
                CompositionLocalProvider(LocalMotionScale provides 0f) { content() }
            }
        }
        repeat(20) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100)) }
        val out = nodes(activity).flatMap { n ->
            listOfNotNull(
                n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text },
            ) + n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        }.filter { it.isNotBlank() }
        controller.pause().stop().destroy()
        return out
    }

    private fun nodes(activity: ComponentActivity): List<SemanticsNode> {
        val owner = findRoot(activity.window.decorView)?.semanticsOwner ?: return emptyList()
        val out = mutableListOf<SemanticsNode>()
        fun walk(node: SemanticsNode) {
            out += node
            node.children.forEach(::walk)
        }
        walk(owner.unmergedRootSemanticsNode)
        return out
    }

    private fun findRoot(view: View): RootForTest? {
        if (view is RootForTest) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findRoot(view.getChildAt(i))?.let { return it }
        return null
    }
}
