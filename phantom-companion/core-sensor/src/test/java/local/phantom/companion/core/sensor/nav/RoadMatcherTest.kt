package local.phantom.companion.core.sensor.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ATLAS-2 — the matcher's promises on a synthetic city block: it prefers
 * topology over raw proximity, it refuses to invent a road under a park,
 * and it survives a noisy walk down a real street.
 */
class RoadMatcherTest {

    // A small grid anchored at Kyiv. 1e-5° lat ≈ 1.11 m; streets run exactly
    // north-south / east-west so distances are easy to reason about.
    private val lat0 = 50.4500
    private val lon0 = 30.5200

    private fun latAt(northM: Double) = lat0 + Math.toDegrees(northM / 6_371_000.0)
    private fun lonAt(eastM: Double) =
        lon0 + Math.toDegrees(eastM / (6_371_000.0 * Math.cos(Math.toRadians(lat0))))

    /**
     * Two parallel north-south avenues 80 m apart, joined by one east-west
     * cross street at n=200. Node coordinates are IDENTICAL where ways meet
     * (the OSM contract the graph's junction detection rests on).
     */
    private fun cityBlock(): RoadGraph {
        val west = RoadWay(
            id = 1L,
            name = "Західна",
            points = listOf(
                latAt(0.0) to lonAt(0.0),
                latAt(200.0) to lonAt(0.0),
                latAt(400.0) to lonAt(0.0),
            ),
        )
        val east = RoadWay(
            id = 2L,
            name = "Східна",
            points = listOf(
                latAt(0.0) to lonAt(80.0),
                latAt(200.0) to lonAt(80.0),
                latAt(400.0) to lonAt(80.0),
            ),
        )
        val cross = RoadWay(
            id = 3L,
            name = "Поперечна",
            points = listOf(
                latAt(200.0) to lonAt(0.0),
                latAt(200.0) to lonAt(80.0),
            ),
        )
        return RoadGraph(listOf(west, east, cross))
    }

    @Test
    fun `snaps a noisy walk to the street being walked`() {
        val graph = cityBlock()
        val matcher = RoadMatcher()
        // North along the west avenue, fixes wobbling ±6 m east-west.
        var t = 0L
        var match: RoadMatcher.Match? = null
        val wobble = listOf(4.0, -5.0, 6.0, -3.0, 5.0, -6.0, 2.0, -4.0)
        wobble.forEachIndexed { i, e ->
            t += 1000
            match = matcher.onFix(
                graph,
                lat = latAt(20.0 + i * 15.0),
                lon = lonAt(e),
                accuracyM = 10f,
                bearingDeg = 0f,
                nowMs = t,
            )
        }
        assertNotNull(match)
        assertEquals(1L, match!!.wayId)
        // The snap kills the lateral wobble entirely.
        val lonErrM = Math.abs(
            Math.toRadians(match!!.lon - lonAt(0.0)) * 6_371_000.0 * Math.cos(Math.toRadians(lat0)),
        )
        assertTrue("still $lonErrM m off the centreline", lonErrM < 1.0)
    }

    @Test
    fun `topology outvotes raw proximity after a turn`() {
        val graph = cityBlock()
        val matcher = RoadMatcher()
        var t = 0L
        // Walk north up the west avenue to the junction…
        for (i in 0..6) {
            t += 1000
            matcher.onFix(graph, latAt(80.0 + i * 20.0), lonAt(1.0), 10f, 0f, t)
        }
        // …turn east onto the cross street.
        var match: RoadMatcher.Match? = null
        for (i in 1..4) {
            t += 1000
            match = matcher.onFix(graph, latAt(200.0), lonAt(i * 15.0), 10f, 90f, t)
        }
        assertNotNull(match)
        // 60 m along the cross street: the east avenue is only 20 m away
        // laterally by the last fix, but the chain arrived via the junction.
        assertEquals("Поперечна", match!!.wayName)
    }

    @Test
    fun `refuses to snap a park`() {
        val graph = cityBlock()
        val matcher = RoadMatcher()
        // 45 m from the nearest street with a 10 m accuracy: beyond 2.5σ.
        val match = matcher.onFix(graph, latAt(100.0), lonAt(45.0), 10f, null, 1000)
        assertNull(match)
    }

    @Test
    fun `an empty graph is an honest null`() {
        val matcher = RoadMatcher()
        assertNull(matcher.onFix(RoadGraph(emptyList()), lat0, lon0, 10f, null, 1000))
    }

    @Test
    fun `a long silence breaks the chain instead of teleporting inference`() {
        val graph = cityBlock()
        val matcher = RoadMatcher()
        matcher.onFix(graph, latAt(20.0), lonAt(2.0), 10f, 0f, 1_000)
        // 3 minutes later, on the other avenue — no transition math may span
        // that hole; it must be scored as a fresh first fix.
        val match = matcher.onFix(graph, latAt(300.0), lonAt(78.0), 10f, 0f, 181_000)
        assertNotNull(match)
        assertEquals(2L, match!!.wayId)
    }

    @Test
    fun `confidence drops between two equally plausible parallel streets`() {
        val graph = cityBlock()
        val matcher = RoadMatcher()
        // Dead centre between the avenues with a huge accuracy circle: both
        // are candidates, neither should be claimed with confidence.
        val match = matcher.onFix(graph, latAt(100.0), lonAt(40.0), 60f, null, 1000)
        if (match != null) {
            assertTrue(
                "claimed ${match.confidence} between two identical streets",
                match.confidence < 0.75f,
            )
        }
    }

    @Test
    fun `travel heading flips to run with the operator on bidirectional ways`() {
        val graph = cityBlock()
        val matcher = RoadMatcher()
        // Walking SOUTH down the west avenue (bearing 180): the way's own
        // geometry runs north, but the reported heading must run south.
        var t = 0L
        var match: RoadMatcher.Match? = null
        for (i in 0..4) {
            t += 1000
            match = matcher.onFix(graph, latAt(300.0 - i * 15.0), lonAt(1.0), 10f, 180f, t)
        }
        assertEquals(180f, match!!.headingDeg, 1f)
    }

    /**
     * The owner's crash, 15 Aug: routing on a real phone died with
     * `IndexOutOfBoundsException: Index 38 out of bounds for length 4` on
     * every fix, so the display froze while speed and GPS kept updating,
     * and relaunching died again on the next fix.
     *
     * A Candidate holds an index into the graph that produced it. The
     * matcher keeps hypotheses across fixes; the neighbourhood is refetched
     * as the operator moves. Hand it a smaller graph and yesterday's index
     * addresses a way that no longer exists.
     */
    @Test
    fun `a refetched, smaller graph does not take the app down`() {
        val matcher = RoadMatcher()
        // A wide neighbourhood: enough ways that a surviving candidate's
        // index cannot exist in the narrow one below.
        val wide = RoadGraph(
            (0 until 40).map { i ->
                RoadWay(
                    id = 100L + i,
                    name = "Смуга $i",
                    points = listOf(
                        latAt(0.0) to lonAt(i * 20.0),
                        latAt(400.0) to lonAt(i * 20.0),
                    ),
                )
            },
        )
        var t = 1000L
        repeat(3) {
            matcher.onFix(wide, latAt(100.0 + it * 10.0), lonAt(600.0), 10f, 0f, t)
            t += 1000
        }

        // Same place, one second later, but only the immediate street was
        // refetched. Before the fix this threw; the chain must simply restart.
        val narrow = RoadGraph(
            listOf(
                RoadWay(
                    id = 900L,
                    name = "Вузька",
                    points = listOf(
                        latAt(0.0) to lonAt(600.0),
                        latAt(400.0) to lonAt(600.0),
                    ),
                ),
            ),
        )
        val after = matcher.onFix(narrow, latAt(130.0), lonAt(600.0), 10f, 0f, t)
        assertNotNull("a refetched graph must restart the chain, not crash", after)
        assertEquals("Вузька", after!!.wayName)
    }
}
