package local.phantom.companion.feature.stream.ui.map.nav.lanes

import local.phantom.companion.core.net.map.LaneInfo
import local.phantom.companion.core.net.map.RouteProfile
import local.phantom.companion.core.net.map.RouteStep
import local.phantom.companion.core.net.map.RouteVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val M_PER_DEG = 111_320.0

/**
 * PRESENCE — what gets baked, and (mostly) what does not.
 *
 * The honesty law of D8a lives here: no lane tags → no band, no placeholder,
 * no invented count. Every negative case below is a case the map must render
 * as plain road.
 */
class LaneBandsTest {

    private val points = (0..20).map { (50.0 + it * 50.0 / M_PER_DEG) to 30.0 }

    private val lanes = listOf(
        LaneInfo(listOf("left"), validForManeuver = false),
        LaneInfo(listOf("straight"), validForManeuver = false),
        LaneInfo(listOf("straight", "right"), validForManeuver = true),
    )

    private fun variant(
        stepLanes: List<LaneInfo> = lanes,
        profile: RouteProfile = RouteProfile.CAR,
        approachMeters: Double = 600.0,
    ) = RouteVariant(
        points = points,
        distanceMeters = 1000.0,
        durationSeconds = 120.0,
        steps = listOf(
            RouteStep("depart", null, "", approachMeters, 60.0, points[0].first, points[0].second),
            RouteStep(
                "turn", "right", "Васильківська", 400.0, 60.0,
                points[12].first, points[12].second,
                lanes = stepLanes,
            ),
            RouteStep("arrive", null, "", 0.0, 0.0, points[20].first, points[20].second),
        ),
        speedsMps = emptyList(),
        profile = profile,
    )

    private fun String.countOf(needle: String) = split(needle).size - 1

    @Test
    fun `a tagged maneuver bakes one band of lanes and separators`() {
        val bands = LaneBands.of(variant())
        assertEquals(1, bands.size)

        val band = bands.first()
        assertEquals(1, band.stepIndex)
        assertEquals(3, band.laneCount)
        assertEquals(3, band.geoJson.countOf(""""kind":"lane""""))
        assertEquals(2, band.geoJson.countOf(""""kind":"sep""""))
        // The router's verdict survives the bake — exactly one lane is lit.
        assertEquals(1, band.geoJson.countOf(""""valid":true"""))
        assertEquals(2, band.geoJson.countOf(""""valid":false"""))
        assertTrue(band.geoJson.startsWith("""{"type":"FeatureCollection"""))
    }

    @Test
    fun `no lane tags means no band at all`() {
        assertTrue(LaneBands.of(variant(stepLanes = emptyList())).isEmpty())
    }

    @Test
    fun `lanes are a driving claim, not a walking one`() {
        assertTrue(LaneBands.of(variant(profile = RouteProfile.FOOT)).isEmpty())
        assertTrue(LaneBands.of(variant(profile = RouteProfile.BIKE)).isEmpty())
    }

    @Test
    fun `a maneuver with no road behind it gets no band`() {
        // Two junctions 12 m apart: a band there is a smudge, not a lane.
        assertTrue(LaneBands.of(variant(approachMeters = 12.0)).isEmpty())
    }

    @Test
    fun `the band never runs back past the previous maneuver`() {
        // 80 m of room: the spine is cut to it, not to the 250 m the band
        // would otherwise ask for — a band through the junction behind is a
        // claim about lanes nobody measured.
        val short = LaneBands.of(variant(approachMeters = 80.0)).first()
        val full = LaneBands.of(variant()).first()
        assertTrue(short.geoJson.length < full.geoJson.length)
    }

    @Test
    fun `a lane count that is a tagging error is refused`() {
        val absurd = List(12) { LaneInfo(listOf("straight"), validForManeuver = true) }
        assertTrue(LaneBands.of(variant(stepLanes = absurd)).isEmpty())
    }

    @Test
    fun `a band with no drawable lane is null rather than empty scaffolding`() {
        assertNull(LaneBands.bandGeoJson(listOf(50.0 to 30.0), lanes))
    }

    @Test
    fun `the lit lane shows the arrow it will actually be used for`() {
        // `through;right` on a right turn draws the RIGHT arrow: the through
        // arrow is true about the paint and false about the next ten seconds.
        assertEquals("right", laneGlyphModifier(lanes[2], "right"))
        assertEquals("straight", laneGlyphModifier(lanes[1], "right"))
        assertEquals("left", laneGlyphModifier(lanes[0], "right"))
    }

    @Test
    fun `an unmarked lane offers no arrow to draw`() {
        assertNull(laneGlyphModifier(LaneInfo(emptyList(), validForManeuver = true), "right"))
    }

    @Test
    fun `a merge indication is said in the glyph family's own words`() {
        val merge = LaneInfo(listOf("merge to left"), validForManeuver = true)
        assertEquals("slight left", laneGlyphModifier(merge, "left"))
    }
}
