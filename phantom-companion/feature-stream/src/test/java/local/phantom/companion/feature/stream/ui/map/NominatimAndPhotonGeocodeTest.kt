package local.phantom.companion.feature.stream.ui.map

import local.phantom.companion.core.net.map.GeoEndpointsConfig
import local.phantom.companion.core.net.map.GeocoderKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.test.core.app.ApplicationProvider

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en")
class NominatimAndPhotonGeocodeTest {

    @Before
    fun bind() {
        MapText.bind(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `coordinate string is parsed directly into exact result`() {
        val client = NominatimClient(baseUrl = "https://relay.example/nominatim")
        val results = kotlinx.coroutines.runBlocking {
            client.search("50.450100, 30.523400")
        }
        assertEquals(1, results.size)
        val r = results.first()
        assertEquals("coordinate", r.type)
        assertEquals(50.450100, r.latitude, 0.00001)
        assertEquals(30.523400, r.longitude, 0.00001)
        assertTrue(r.displayName.contains("50.45010"))
    }

    @Test
    fun `cleanTitle produces street and house number instead of raw string`() {
        val addr = NominatimAddress(
            road = "вулиця Хрещатик",
            houseNumber = "22",
            city = "Київ",
            country = "Україна",
        )
        val result = NominatimResult(
            lat = "50.449",
            lon = "30.523",
            displayName = "22, вулиця Хрещатик, Печерський район, Київ, 01001, Україна",
            type = "building",
            address = addr,
        )
        assertEquals("вулиця Хрещатик, 22", result.cleanTitle)
        assertEquals("Київ", result.cleanSubtitle)
    }

    @Test
    fun `photon json parsing extracts structured address with house number`() {
        val json = """
            {
              "type": "FeatureCollection",
              "features": [
                {
                  "type": "Feature",
                  "geometry": {
                    "type": "Point",
                    "coordinates": [30.5234, 50.4501]
                  },
                  "properties": {
                    "osm_key": "building",
                    "osm_value": "yes",
                    "name": "Київська міська рада",
                    "street": "вулиця Хрещатик",
                    "housenumber": "36",
                    "city": "Київ",
                    "country": "Україна"
                  }
                }
              ]
            }
        """.trimIndent()

        val parsed = parsePhotonFeatures(json)
        assertEquals(1, parsed.size)
        val first = parsed.first()
        assertEquals(50.4501, first.latitude, 0.0001)
        assertEquals(30.5234, first.longitude, 0.0001)
        assertEquals("yes", first.type)
        assertNotNull(first.address)
        assertEquals("вулиця Хрещатик", first.address?.road)
        assertEquals("36", first.address?.houseNumber)
        assertEquals("Київ", first.address?.city)
    }

    @Test
    fun `cleanTitle falls back gracefully when address is missing`() {
        val result = NominatimResult(
            lat = "50.45",
            lon = "30.52",
            displayName = "Золоті ворота, Володимирська вулиця, Шевченківський район, Київ, 01034, Україна",
        )
        assertEquals("Золоті ворота", result.cleanTitle)
        assertEquals("Володимирська вулиця, Шевченківський район", result.cleanSubtitle)
    }

    // ── ATLAS-0.5: чесна відмова, планета замість однієї країни ─────────────

    @Test
    fun `nothing configured means no client, not a hop to public nominatim`() {
        assertNull(geocodeClientFor(null))
        assertNull(geocodeClientFor(GeoEndpointsConfig.DISABLED))
        assertNull(geocodeClientFor(GeoEndpointsConfig.DISABLED.copy(geocodeBaseUrl = "   ")))
    }

    @Test
    fun `a configured base still builds the client of its dialect`() {
        val nominatim = GeoEndpointsConfig.DISABLED.copy(
            geocodeBaseUrl = "https://relay.example/nominatim",
            geocoderKind = GeocoderKind.NOMINATIM,
        )
        assertTrue(geocodeClientFor(nominatim) is NominatimClient)
        assertTrue(geocodeClientFor(nominatim.copy(geocoderKind = GeocoderKind.PHOTON)) is PhotonClient)
    }

    @Test
    fun `search urls carry no country lock and speak the device language`() {
        val url = nominatimSearchUrl("https://geo.example", "cafe", 5, "de-DE,de,en")
        assertFalse(url.contains("countrycodes"))
        assertTrue(url.contains("accept-language=de-DE,de,en"))

        val reverse = nominatimReverseUrl("https://geo.example", 52.52, 13.40, "de-DE,de,en")
        assertFalse(reverse.contains("countrycodes"))
        assertTrue(reverse.contains("accept-language=de-DE,de,en"))
    }

    /**
     * Не текст, а ПОВЕДІНКА: жодного байта назовні, коли свого сервера нема.
     *
     * Сторож у :core-net читає джерело й ловить нову адресу з координатами.
     * Він не ловить іншого: клієнта, зібраного з порожньою основою, який усе
     * одно кудись піде. Тут «нема свого сервера» — три форми, і на кожну
     * відповідь мусить бути `null`, а не дзвінок у Komoot.
     *
     * `runBlocking` без мокнутої мережі: якби тір назовні лишився, цей тест
     * висів би на connect timeout, а не червонів — тому перевіряємо саме
     * миттєве `null`, і межа часу нижче це стверджує.
     */
    @Test
    fun `зворотне геокодування без свого сервера не ходить нікуди`() {
        val cases = listOf(
            NominatimClient(baseUrl = ""),
            NominatimClient(baseUrl = "   "),
            NominatimClient(baseUrl = "http://10.0.2.2:8088", isEmulatorDev = true),
        )
        for (c in cases) {
            val startMs = System.currentTimeMillis()
            val r = kotlinx.coroutines.runBlocking { c.reverse(50.4501, 30.5234) }
            val spentMs = System.currentTimeMillis() - startMs
            assertNull("клієнт без свого сервера повернув адресу — значить, спитав чужий", r)
            assertTrue(
                "відповідь зайняла $spentMs мс: нуль без мережі миттєвий, а секунди означають " +
                    "живий запит назовні (connect timeout), тобто тір до чужого сервера повернувся",
                spentMs < 1_000L,
            )
        }
    }

    @Test
    fun `request gap honours the one-per-second policy`() {
        assertTrue(NominatimClient.MIN_GAP_MS >= 1_100L)
    }

    @Test
    fun `query cleaning keeps ukrainian prefixes and adds foreign ones`() {
        assertEquals("Хрещатик 22", normalizeGeoQuery("вул. Хрещатик 22"))
        assertEquals("Хрещатик 22", normalizeGeoQuery("Хрещатик буд. 22"))
        assertEquals("Marszałkowska 1", normalizeGeoQuery("ul. Marszałkowska 1"))
        // Слово, що несе сенс назви, лишається: воно стоїть не попереду.
        assertEquals("Baker Street 221B", normalizeGeoQuery("Baker Street 221B"))
        assertEquals("Via Roma 1", normalizeGeoQuery("Via Roma 1"))
    }

    /**
     * Людина пише «Вул.» з великої частіше, ніж «вул.» — і саме цей випадок
     * не працював.
     *
     * `(?i)` у Java згортає регістр лише ASCII. Кирилиця повз нього проходила:
     * на JDK 17, тому самому, де сусідній тест був зелений, «Вул. Хрещатик 22»
     * не зрізалось. Тест не бачив вади, бо перевіряв тільки малі літери.
     */
    @Test
    fun `prefix is stripped whatever the case`() {
        for (q in listOf("вул. Хрещатик 22", "Вул. Хрещатик 22", "ВУЛ. Хрещатик 22")) {
            assertEquals("Хрещатик 22", normalizeGeoQuery(q))
        }
        assertEquals("Хрещатик 22", normalizeGeoQuery("Хрещатик Буд. 22"))
        assertEquals("Marszałkowska 1", normalizeGeoQuery("Ul. Marszałkowska 1"))
    }

    /**
     * Межа задана явно, а не через `\b`.
     *
     * `\b` визначено через `\w`, і його трактування кирилиці змінюється між
     * версіями JDK: на 21 префікс перестає зрізатись зовсім. Телефон — третій
     * рушій, і про нього зелене на ноуті не каже нічого. Ці два твердження
     * ловлять обидва боки межі й не залежать від рушія.
     */
    @Test
    fun `prefix inside a word is left alone`() {
        // «б.» живе всередині «Набережна» — зрізати його означало б зʼїсти назву.
        assertEquals("Набережна 5", normalizeGeoQuery("Набережна 5"))
        assertEquals("Прованс 7", normalizeGeoQuery("Прованс 7"))
        // А відокремлений префікс зрізається і після коми, і після пробілу.
        assertEquals("Київ, Хрещатик 22", normalizeGeoQuery("Київ, вул. Хрещатик 22"))
    }
}
