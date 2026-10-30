package local.phantom.companion.feature.stream.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** GEO-LAW — pins the Photon GeoJSON FeatureCollection → NominatimResult mapping. */
class PhotonClientTest {

    @Test
    fun `full feature maps name, street and city into a joined display name`() {
        val body = """
            {"type":"FeatureCollection","features":[
              {"type":"Feature",
               "geometry":{"type":"Point","coordinates":[30.5234,50.4501]},
               "properties":{"name":"Хрещатик","street":"Хрещатик","housenumber":"22",
                 "city":"Київ","state":"Київська область","country":"Україна",
                 "osm_key":"amenity","osm_value":"cafe"}}
            ]}
        """.trimIndent()

        val results = parsePhotonFeatures(body)
        assertEquals(1, results.size)
        val r = results[0]
        assertEquals("Хрещатик, Хрещатик 22, Київ, Київська область, Україна", r.displayName)
        assertEquals(50.4501, r.latitude, 1e-9)
        assertEquals(30.5234, r.longitude, 1e-9)
        assertEquals("cafe", r.type)
    }

    @Test
    fun `feature with only a name still maps, skipping blank address parts`() {
        val body = """
            {"type":"FeatureCollection","features":[
              {"type":"Feature",
               "geometry":{"type":"Point","coordinates":[30.0,50.0]},
               "properties":{"name":"Соло"}}
            ]}
        """.trimIndent()

        val results = parsePhotonFeatures(body)
        assertEquals(1, results.size)
        assertEquals("Соло", results[0].displayName)
        assertEquals(50.0, results[0].latitude, 1e-9)
        assertEquals(30.0, results[0].longitude, 1e-9)
    }

    @Test
    fun `empty FeatureCollection yields an empty list`() {
        val body = """{"type":"FeatureCollection","features":[]}"""
        assertTrue(parsePhotonFeatures(body).isEmpty())
    }

    @Test
    fun `garbage body yields an empty list, not a throw`() {
        assertTrue(parsePhotonFeatures("not json at all").isEmpty())
        assertTrue(parsePhotonFeatures("").isEmpty())
    }
}
