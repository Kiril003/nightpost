package local.phantom.companion.feature.stream.ui.map.transit

import local.phantom.companion.core.data.transit.raptor.TransitMode
import local.phantom.companion.core.sensor.nav.RailLine
import local.phantom.companion.core.sensor.nav.TransitStop
import local.phantom.companion.feature.stream.ui.map.MapPackTransit
import local.phantom.companion.feature.stream.ui.map.OfflineBounds
import local.phantom.companion.feature.stream.ui.map.buildPackRailsGeoJson
import local.phantom.companion.feature.stream.ui.map.buildPackStopsGeoJson
import local.phantom.companion.feature.stream.ui.map.packRailIsMain
import local.phantom.companion.feature.stream.ui.map.packStopLabel
import local.phantom.companion.feature.stream.ui.map.packStopComfort
import local.phantom.companion.feature.stream.ui.map.packStopMode
import local.phantom.companion.feature.stream.ui.map.packTransitDetail
import androidx.test.core.app.ApplicationProvider
import local.phantom.companion.feature.stream.ui.map.MapText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Мережа транспорту з пакета на склі — чиста половина шару.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "uk")
class PackTransitLayerTest {

    @Before
    fun bindText() {
        MapText.bind(ApplicationProvider.getApplicationContext())
    }

    private fun stop(
        id: Long = 1L,
        name: String? = "Медичний центр",
        tags: Map<String, String> = mapOf("highway" to "bus_stop"),
        routes: String = "",
    ) = TransitStop(id = id, lat = 50.5, lon = 30.4, name = name, tags = tags, routes = routes)

    @Test
    fun `a stop wears the mode its own tags declare`() {
        assertEquals(TransitMode.METRO, packStopMode(mapOf("station" to "subway")))
        assertEquals(TransitMode.TRAM, packStopMode(mapOf("railway" to "tram_stop")))
        assertEquals(TransitMode.RAIL, packStopMode(mapOf("railway" to "station")))
        assertEquals(TransitMode.TROLLEYBUS, packStopMode(mapOf("trolleybus" to "yes")))
        assertEquals(TransitMode.BUS, packStopMode(mapOf("highway" to "bus_stop")))
        assertEquals(TransitMode.OTHER, packStopMode(emptyMap()))
    }

    /**
     * Вузол метро в OSM часто несе ще й `public_transport=station`, а вузол
     * тролейбуса — `bus=yes` разом із `trolleybus=yes`. Порядок перевірок —
     * від вужчого до ширшого, інакше конкретне губиться в загальному.
     */
    @Test
    fun `the narrower tag wins over the broader one`() {
        assertEquals(
            TransitMode.METRO,
            packStopMode(mapOf("public_transport" to "station", "station" to "subway")),
        )
        assertEquals(
            TransitMode.TROLLEYBUS,
            packStopMode(mapOf("bus" to "yes", "trolleybus" to "yes")),
        )
    }

    @Test
    fun `the label carries the name and the numbers that actually stop here`() {
        assertEquals(
            "Медичний центр\n24, 25, 72",
            packStopLabel(stop(routes = "trolleybus:24|trolleybus:25|bus:72")),
        )
    }

    /**
     * Довгий хвіст номерів на склі нечитний, а обрізаний рядок вдавав би
     * повний перелік. «+N» каже, що їх більше, і не бреше про скільки.
     */
    @Test
    fun `a long list says how many it did not show`() {
        val label = packStopLabel(
            stop(routes = "bus:1|bus:2|bus:3|bus:4|bus:5|bus:6"),
            maxRefs = 4,
        )
        assertEquals("Медичний центр\n1, 2, 3, 4 +2", label)
    }

    @Test
    fun `a stop with no name is still a stop`() {
        assertEquals("5", packStopLabel(stop(name = null, routes = "bus:5")))
        assertEquals("", packStopLabel(stop(name = null)))
    }

    @Test
    fun `an unnumbered stop shows only its name`() {
        assertEquals("Медичний центр", packStopLabel(stop()))
    }

    /** Чеські стрічки склеюють район і зупинку комою без пробілу. */
    @Test
    fun `a glued comma gets its space back`() {
        assertEquals("Poruba, Otakara Jeremiáše", packStopLabel(stop(name = "Poruba,Otakara Jeremiáše")))
        assertEquals("Poruba, Otakara Jeremiáše", packStopLabel(stop(name = "Poruba, Otakara Jeremiáše")))
    }

    /**
     * ГОЛОВНИЙ ТЕСТ ЦЬОГО ФАЙЛА.
     *
     * OSM не несе ні розкладів, ні тарифів, і ця сама поверхня колись
     * друкувала «Проїзд: 8.00 ₴» літералом на кожній зупинці країни. Тому
     * шар перевіряється не на те, що він показує, а на те, чого він показати
     * НЕ МОЖЕ: у властивостях зупинки немає жодного поля про час чи ціну,
     * і додати його непомітно не вийде.
     */
    @Test
    fun `nothing about a minute or a price can reach the glass`() {
        val json = buildPackStopsGeoJson(
            listOf(stop(routes = "bus:5", tags = mapOf("highway" to "bus_stop", "shelter" to "yes"))),
        )
        for (forbidden in listOf(
            "fare", "price", "cost", "ціна", "проїзд", "₴", "UAH",
            "time", "departure", "schedule", "хвилин", "розклад", "відправ",
        )) {
            assertFalse(
                "шар почав говорити про «$forbidden» — OSM цього не несе",
                json.lowercase().contains(forbidden.lowercase()),
            )
        }
        // І при цьому він таки щось каже — інакше перевірка вище зелена на порожнечі.
        assertTrue(json.contains("Медичний центр"))
        assertTrue(json.contains("bus:5"))
    }

    @Test
    fun `stops become one point feature each, in lon-lat order`() {
        val json = buildPackStopsGeoJson(listOf(stop(id = 7L)))
        assertEquals(1, Regex("\"type\":\"Feature\"").findAll(json).count())
        assertTrue(json.contains("\"coordinates\":[30.4,50.5]"))
        assertTrue(json.contains("\"id\":\"7\""))
    }

    @Test
    fun `a quote in a stop name does not tear the json`() {
        val json = buildPackStopsGeoJson(listOf(stop(name = "Вокзал \"Центральний\"")))
        assertTrue(json.contains("\\\"Центральний\\\""))
    }

    @Test
    fun `an empty set draws nothing rather than an empty feature`() {
        assertEquals("""{"type":"FeatureCollection","features":[]}""", buildPackStopsGeoJson(emptyList()))
        assertEquals("""{"type":"FeatureCollection","features":[]}""", buildPackRailsGeoJson(emptyList()))
    }

    @Test
    fun `a rail line with one vertex is dropped, not drawn as a dot`() {
        val broken = RailLine(id = 1L, points = listOf(50.4 to 30.6), tags = mapOf("railway" to "rail"))
        assertEquals("""{"type":"FeatureCollection","features":[]}""", buildPackRailsGeoJson(listOf(broken)))
    }

    /** Магістраль і колія депо — єдина різниця, що варта окремого пікселя. */
    @Test
    fun `a siding is not a main line`() {
        assertTrue(packRailIsMain(mapOf("railway" to "rail", "usage" to "main")))
        assertTrue(packRailIsMain(mapOf("railway" to "rail", "usage" to "branch")))
        assertFalse(packRailIsMain(mapOf("railway" to "rail", "usage" to "main", "service" to "siding")))
        assertFalse(packRailIsMain(mapOf("railway" to "rail")))
    }

    @Test
    fun `rails carry their main flag onto the glass`() {
        val json = buildPackRailsGeoJson(
            listOf(
                RailLine(
                    id = 2L,
                    points = listOf(50.4 to 30.6, 50.41 to 30.61),
                    tags = mapOf("railway" to "rail", "usage" to "main"),
                ),
            ),
        )
        assertTrue(json.contains("\"main\":true"))
        assertTrue(json.contains("[30.6,50.4]"))
    }

    /**
     * Камера стає десятки разів за хвилину панорамування, а зупинки за пів
     * клітинки не змінюються. Без округлення ключа кожен дотик пальця
     * коштував би bbox-запиту по базі на мільйони рядків.
     */
    @Test
    fun `a nudge of the camera does not re-query the pack`() {
        val a = OfflineBounds(northLat = 50.51, eastLon = 30.53, southLat = 50.49, westLon = 30.51)
        val nudged = OfflineBounds(
            northLat = 50.5104, eastLon = 30.5304, southLat = 50.4904, westLon = 30.5104,
        )
        assertEquals(
            MapPackTransit.cameraKey(a, stops = true, rails = true),
            MapPackTransit.cameraKey(nudged, stops = true, rails = true),
        )
    }

    @Test
    fun `a real pan does re-query`() {
        val a = OfflineBounds(northLat = 50.51, eastLon = 30.53, southLat = 50.49, westLon = 30.51)
        val moved = OfflineBounds(northLat = 50.61, eastLon = 30.63, southLat = 50.59, westLon = 30.61)
        assertNotEquals(
            MapPackTransit.cameraKey(a, stops = true, rails = true),
            MapPackTransit.cameraKey(moved, stops = true, rails = true),
        )
    }

    /**
     * У країні 66 106 зупинок. Малювати їх усі на оглядовому зумі — не
     * повільно, а НЕЧИТНО: під кашею з крапок не видно дороги. Тому поріг
     * зупинок вищий за поріг колій, яких на порядок менше й які читаються
     * здалеку.
     */
    @Test
    fun `the card names the mode, not just the number`() {
        assertEquals(
            "тролейбус 24, 25 · автобус 72",
            packTransitDetail("trolleybus:24|trolleybus:25|bus:72"),
        )
        assertEquals("потяг 96", packTransitDetail("train:96"))
    }

    /**
     * 18 417 зупинок країни з 66 106 мають номери; решта просто не внесені в
     * маршрутні відношення OSM. Написати на такій картці «маршрутів немає»
     * означало б зробити твердження, якого дані не роблять — тому картка
     * МОВЧИТЬ.
     */
    @Test
    fun `a stop with no known routes says nothing rather than «none»`() {
        assertEquals(null, packTransitDetail(""))
    }

    /**
     * Пекар возить `shelter`/`covered`/`wheelchair`/`tactile_paving` — 16 907,
     * 1 228, 607 і 1 301 зупинки країни. Не показати їх означало б завезти
     * дані, яких ніхто не читає: той самий клас, що й вісім ключів дороги,
     * які три тижні не доїжджали до пакета.
     */
    @Test
    fun `what a person will find at the stop is read, not just carried`() {
        assertEquals("з навісом", packStopComfort(mapOf("shelter" to "yes")))
        assertEquals(
            "з навісом, доступно на візку, тактильна плитка",
            packStopComfort(
                mapOf("covered" to "yes", "wheelchair" to "yes", "tactile_paving" to "yes"),
            ),
        )
    }

    /**
     * `shelter=no` — це твердження «навісу немає», і воно теж не наше діло
     * переказувати: картка каже лише те, що на місці Є. А відсутність тегу
     * взагалі не означає нічого.
     */
    @Test
    fun `silence about comfort is not a claim about comfort`() {
        assertEquals(null, packStopComfort(emptyMap()))
        assertEquals(null, packStopComfort(mapOf("shelter" to "no", "wheelchair" to "limited")))
    }

    @Test
    fun `stops appear later than rails, and both have a floor`() {
        assertTrue(MapPackTransit.STOPS_MIN_ZOOM > MapPackTransit.RAILS_MIN_ZOOM)
        assertTrue(MapPackTransit.RAILS_MIN_ZOOM >= 8.0)
    }
}
