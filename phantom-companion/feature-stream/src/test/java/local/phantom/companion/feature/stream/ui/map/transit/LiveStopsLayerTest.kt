package local.phantom.companion.feature.stream.ui.map.transit

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import local.phantom.companion.core.data.transit.raptor.TransitMode
import local.phantom.companion.core.net.transit.TransitousStopClient
import local.phantom.companion.feature.stream.ui.map.MapPackTransit
import local.phantom.companion.feature.stream.ui.map.OfflineBounds
import local.phantom.companion.feature.stream.ui.map.PackTransitSlice
import local.phantom.companion.feature.stream.ui.map.buildLiveStopsGeoJson
import local.phantom.companion.feature.stream.ui.map.liveStopMode
import local.phantom.companion.feature.stream.ui.map.packStopModeWord
import androidx.test.core.app.ApplicationProvider
import local.phantom.companion.feature.stream.ui.map.MapText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * СКАРГА ВЛАСНИКА 20.09: «навіть у Львові не працює нормально, там і жодної
 * зупинки не бачу».
 *
 * Причина була не в місті. Шар зупинок живився ВИКЛЮЧНО дорожнім пакетом, а
 * пакетів транспорту в роздачі кузні на 20.09 рівно один на весь світ —
 * Люксембург (forge-catalog.json: BASEMAP 4, DEM 18, GEOCODER 26,
 * TRANSIT_GTFS 1). Тобто шар був порожній ЗА ПОБУДОВОЮ всюди, де людина
 * живе: і у Львові, і в Києві.
 *
 * Дані при цьому були й були безкоштовні: заміряно 20.09 з
 * api.transitous.org/api/v1/map/stops — рамка Львова (49.78,23.92 →
 * 49.88,24.10) віддала 769 зупинок за 0,28 с, рамка Києва — 527.
 *
 * Цей файл існує, щоб та сама тиша не повернулась мовчки. Червоніє, якщо
 * камера стала над містом без пакета, а шар лишився порожнім.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "uk")
class LiveStopsLayerTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        MapText.bind(ApplicationProvider.getApplicationContext())
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private val lviv = OfflineBounds(
        southLat = 49.78, westLon = 23.92, northLat = 49.88, eastLon = 24.10,
    )

    private fun liveStop(
        id: String = "ua-lviv_1",
        name: String = "Хоткевича (310)",
        modes: List<String> = listOf("BUS"),
    ) = TransitousStopClient.MapStop(id = id, name = name, lat = 49.83, lon = 24.02, modes = modes)

    private fun features(json: String): Int =
        Regex("\"type\":\"Feature\"").findAll(json).count()

    /**
     * ГОЛОВНИЙ СТОРОЖ. Пакета немає — зупинки все одно мусять бути на склі.
     *
     * Саме це твердження було ХИБНИМ до 20.09, і саме воно ловить повернення
     * вади: досить прибрати живого читача або його виклик, і тут стане 0.
     */
    @Test
    fun `камера над містом без пакета все одно дає зупинки`() = runTest(dispatcher) {
        val layer = MapPackTransit(
            scope = this,
            fetch = { _, _, _, _ -> PackTransitSlice(stops = emptyList(), rails = emptyList()) },
            liveStops = { _, _, _, _ -> List(769) { liveStop(id = "ua-lviv_$it") } },
            io = dispatcher,
        )
        layer.onCamera(lviv, zoom = 14.0)
        advanceUntilIdle()

        assertEquals(
            "шар зупинок замовк без пакета — саме це власник бачив у Львові 20.09",
            769,
            features(layer.stopsGeoJson.value),
        )
    }

    /** Той самий шлях без жодного читача пакета взагалі — свіжий пристрій. */
    @Test
    fun `свіжий пристрій без читача пакета теж показує зупинки`() = runTest(dispatcher) {
        val layer = MapPackTransit(
            scope = this,
            fetch = null,
            liveStops = { _, _, _, _ -> listOf(liveStop()) },
            io = dispatcher,
        )
        layer.onCamera(lviv, zoom = 14.0)
        advanceUntilIdle()

        assertEquals(1, features(layer.stopsGeoJson.value))
    }

    /**
     * ПОРОЖНЬО І ЧЕСНО. Відмова мережі лишає шар порожнім, а не тримає
     * попередню рамку: крапка не на своєму місці гірша за відсутність крапки.
     */
    @Test
    fun `відмова мережі стирає шар, а не показує стару рамку`() = runTest(dispatcher) {
        var answer: List<TransitousStopClient.MapStop>? = listOf(liveStop())
        val layer = MapPackTransit(
            scope = this,
            fetch = { _, _, _, _ -> PackTransitSlice(emptyList(), emptyList()) },
            liveStops = { _, _, _, _ -> answer },
            io = dispatcher,
        )
        layer.onCamera(lviv, zoom = 14.0)
        advanceUntilIdle()
        assertEquals(1, features(layer.stopsGeoJson.value))

        answer = null
        layer.onCamera(lviv.copy(southLat = 50.38, northLat = 50.48, westLon = 30.40, eastLon = 30.58), 14.0)
        advanceUntilIdle()

        assertEquals(
            "після відмови шар мусить бути порожнім, а не нести зупинки чужого міста",
            0,
            features(layer.stopsGeoJson.value),
        )
    }

    /**
     * Де пакет накрив рамку — мережі не чіпаємо. Не заради трафіку: рамка,
     * надіслана назовні там, де офлайн уже відповів, — витік координат без
     * жодної користі.
     */
    @Test
    fun `пакет накрив рамку — жодного мережевого запиту`() = runTest(dispatcher) {
        var asked = 0
        val layer = MapPackTransit(
            scope = this,
            fetch = { _, _, _, _ ->
                PackTransitSlice(
                    stops = listOf(
                        local.phantom.companion.core.sensor.nav.TransitStop(
                            id = 1L, lat = 49.83, lon = 24.02, name = "Пакетна",
                            tags = mapOf("highway" to "bus_stop"), routes = "",
                        ),
                    ),
                    rails = emptyList(),
                )
            },
            liveStops = { _, _, _, _ -> asked++; listOf(liveStop()) },
            io = dispatcher,
        )
        layer.onCamera(lviv, zoom = 14.0)
        advanceUntilIdle()

        assertEquals("офлайн відповів — назовні нічого не йде", 0, asked)
        assertEquals(1, features(layer.stopsGeoJson.value))
    }

    /** Та сама рамка вдруге — запит не повторюється. */
    @Test
    fun `повернення на ту саму рамку не коштує другого запиту`() = runTest(dispatcher) {
        var asked = 0
        val layer = MapPackTransit(
            scope = this,
            fetch = { _, _, _, _ -> PackTransitSlice(emptyList(), emptyList()) },
            liveStops = { _, _, _, _ -> asked++; listOf(liveStop()) },
            io = dispatcher,
        )
        val elsewhere = lviv.copy(southLat = 50.38, northLat = 50.48, westLon = 30.40, eastLon = 30.58)
        layer.onCamera(lviv, 14.0); advanceUntilIdle()
        layer.onCamera(elsewhere, 14.0); advanceUntilIdle()
        layer.onCamera(lviv, 14.0); advanceUntilIdle()

        assertEquals("третій кадр — та сама рамка, відповідь мусить прийти з памʼяті", 2, asked)
        assertEquals(1, features(layer.stopsGeoJson.value))
    }

    /** Нижче порога зупинок мережу не смикаємо взагалі. */
    @Test
    fun `на оглядовому зумі мережа мовчить`() = runTest(dispatcher) {
        var asked = 0
        val layer = MapPackTransit(
            scope = this,
            fetch = { _, _, _, _ -> PackTransitSlice(emptyList(), emptyList()) },
            liveStops = { _, _, _, _ -> asked++; listOf(liveStop()) },
        )
        layer.onCamera(lviv, zoom = MapPackTransit.STOPS_MIN_ZOOM - 1.0)
        advanceUntilIdle()

        assertEquals(0, asked)
        assertEquals(0, features(layer.stopsGeoJson.value))
    }

    /**
     * Словник MOTIS ширший за наш і росте без нашої згоди. Невідоме слово
     * мусить падати в OTHER, а не в BUS: інакше шар стверджує рід, якого
     * ніхто не назвав — та сама вада, що й «закритий словник падає в брехню».
     */
    @Test
    fun `невідомий рід транспорту не вдає автобус`() {
        assertEquals(TransitMode.BUS, liveStopMode(listOf("BUS")))
        assertEquals(TransitMode.BUS, liveStopMode(listOf("COACH")))
        assertEquals(TransitMode.TRAM, liveStopMode(listOf("TRAM")))
        assertEquals(TransitMode.METRO, liveStopMode(listOf("SUBWAY")))
        assertEquals(TransitMode.RAIL, liveStopMode(listOf("LONG_DISTANCE")))
        assertEquals(TransitMode.RAIL, liveStopMode(listOf("HIGHSPEED_RAIL")))
        assertEquals(TransitMode.OTHER, liveStopMode(listOf("CABLE_CAR")))
        assertEquals(TransitMode.OTHER, liveStopMode(emptyList()))
    }

    /** Трамвай на зупинці з двома родами лишається трамваєм — його і шукають. */
    @Test
    fun `трамвай не тоне в автобусі на спільній зупинці`() {
        assertEquals(TransitMode.TRAM, liveStopMode(listOf("BUS", "TRAM")))
        assertEquals(TransitMode.METRO, liveStopMode(listOf("BUS", "TRAM", "METRO")))
    }

    /** І він мусить бути ІНШОГО кольору на склі, бо колір — це те, що видно. */
    @Test
    fun `автобус і трамвай не одного кольору`() {
        val bus = buildLiveStopsGeoJson(listOf(liveStop(modes = listOf("BUS"))))
        val tram = buildLiveStopsGeoJson(listOf(liveStop(modes = listOf("TRAM"))))
        val hex = Regex("\"colour\":\"(#[0-9A-Fa-f]{6})\"")
        assertNotEquals(
            hex.find(bus)!!.groupValues[1],
            hex.find(tram)!!.groupValues[1],
        )
    }

    /**
     * Жива половина носить ТІ САМІ ознаки, що й пакетна. Інакше шар малює
     * її, а обробник тапу мовчки не впізнає — та сама друга гілка, яку
     * забувають полагодити.
     */
    @Test
    fun `жива зупинка носить ті самі ознаки, що й пакетна`() {
        val json = buildLiveStopsGeoJson(listOf(liveStop()))
        for (key in listOf("colour", "mode", "id", "label", "name", "routes", "comfort")) {
            assertTrue("жива зупинка без ознаки $key", json.contains("\"$key\":"))
        }
        assertTrue("українська назва мусить доїхати на скло", json.contains("Хоткевича (310)"))
    }

    /** Лапки й слеші в назві не мають права розвалити колекцію. */
    @Test
    fun `назва з лапками не ламає GeoJSON`() {
        val json = buildLiveStopsGeoJson(listOf(liveStop(name = "Пл. \"Ринок\"\\1")))
        assertFalse(json.contains("\"Ринок\"\\1\""))
        assertEquals(1, features(json))
    }

    /** Невідомий рід не підписується англійським словом у українській картці. */
    @Test
    fun `рід словом мовчить там, де не знає`() {
        assertEquals("трамвай", packStopModeWord(TransitMode.TRAM.name))
        assertEquals("автобус", packStopModeWord(TransitMode.BUS.name))
        assertEquals("метро", packStopModeWord(TransitMode.METRO.name))
        assertNull(packStopModeWord(TransitMode.OTHER.name))
        assertNull(packStopModeWord("CABLE_CAR"))
    }

    /** Порожня відповідь — порожня колекція, а не «Feature» з нічого. */
    @Test
    fun `порожній перелік дає порожню колекцію`() {
        assertEquals(0, features(buildLiveStopsGeoJson(emptyList())))
    }
}
