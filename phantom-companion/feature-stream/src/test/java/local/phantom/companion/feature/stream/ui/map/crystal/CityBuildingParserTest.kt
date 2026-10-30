package local.phantom.companion.feature.stream.ui.map.crystal

import local.phantom.companion.feature.stream.ui.map.GroundRegister
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CityBuildingParserTest {

    @Test
    fun `way with explicit height and colour parses fully`() {
        val buildings = CityBuildingParser.parse(SAMPLE)
        val tower = buildings.single { it.id == 100L }
        assertEquals(24f, tower.heightM, 0.01f)
        assertEquals(RoofShape.GABLED, tower.roofShape)
        assertEquals(2.5f, tower.roofHeightM, 0.01f)
        assertEquals(0xFFB22222.toInt(), tower.wallColor) // "brick"
        assertEquals(0xFF8B0000.toInt(), tower.roofColor) // "#8b0000"
        // Closed way ring stored open: 4 corners, no duplicated closing point.
        assertEquals(1, tower.rings.size)
        assertEquals(4, tower.rings[0].size)
        // An explicit `height` tag is MEASURED, not merely present.
        assertEquals(GroundRegister.MEASURED, tower.register)
    }

    @Test
    fun `way with levels only derives height and defaults`() {
        val buildings = CityBuildingParser.parse(SAMPLE)
        val slab = buildings.single { it.id == 101L }
        assertEquals(5 * 3.2f, slab.heightM, 0.01f)
        assertEquals(RoofShape.FLAT, slab.roofShape)
        assertEquals(0f, slab.minHeightM, 0.01f)
        assertNull(slab.wallColor)
        // `building:levels` without `height` is DERIVED — an extrusion, not a measurement.
        assertEquals(GroundRegister.DERIVED, slab.register)
    }

    @Test
    fun `neither height nor levels is GUESSED, and no meta means no edit year`() {
        val buildings = CityBuildingParser.parse(SAMPLE)
        val courtyard = buildings.single { it.id == 200L }
        assertEquals(GroundRegister.GUESSED, courtyard.register)
        buildings.forEach { assertNull("id ${it.id}", it.editedYear) }
    }

    @Test
    fun `a timestamp from Overpass meta parses to its year`() {
        val buildings = CityBuildingParser.parse(TIMESTAMPED_SAMPLE)
        assertEquals(2020, buildings.single().editedYear)
    }

    @Test
    fun `building=no and untagged ways are skipped`() {
        val buildings = CityBuildingParser.parse(SAMPLE)
        assertTrue(buildings.none { it.id == 102L })
        assertEquals(3, buildings.size) // 100, 101 + relation 200
    }

    @Test
    fun `multipolygon relation stitches split outer ways and keeps inner hole`() {
        val buildings = CityBuildingParser.parse(SAMPLE)
        val courtyard = buildings.single { it.id == 200L }
        assertEquals(2, courtyard.rings.size)
        assertEquals(4, courtyard.rings[0].size) // outer stitched from two open segments
        assertEquals(3, courtyard.rings[1].size) // inner courtyard ring
        assertEquals(RoofShape.HIPPED, courtyard.roofShape)
    }

    @Test
    fun `stitcher joins reversed segments and drops open leftovers`() {
        val a = CityPoint(0.0, 0.0); val b = CityPoint(0.0, 1.0)
        val c = CityPoint(1.0, 1.0); val d = CityPoint(1.0, 0.0)
        val rings = CityBuildingParser.stitchRings(
            listOf(
                listOf(a, b, c),
                listOf(a, d, c), // reversed direction relative to ring winding
                listOf(CityPoint(5.0, 5.0), CityPoint(6.0, 6.0)), // dangling — dropped
            )
        )
        assertEquals(1, rings.size)
        assertEquals(4, rings[0].size)
    }

    @Test
    fun `meters parser accepts unit suffix and rejects other units`() {
        assertEquals(12f, CityBuildingParser.meters("12")!!, 0.01f)
        assertEquals(12.5f, CityBuildingParser.meters("12.5 m")!!, 0.01f)
        assertEquals(7f, CityBuildingParser.meters("7m")!!, 0.01f)
        assertNull(CityBuildingParser.meters("40 ft"))
        assertNull(CityBuildingParser.meters("tall"))
        assertNull(CityBuildingParser.meters("-3"))
    }

    @Test
    fun `colour parser handles hex short hex names and garbage`() {
        assertEquals(0xFFFFFFFF.toInt(), CityBuildingParser.parseOsmColor("#fff"))
        assertEquals(0xFF1F1F24.toInt(), CityBuildingParser.parseOsmColor("#1F1F24"))
        assertEquals(0xFFF5F5DC.toInt(), CityBuildingParser.parseOsmColor("beige"))
        assertNull(CityBuildingParser.parseOsmColor("hotdogstand"))
        assertNull(CityBuildingParser.parseOsmColor(null))
    }

    @Test
    fun `unknown roof shapes map to OTHER and absent to FLAT`() {
        assertEquals(RoofShape.FLAT, CityBuildingParser.roofShape(null))
        assertEquals(RoofShape.DOME, CityBuildingParser.roofShape("onion"))
        assertEquals(RoofShape.OTHER, CityBuildingParser.roofShape("mansard"))
    }

    @Test
    fun `malformed body yields empty list`() {
        assertTrue(CityBuildingParser.parse("not json").isEmpty())
        assertTrue(CityBuildingParser.parse("{}").isEmpty())
    }

    private companion object {
        // Realistic `out tags geom` shape: way 100 (full tags), way 101
        // (levels only), way 102 (building=no), relation 200 (outer split
        // into two open segments + inner triangle hole).
        val SAMPLE = """
        {
          "version": 0.6,
          "elements": [
            {
              "type": "way", "id": 100,
              "tags": {"building": "yes", "height": "24", "roof:shape": "gabled",
                       "roof:height": "2.5", "building:colour": "brick", "roof:colour": "#8b0000"},
              "geometry": [
                {"lat": 50.4500, "lon": 30.5200}, {"lat": 50.4501, "lon": 30.5200},
                {"lat": 50.4501, "lon": 30.5202}, {"lat": 50.4500, "lon": 30.5202},
                {"lat": 50.4500, "lon": 30.5200}
              ]
            },
            {
              "type": "way", "id": 101,
              "tags": {"building": "apartments", "building:levels": "5"},
              "geometry": [
                {"lat": 50.4510, "lon": 30.5210}, {"lat": 50.4511, "lon": 30.5210},
                {"lat": 50.4511, "lon": 30.5212}, {"lat": 50.4510, "lon": 30.5210}
              ]
            },
            {
              "type": "way", "id": 102,
              "tags": {"building": "no"},
              "geometry": [
                {"lat": 50.4520, "lon": 30.5220}, {"lat": 50.4521, "lon": 30.5220},
                {"lat": 50.4521, "lon": 30.5222}, {"lat": 50.4520, "lon": 30.5220}
              ]
            },
            {
              "type": "relation", "id": 200,
              "tags": {"building": "yes", "type": "multipolygon", "roof:shape": "hipped"},
              "members": [
                {"type": "way", "role": "outer", "geometry": [
                  {"lat": 50.4600, "lon": 30.5300}, {"lat": 50.4601, "lon": 30.5300},
                  {"lat": 50.4601, "lon": 30.5302}
                ]},
                {"type": "way", "role": "outer", "geometry": [
                  {"lat": 50.4601, "lon": 30.5302}, {"lat": 50.4600, "lon": 30.5302},
                  {"lat": 50.4600, "lon": 30.5300}
                ]},
                {"type": "way", "role": "inner", "geometry": [
                  {"lat": 50.46003, "lon": 30.53005}, {"lat": 50.46006, "lon": 30.53005},
                  {"lat": 50.46006, "lon": 30.53010}, {"lat": 50.46003, "lon": 30.53005}
                ]}
              ]
            }
          ]
        }
        """.trimIndent()

        // Mariupol shape, not the place — a rich, `meta`-carrying element
        // whose `timestamp` is what lets a caller tell "measured recently"
        // apart from "measured in 2020 and frozen since".
        val TIMESTAMPED_SAMPLE = """
        {
          "elements": [
            {
              "type": "way", "id": 300, "timestamp": "2020-05-14T09:12:33Z",
              "tags": {"building": "yes", "height": "12"},
              "geometry": [
                {"lat": 47.0971, "lon": 37.5434}, {"lat": 47.0972, "lon": 37.5434},
                {"lat": 47.0972, "lon": 37.5436}, {"lat": 47.0971, "lon": 37.5434}
              ]
            }
          ]
        }
        """.trimIndent()
    }
}
