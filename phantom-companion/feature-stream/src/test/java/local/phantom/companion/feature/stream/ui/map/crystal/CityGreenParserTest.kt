package local.phantom.companion.feature.stream.ui.map.crystal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CityGreenParserTest {

    @Test
    fun `a park way becomes one green area`() {
        val body = """
            {"elements":[
              {"type":"way","id":11,"tags":{"leisure":"park"},"geometry":[
                {"lat":50.0,"lon":30.0},{"lat":50.0,"lon":30.001},
                {"lat":50.001,"lon":30.001},{"lat":50.0,"lon":30.0}]}
            ]}
        """.trimIndent()
        val greenery = CityGreenParser.parse(body)
        assertEquals(1, greenery.areas.size)
        assertEquals(GreenKind.PARK, greenery.areas[0].kind)
        assertEquals(3, greenery.areas[0].rings[0].size)
    }

    @Test
    fun `a surveyed tree node lands where it stands`() {
        val body = """
            {"elements":[{"type":"node","id":5,"lat":50.45,"lon":30.52,"tags":{"natural":"tree"}}]}
        """.trimIndent()
        val greenery = CityGreenParser.parse(body)
        assertEquals(listOf(CityPoint(50.45, 30.52)), greenery.trees)
    }

    @Test
    fun `a building is never green even when it carries a landuse tag`() {
        val body = """
            {"elements":[
              {"type":"way","id":11,"tags":{"building":"yes","landuse":"grass"},"geometry":[
                {"lat":50.0,"lon":30.0},{"lat":50.0,"lon":30.001},
                {"lat":50.001,"lon":30.001},{"lat":50.0,"lon":30.0}]}
            ]}
        """.trimIndent()
        assertTrue(CityGreenParser.parse(body).isEmpty)
    }

    @Test
    fun `forest and wood both read as WOOD, grass as GRASS`() {
        fun kindOf(tags: String): GreenKind? {
            val body = """
                {"elements":[{"type":"way","id":1,"tags":$tags,"geometry":[
                  {"lat":50.0,"lon":30.0},{"lat":50.0,"lon":30.001},
                  {"lat":50.001,"lon":30.001},{"lat":50.0,"lon":30.0}]}]}
            """.trimIndent()
            return CityGreenParser.parse(body).areas.firstOrNull()?.kind
        }
        assertEquals(GreenKind.WOOD, kindOf("""{"landuse":"forest"}"""))
        assertEquals(GreenKind.WOOD, kindOf("""{"natural":"wood"}"""))
        assertEquals(GreenKind.GRASS, kindOf("""{"landuse":"meadow"}"""))
        assertEquals(GreenKind.GRASS, kindOf("""{"natural":"scrub"}"""))
        assertEquals(null, kindOf("""{"landuse":"industrial"}"""))
    }

    @Test
    fun `a multipolygon park keeps its courtyard as a hole`() {
        val body = """
            {"elements":[
              {"type":"relation","id":77,"tags":{"leisure":"park","type":"multipolygon"},"members":[
                {"role":"outer","geometry":[
                  {"lat":50.0,"lon":30.0},{"lat":50.0,"lon":30.002},
                  {"lat":50.002,"lon":30.002},{"lat":50.002,"lon":30.0},{"lat":50.0,"lon":30.0}]},
                {"role":"inner","geometry":[
                  {"lat":50.0005,"lon":30.0005},{"lat":50.0005,"lon":30.001},
                  {"lat":50.001,"lon":30.001},{"lat":50.0005,"lon":30.0005}]}
              ]}
            ]}
        """.trimIndent()
        val area = CityGreenParser.parse(body).areas.single()
        assertEquals(2, area.rings.size)
        assertEquals(4, area.rings[0].size)
    }

    @Test
    fun `a tile cached before greenery was asked for parses to empty`() {
        val body = """
            {"elements":[
              {"type":"way","id":11,"tags":{"building":"yes"},"geometry":[
                {"lat":50.0,"lon":30.0},{"lat":50.0,"lon":30.001},
                {"lat":50.001,"lon":30.001},{"lat":50.0,"lon":30.0}]}
            ]}
        """.trimIndent()
        assertTrue(CityGreenParser.parse(body).isEmpty)
        assertEquals(1, CityBuildingParser.parse(body).size)
    }

    @Test
    fun `garbage parses to empty instead of throwing`() {
        assertTrue(CityGreenParser.parse("not json at all").isEmpty)
        assertTrue(CityGreenParser.parse("{}").isEmpty)
    }
}
