package local.phantom.companion.core.net.map

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GEO-LAW — the dialect helper must parse BOTH geocoders' shapes to the same
 * [GeoPlace], so the symbiote AI tools (and any future caller) speak Photon
 * and Nominatim without knowing which is configured.
 */
class GeocodeDialectTest {

    private val nominatimSearch = """
        [
          {"lat":"50.4501","lon":"30.5234","display_name":"Майдан Незалежності, Київ","type":"square"},
          {"lat":"50.4400","lon":"30.4890","display_name":"Вокзальна, Київ","type":"station"}
        ]
    """.trimIndent()

    private val nominatimReverse = """
        {"lat":"50.4501","lon":"30.5234","display_name":"Хрещатик, Київ","type":"road"}
    """.trimIndent()

    private val photonSearch = """
        {"type":"FeatureCollection","features":[
          {"geometry":{"type":"Point","coordinates":[30.5234,50.4501]},
           "properties":{"name":"Майдан Незалежності","city":"Київ","osm_key":"place","osm_value":"square"}},
          {"geometry":{"type":"Point","coordinates":[30.4890,50.4400]},
           "properties":{"street":"Вокзальна","housenumber":"1","city":"Київ"}}
        ]}
    """.trimIndent()

    @Test
    fun `nominatim search parses to places`() {
        val places = GeocodeDialect.parseSearch(nominatimSearch, GeocoderKind.NOMINATIM)
        assertEquals(2, places.size)
        assertEquals(50.4501, places[0].lat, 1e-6)
        assertEquals(30.5234, places[0].lon, 1e-6)
        assertTrue(places[0].name.startsWith("Майдан"))
        assertEquals("square", places[0].kind)
    }

    @Test
    fun `photon search parses coordinates lon-lat swapped and joins a name`() {
        val places = GeocodeDialect.parseSearch(photonSearch, GeocoderKind.PHOTON)
        assertEquals(2, places.size)
        // GeoJSON is [lon,lat] — the helper must flip it.
        assertEquals(50.4501, places[0].lat, 1e-6)
        assertEquals(30.5234, places[0].lon, 1e-6)
        assertEquals("Майдан Незалежності, Київ", places[0].name)
        assertEquals("square", places[0].kind)
        // street + housenumber fold into one component.
        assertEquals("Вокзальна 1, Київ", places[1].name)
    }

    @Test
    fun `nominatim reverse is a single object, photon reverse is a feature collection`() {
        assertEquals("Хрещатик, Київ", GeocodeDialect.parseReverse(nominatimReverse, GeocoderKind.NOMINATIM)?.name)
        assertEquals("Майдан Незалежності, Київ", GeocodeDialect.parseReverse(photonSearch, GeocoderKind.PHOTON)?.name)
    }

    @Test
    fun `garbage body yields empty, never throws`() {
        assertEquals(emptyList<GeoPlace>(), GeocodeDialect.parseSearch("not json", GeocoderKind.NOMINATIM))
        assertEquals(emptyList<GeoPlace>(), GeocodeDialect.parseSearch("{}", GeocoderKind.PHOTON))
        assertNull(GeocodeDialect.parseReverse("<html>", GeocoderKind.NOMINATIM))
    }

    @Test
    fun `urls differ by dialect`() {
        assertTrue(
            GeocodeDialect.searchUrl("http://h:8088", "кафе", 5, GeocoderKind.NOMINATIM)
                .contains("/search?q="),
        )
        assertTrue(
            GeocodeDialect.searchUrl("http://h:2322", "кафе", 5, GeocoderKind.PHOTON)
                .contains("/api?q="),
        )
        assertTrue(
            GeocodeDialect.reverseUrl("http://h:2322", 50.45, 30.52, GeocoderKind.PHOTON)
                .startsWith("http://h:2322/reverse?"),
        )
    }

    // ── ATLAS-0.5: мова з локалі, а не з коду ───────────────────────────────

    @Test
    fun `ukrainian device keeps ukrainian first`() {
        assertTrue(GeoLang.acceptLanguage(Locale.forLanguageTag("uk-UA")).startsWith("uk"))
        assertEquals("uk", GeoLang.photonLang(Locale.forLanguageTag("uk-UA")))
    }

    @Test
    fun `foreign device asks in its own language, never in ukrainian`() {
        val de = GeoLang.acceptLanguage(Locale.forLanguageTag("de-DE"))
        assertTrue(de.startsWith("de"))
        assertFalse(de.contains("uk"))
        assertEquals("de", GeoLang.photonLang(Locale.forLanguageTag("de-DE")))
    }

    @Test
    fun `english is the tail of every list, once`() {
        assertEquals("en-GB,en", GeoLang.acceptLanguage(Locale.forLanguageTag("en-GB")))
        assertTrue(GeoLang.acceptLanguage(Locale.forLanguageTag("pl-PL")).endsWith(",en"))
    }

    @Test
    fun `script variants keep the full tag ahead of the bare language`() {
        val tw = GeoLang.acceptLanguage(Locale.forLanguageTag("zh-Hant-TW"))
        assertTrue(tw.startsWith("zh-Hant"))
        assertTrue(tw.contains(",zh,"))
    }

    @Test
    fun `dialect urls carry the device language and no country lock`() {
        val locale = Locale.forLanguageTag("fr-FR")
        val nominatim = GeocodeDialect.searchUrl("http://h:8088", "cafe", 5, GeocoderKind.NOMINATIM, locale)
        assertTrue(nominatim.contains("accept-language=fr"))
        assertFalse(nominatim.contains("countrycodes"))
        assertFalse(nominatim.contains("lang=uk"))

        val photon = GeocodeDialect.searchUrl("http://h:2322", "cafe", 5, GeocoderKind.PHOTON, locale)
        assertTrue(photon.contains("lang=fr"))

        val reverse = GeocodeDialect.reverseUrl("http://h:8088", 48.85, 2.35, GeocoderKind.NOMINATIM, locale)
        assertTrue(reverse.contains("accept-language=fr"))
    }
}
