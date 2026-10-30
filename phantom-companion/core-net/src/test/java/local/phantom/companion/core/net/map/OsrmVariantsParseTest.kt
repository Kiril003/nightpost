package local.phantom.companion.core.net.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** NAV-1 — pins the OSRM steps/alternatives/annotations response parsing. */
class OsrmVariantsParseTest {

    private val twoVariantBody = """
        {"code":"Ok","routes":[
          {"distance":5200.5,"duration":720.0,
           "geometry":{"coordinates":[[30.5,50.4],[30.51,50.41]]},
           "legs":[{"annotation":{"speed":[7.2,8.1]},"steps":[
             {"distance":100.0,"duration":80.0,"name":"вулиця Хрещатик",
              "maneuver":{"type":"depart","location":[30.5,50.4]}},
             {"distance":200.0,"duration":60.0,"name":"",
              "maneuver":{"type":"turn","modifier":"left","location":[30.505,50.405]}},
             {"distance":150.0,"duration":40.0,"name":"провулок",
              "maneuver":{"type":"new name","location":[30.507,50.407]}},
             {"distance":0.0,"duration":0.0,"name":"",
              "maneuver":{"type":"arrive","location":[30.51,50.41]}}]}]},
          {"distance":6000.0,"duration":800.0,
           "geometry":{"coordinates":[[30.5,50.4],[30.52,50.42]]},
           "legs":[]}
        ]}
    """.trimIndent()

    @Test
    fun `parses variants with flipped coordinates, steps and speeds`() {
        val variants = parseRouteVariants(twoVariantBody, RouteProfile.CAR)!!
        assertEquals(2, variants.size)

        val main = variants[0]
        assertEquals(50.4 to 30.5, main.points.first()) // GeoJSON lon,lat → lat,lon
        assertEquals(5200.5, main.distanceMeters, 0.001)
        assertEquals(4, main.steps.size)
        assertEquals("depart", main.steps[0].type)
        assertEquals("left", main.steps[1].modifier)
        assertEquals(50.405, main.steps[1].lat, 1e-9) // maneuver location flipped too
        assertEquals(listOf(7.2, 8.1), main.speedsMps)
        assertEquals(RouteProfile.CAR, main.profile)

        val alt = variants[1]
        assertTrue(alt.steps.isEmpty())
        assertTrue(alt.speedsMps.isEmpty())
    }

    @Test
    fun `turnCount excludes depart, arrive and road renames`() {
        val main = parseRouteVariants(twoVariantBody, RouteProfile.FOOT)!!.first()
        assertEquals(1, main.turnCount)
    }

    @Test
    fun `empty routes and malformed bodies are honest nulls`() {
        assertNull(parseRouteVariants("""{"code":"NoRoute","routes":[]}""", RouteProfile.FOOT))
        assertNull(parseRouteVariants("not json at all", RouteProfile.FOOT))
    }

    @Test
    fun `route without geometry is dropped`() {
        val body = """{"code":"Ok","routes":[{"distance":1.0,"duration":1.0,
            "geometry":{"coordinates":[]},"legs":[]}]}"""
        assertNull(parseRouteVariants(body, RouteProfile.FOOT))
    }
}
