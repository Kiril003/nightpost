package local.phantom.companion.core.net.map

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Каракулі на мапі» 18.09: поверх маршруту на 811 м лежав довгий вузький
 * трикутник. Трикутник — це підпис вивернутого сегмента: лінія йде вперед,
 * стрибає назад тією самою вулицею і йде вперед знову.
 *
 * Тести міряють ГЕОМЕТРІЮ, а не виклики: жодна вершина двічі, і довжина
 * ламаної дорівнює довжині прямого проходу. Наївне склеювання членів
 * відношення валить обидва.
 */
class TransitRelationGeometryTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun body(vararg members: String) =
        """{"elements":[{"type":"relation","id":1,"members":[${members.joinToString(",")}]}]}"""

    private fun way(role: String, vararg pts: Pair<Double, Double>): String {
        val geom = pts.joinToString(",") { """{"lat":${it.first},"lon":${it.second}}""" }
        return """{"type":"way","ref":7,"role":"$role","geometry":[$geom]}"""
    }

    private fun length(line: List<Pair<Double, Double>>): Double =
        (1 until line.size).sumOf { i ->
            val (aLat, aLon) = line[i - 1]
            val (bLat, bLon) = line[i]
            kotlin.math.hypot(bLat - aLat, bLon - aLon)
        }

    @Test
    fun `вивернутий член не малює трикутника`() {
        // B лежить у відношенні задом наперед — так OSM і зберігає дороги.
        val segments = TransitRelationGeometry.parse(
            body(
                way("", 50.0 to 10.0, 50.0 to 10.001),
                way("", 50.0 to 10.002, 50.0 to 10.001),
                way("", 50.0 to 10.002, 50.0 to 10.003),
            ),
            json,
        )

        assertEquals("один звʼязний ланцюг", 1, segments.size)
        val line = segments.single()
        assertEquals(
            "жодної вершини двічі",
            line.size,
            line.distinct().size,
        )
        assertEquals(
            "довжина ламаної = прямий прохід",
            0.003,
            length(line),
            1e-9,
        )
        assertEquals(50.0 to 10.0, line.first())
        assertEquals(50.0 to 10.003, line.last())
    }

    @Test
    fun `платформа і зворотний бік у лінію не потрапляють`() {
        val segments = TransitRelationGeometry.parse(
            body(
                way("platform", 50.01 to 10.0, 50.01 to 10.001),
                way("", 50.0 to 10.0, 50.0 to 10.001),
                way("backward", 50.0 to 10.001, 50.0 to 10.0),
                way("", 50.0 to 10.001, 50.0 to 10.002),
            ),
            json,
        )

        val visited = segments.flatten()
        assertTrue("платформа осторонь дороги не малюється", visited.none { it.first == 50.01 })
        assertEquals("жодної вершини двічі", visited.size, visited.distinct().size)
        assertEquals(1, segments.size)
        assertEquals(0.002, length(segments.single()), 1e-9)
    }

    @Test
    fun `розрив починає новий ланцюг замість хорди через місто`() {
        val segments = TransitRelationGeometry.parse(
            body(
                way("", 50.0 to 10.0, 50.0 to 10.001),
                way("", 50.5 to 11.0, 50.5 to 11.001),
            ),
            json,
        )

        assertEquals("два ланцюги, а не одна ламана з хордою", 2, segments.size)
        val longestEdge = segments.flatMap { seg ->
            (1 until seg.size).map { i ->
                kotlin.math.hypot(
                    seg[i].first - seg[i - 1].first,
                    seg[i].second - seg[i - 1].second,
                )
            }
        }.max()
        assertTrue("жодного стрибка через місто: $longestEdge", longestEdge < 0.01)
    }

    @Test
    fun `порядок членів не зобовʼязує — ланцюг тримається стиком`() {
        val segments = TransitRelationGeometry.stitch(
            listOf(
                listOf(50.0 to 10.001, 50.0 to 10.0),
                listOf(50.0 to 10.001, 50.0 to 10.002),
            ),
        )
        val line = segments.single()
        assertEquals(listOf(50.0 to 10.0, 50.0 to 10.001, 50.0 to 10.002), line)
    }
}
