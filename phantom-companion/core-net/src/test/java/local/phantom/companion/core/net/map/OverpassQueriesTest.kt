package local.phantom.companion.core.net.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Форма запиту — єдине, що бачить публічне дзеркало. Тут вона доводиться
 * без сокета: довгий `around:` — це гарантований 504, а не «повільно».
 */
class OverpassQueriesTest {

    private fun route(points: Int): List<Pair<Double, Double>> =
        (0 until points).map { 50.45 + it * 0.0001 to 30.52 + it * 0.0001 }

    @Test
    fun `corridor caps around vertices at 48 for a long route`() {
        val query = OverpassQueries.corridor(route(500), wayRadiusMeters = 40, cameraRadiusMeters = 120)

        // Кожна клауза around: несе ту саму вибірку — рахуємо вершини в першій.
        val around = query.substringAfter("(around:40,").substringBefore(")")
        val vertices = around.split(",").size / 2
        assertEquals(OverpassQueries.MAX_AROUND_VERTICES, vertices)
    }

    @Test
    fun `corridor keeps every vertex of a short route`() {
        val query = OverpassQueries.corridor(route(12), wayRadiusMeters = 40, cameraRadiusMeters = 120)
        val around = query.substringAfter("(around:40,").substringBefore(")")
        assertEquals(12, around.split(",").size / 2)
    }

    @Test
    fun `corridor and along sample identically`() {
        val long = route(500)
        assertEquals(
            OverpassQueries.aroundCoords(long),
            OverpassQueries.samplePolyline(long).joinToString(",") { (lat, lon) -> "$lat,$lon" },
        )
        assertEquals(OverpassQueries.MAX_AROUND_VERTICES, OverpassQueries.samplePolyline(long).size)
    }

    @Test
    fun `sampling under the cap returns the original list untouched`() {
        val short = route(30)
        assertSame(short, OverpassQueries.samplePolyline(short))
    }

    @Test
    fun `sampling spans the whole route, not just its head`() {
        val long = route(500)
        val sampled = OverpassQueries.samplePolyline(long)
        assertEquals(long.first(), sampled.first())
        // Останній зразок має лежати в хвостовому відрізку — інакше коридор
        // обривався б задовго до мети.
        assertTrue(long.indexOf(sampled.last()) > long.size - (long.size / OverpassQueries.MAX_AROUND_VERTICES) - 2)
    }

    @Test
    fun `aroundAny unions every selector into a single query`() {
        val selectors = listOf("amenity=shelter", "amenity=school", "building=administrative")
        val query = OverpassQueries.aroundAny(50.45, 30.52, 3000, selectors, limit = 320)

        selectors.forEach { assertTrue("селектор $it загубився", query.contains("node[$it](around:3000,50.45,30.52)")) }
        assertEquals(1, query.split("[out:json]").size - 1)
        assertTrue(query.endsWith("out center 320;"))
    }

    @Test
    fun `byName refuses a selector that would match everything`() {
        assertEquals(null, OverpassQueries.byName("[]{}", 50.45, 30.52, 500))
        assertEquals(null, OverpassQueries.byName("   ", 50.45, 30.52, 500))
    }
}
