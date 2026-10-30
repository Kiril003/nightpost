package local.phantom.companion.core.net.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Відповіді — живі з photon.komoot.io і Nominatim 23.10.2026 для Сімферополя. */
class CrimeaUaTest {

    @Test
    fun `photon answer for simferopol reads in ukrainian`() {
        val body = """{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"Point","coordinates":[34.1,44.95]},
            "properties":{"name":"Симферополь","state":"Республика Крым","country":"Україна","countrycode":"UA","osm_key":"place","osm_value":"city"}}]}"""
        val place = GeocodeDialect.parseSearch(body, GeocoderKind.PHOTON).single()
        assertEquals("Сімферополь, Автономна Республіка Крим, Україна", place.name)
    }

    @Test
    fun `nominatim's ukrainian answer names the autonomous republic`() {
        val body = """{"lat":"44.952","lon":"34.102","display_name":"Сімферопіль, Сімферопольський район, Республіка Крим, Україна","type":"city"}"""
        val place = GeocodeDialect.parseReverse(body, GeocoderKind.NOMINATIM)!!
        assertEquals("Сімферопіль, Сімферопольський район, Автономна Республіка Крим, Україна", place.name)
    }

    @Test
    fun `occupation names are fixed, ukrainian ones are left alone`() {
        assertEquals("Ялта, Автономна Республіка Крим", CrimeaUa.text("город Ялта, Республика Крым", 44.5, 34.17))
        assertEquals("Керч", CrimeaUa.text("Керчь", 45.36, 36.47))
        assertEquals("Autonomous Republic of Crimea, Ukraine", CrimeaUa.text("Republic of Crimea, Ukraine"))
        assertEquals("Автономна Республіка Крим", CrimeaUa.text("Автономна Республіка Крим"))
        assertEquals("Autonomous Republic of Crimea", CrimeaUa.text("Autonomous Republic of Crimea"))
        assertEquals("Белогорск", CrimeaUa.text("Белогорск", 50.92, 128.47))
    }

    @Test
    fun `the peninsula frame holds the four cities and not kherson`() {
        for ((lat, lon) in listOf(44.952 to 34.102, 44.616 to 33.525, 44.495 to 34.166, 45.357 to 36.468)) {
            assertTrue("$lat,$lon", CrimeaUa.contains(lat, lon))
        }
        assertFalse(CrimeaUa.contains(46.636, 32.617))
        assertFalse(CrimeaUa.contains(46.174, 34.803))
    }
}
