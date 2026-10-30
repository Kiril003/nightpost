package local.phantom.companion.feature.stream.ui.map.nav.lanes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** Metres per degree of latitude — the fixtures' own frame. */
private const val M_PER_DEG = 111_320.0

/**
 * PRESENCE — the band's arithmetic, measured on the ground rather than trusted.
 *
 * Two fixtures: a straight kilometre, where a lane must simply stand a fixed
 * distance to the side, and a 200 m radius quarter-arc — the shape a motorway
 * ramp actually is, and the shape that exposes a naive per-vertex normal
 * (lanes that pinch on the inside of the bend and gap on the outside).
 */
class LaneGeometryTest {

    private val origin = 50.0 to 30.0

    /** [north] metres up, [east] metres right of [origin]. */
    private fun at(north: Double, east: Double): Pair<Double, Double> =
        (origin.first + north / M_PER_DEG) to
            (origin.second + east / (M_PER_DEG * cos(Math.toRadians(origin.first))))

    private val straight = (0..20).map { at(it * 50.0, 0.0) }

    private val radius = 200.0

    /** Anticlockwise from due east to due north — a left-hand bend, centre inside. */
    private val arc = (0..90 step 5).map { deg ->
        val rad = Math.toRadians(deg.toDouble())
        at(radius * sin(rad), radius * cos(rad))
    }
    private val arcCentre = at(0.0, 0.0)

    private fun length(path: List<Pair<Double, Double>>): Double =
        path.zipWithNext().sumOf { (a, b) -> laneMetersBetween(a, b) }

    @Test
    fun `lane centres are symmetric about the route line`() {
        assertEquals(listOf(-5.25, -1.75, 1.75, 5.25), laneCenterOffsets(4))
        assertEquals(listOf(-3.5, 0.0, 3.5), laneCenterOffsets(3))
        assertEquals(0.0, laneCenterOffsets(5).sum(), 1e-9)
        assertEquals(0.0, laneCenterOffsets(6).sum(), 1e-9)
    }

    @Test
    fun `separators sit between lanes and are one fewer`() {
        assertEquals(listOf(-3.5, 0.0, 3.5), laneSeparatorOffsets(4))
        assertEquals(2, laneSeparatorOffsets(3).size)
        assertTrue(laneSeparatorOffsets(1).isEmpty())
    }

    @Test
    fun `N lanes make N polylines, each a fixed distance aside`() {
        val spine = approachPath(straight, at(1000.0, 0.0), 250.0)
        val lanes = laneCenterOffsets(4).map { offsetPolyline(spine, it) }

        assertEquals(4, lanes.size)
        assertTrue(lanes.all { it.size == spine.size })
        lanes.forEachIndexed { i, lane ->
            val want = kotlin.math.abs(laneCenterOffsets(4)[i])
            lane.zip(spine).forEach { (l, s) ->
                assertEquals("lane $i off the spine", want, laneMetersBetween(l, s), 0.02)
            }
        }
        // Positive offset is to the RIGHT of travel — due north here, so east.
        assertTrue(lanes.last().first().second > spine.first().second)
        assertTrue(lanes.first().first().second < spine.first().second)
    }

    @Test
    fun `on a bend the lanes stay parallel instead of cutting the corner`() {
        val outer = offsetPolyline(arc, 3.5)
        val inner = offsetPolyline(arc, -3.5)

        assertEquals(arc.size, outer.size)
        outer.forEach {
            assertEquals(radius + 3.5, laneMetersBetween(arcCentre, it), 0.05)
        }
        inner.forEach {
            assertEquals(radius - 3.5, laneMetersBetween(arcCentre, it), 0.05)
        }
        // The geometric signature of a true offset: the outside of the bend is
        // longer than the inside by the width times the swept angle. Measured
        // over the MITERED interior only — the two end vertices have a single
        // segment each, so their normal is the chord's and not the radius's,
        // and that difference is the band's ends, not its parallelism.
        val sweptRad = Math.toRadians(80.0) // vertex 1 (5°) … vertex 17 (85°)
        assertEquals(
            7.0 * sweptRad,
            length(outer.subList(1, outer.size - 1)) - length(inner.subList(1, inner.size - 1)),
            0.05,
        )
        assertTrue(length(outer) > length(inner))
    }

    @Test
    fun `the approach is the last stretch before the maneuver, in travel order`() {
        val spine = approachPath(straight, at(600.0, 0.0), 250.0)

        assertEquals(250.0, length(spine), 0.5)
        assertEquals(0.0, laneMetersBetween(spine.last(), at(600.0, 0.0)), 0.5)
        assertEquals(0.0, laneMetersBetween(spine.first(), at(350.0, 0.0)), 0.5)
    }

    @Test
    fun `a maneuver beside the line still snaps onto it`() {
        val spine = approachPath(straight, at(600.0, 6.0), 100.0)
        assertEquals(100.0, length(spine), 0.5)
        assertEquals(0.0, laneMetersBetween(spine.last(), at(600.0, 0.0)), 0.5)
    }

    @Test
    fun `less road than asked for gives the road that exists`() {
        val spine = approachPath(straight, at(120.0, 0.0), 250.0)
        assertEquals(120.0, length(spine), 0.5)
        assertEquals(0.0, laneMetersBetween(spine.first(), origin), 0.5)
    }

    @Test
    fun `degenerate input draws nothing rather than a stub`() {
        assertTrue(approachPath(emptyList(), origin, 250.0).isEmpty())
        assertTrue(approachPath(listOf(origin), origin, 250.0).isEmpty())
        assertTrue(approachPath(straight, origin, 0.0).isEmpty())
        assertTrue(offsetPolyline(listOf(origin), 3.5).isEmpty())
        assertTrue(offsetPolyline(listOf(origin, origin), 3.5).isEmpty())
    }
}
