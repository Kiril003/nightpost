package local.phantom.companion.core.net.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ATLAS-2b — pins the OSRM `/match` (map-matching) response parsing. The
 * free-drive road-snap rides on this now instead of Overpass tiles, so the
 * shape has to hold: flipped GeoJSON coordinates, the LAST matched point wins
 * (the operator is at the newest fix), and honest nulls on anything OSRM can't
 * match.
 */
class OsrmMatchParseTest {

    // Three input points; the middle one is unmatched (null tracepoint), the
    // newest carries the street name. matchings_index points at matchings[0].
    private val threePointBody = """
        {"code":"Ok",
         "matchings":[{"confidence":0.91}],
         "tracepoints":[
           {"location":[30.5000,50.4000],"matchings_index":0,"name":"вулиця Хрещатик"},
           null,
           {"location":[30.5100,50.4100],"matchings_index":0,"name":"вулиця Хрещатик"}
         ]}
    """.trimIndent()

    @Test
    fun `takes the newest matched point with flipped coords, name and confidence`() {
        val snap = parseMatch(threePointBody)!!
        assertEquals(50.4100, snap.lat, 1e-9) // GeoJSON [lon,lat] → lat
        assertEquals(30.5100, snap.lon, 1e-9)
        assertEquals("вулиця Хрещатик", snap.wayName)
        assertEquals(0.91f, snap.confidence, 1e-6f)
    }

    @Test
    fun `a trailing null tracepoint falls back to the last matched one`() {
        // OSRM emitted a match for the first two, then failed the newest.
        val body = """
            {"code":"Ok","matchings":[{"confidence":0.8}],
             "tracepoints":[
               {"location":[30.50,50.40],"matchings_index":0,"name":"A"},
               {"location":[30.51,50.41],"matchings_index":0,"name":"B"},
               null]}
        """.trimIndent()
        val snap = parseMatch(body)!!
        assertEquals(50.41, snap.lat, 1e-9)
        assertEquals("B", snap.wayName)
    }

    @Test
    fun `a blank street name becomes null, not empty string`() {
        val body = """
            {"code":"Ok","matchings":[{"confidence":0.7}],
             "tracepoints":[{"location":[30.5,50.4],"matchings_index":0,"name":""}]}
        """.trimIndent()
        val snap = parseMatch(body)!!
        assertNull(snap.wayName)
    }

    @Test
    fun `non-Ok, empty and malformed bodies are honest nulls`() {
        assertNull(parseMatch("""{"code":"NoMatch","matchings":[],"tracepoints":[]}"""))
        assertNull(parseMatch("""{"code":"Ok","matchings":[],"tracepoints":[]}"""))
        assertNull(parseMatch("""{"code":"Ok","matchings":[{"confidence":0.9}],"tracepoints":[null,null]}"""))
        assertNull(parseMatch("not json at all"))
    }

    @Test
    fun `a tracepoint with no confidence in its matching is dropped`() {
        // tracepoint matches, but matchings is empty → no confidence to trust.
        val body = """
            {"code":"Ok","matchings":[],
             "tracepoints":[{"location":[30.5,50.4],"matchings_index":0,"name":"X"}]}
        """.trimIndent()
        assertNull(parseMatch(body))
    }

    @Test
    fun `matchings_index selects the right matching's confidence`() {
        val body = """
            {"code":"Ok",
             "matchings":[{"confidence":0.2},{"confidence":0.95}],
             "tracepoints":[{"location":[30.5,50.4],"matchings_index":1,"name":"Y"}]}
        """.trimIndent()
        val snap = parseMatch(body)!!
        assertTrue(snap.confidence > 0.9f)
    }
}
