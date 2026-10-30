package local.phantom.companion.feature.stream.ui.map.transit

import java.io.File
import local.phantom.companion.core.data.transit.TransitOrigin
import local.phantom.companion.core.data.transit.TransitPlane
import local.phantom.companion.core.data.transit.TransitVehicle
import local.phantom.companion.core.data.transit.raptor.TransitMode
import local.phantom.companion.core.data.transit.surface.SurfaceDoc
import local.phantom.companion.core.data.transit.surface.SurfaceFeed
import local.phantom.companion.core.data.transit.surface.SurfaceHeadway
import local.phantom.companion.core.data.transit.surface.SurfaceLine
import local.phantom.companion.core.data.transit.surface.SurfaceStop
import local.phantom.companion.core.net.transit.VehicleOccupancy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Польовий вердикт 23.08: власник не зустрів ЖОДНОЇ машини з тих, що їхали по
 * його мапі. Пігулки малювала модель за розкладом — тобто мапа обіцяла
 * тролейбус, до якого неможливо підійти.
 *
 * Ці перевірки тримають вирок: рухома мітка лише зі спостереженого фіда,
 * розклад лишається розкладом, а картка машини не носить сирої телеметрії.
 */
class TransitGlassLawTest {

    private fun vehicle(
        id: String,
        origin: TransitOrigin,
        lat: Double = 50.4501,
        lon: Double = 30.5234,
        route: String? = "36",
        mode: TransitMode = TransitMode.TROLLEYBUS,
    ) = TransitVehicle(
        vehicleId = id,
        tripId = null,
        routeId = route?.let { "r-$it" },
        routeLabel = route,
        lat = lat,
        lon = lon,
        bearingDegrees = 90f,
        occupancy = VehicleOccupancy.UNKNOWN,
        fixEpochMs = 1_700_000_000_000L,
        origin = origin,
        mode = mode,
    )

    private fun kyivModel() = SurfaceFeed(
        SurfaceDoc(
            city = "Київ",
            serviceStartSec = 0,
            serviceEndSec = 86_399,
            lines = listOf(
                SurfaceLine(
                    ref = "36",
                    mode = "trolleybus",
                    name = "Палац Спорту → Троєщина",
                    headway = SurfaceHeadway(peak = 300, day = 600, evening = 900),
                    stops = listOf(
                        SurfaceStop("s1", "Палац Спорту", 50.4400, 30.5200),
                        SurfaceStop("s2", "Троєщина", 50.5100, 30.6000),
                    ),
                    hopSeconds = listOf(1_200),
                    shape = "yrxlIcytdEuAmB",
                ),
            ),
        ),
    )

    @Test
    fun `only a live feed may put anything on the glass`() {
        assertTrue(feedsGlassFleet(TransitPlane.LIVE_FEED))
        listOf(TransitPlane.SIMULATED_MODEL, TransitPlane.STATIC_SCHEDULE, TransitPlane.NONE)
            .forEach { plane ->
                assertTrue("ярус $plane знову годує скло", !feedsGlassFleet(plane))
            }
    }

    @Test
    fun `a modelled fleet renders zero pills`() {
        val fleet = listOf(
            vehicle("sim_36_trolleybus_1", TransitOrigin.SIMULATED),
            vehicle("sim_36_trolleybus_2", TransitOrigin.SIMULATED),
        )
        assertEquals(emptyList<TransitVehicle>(), glassFleet(fleet))
    }

    @Test
    fun `an observed fix keeps its pill`() {
        val live = vehicle("lviv_7", TransitOrigin.LIVE_FEED)
        val fleet = listOf(live, vehicle("sim_1", TransitOrigin.SIMULATED))
        assertEquals(listOf(live), glassFleet(fleet))
    }

    /**
     * Без живого фіда репозиторію не існує — отже, немає й циклу, що кожні
     * 20 секунд перераховує позиції, яких ніхто не зустріне.
     */
    @Test
    fun `no live feed means no repository and no polling loop`() {
        val block = mapScreen()
            .substringAfter("val transitRepository = remember(")
            .substringBefore("val lifecycleOwner")
        assertTrue("репозиторій більше не привʼязаний до живого фіда", block.contains("transitFeed?.let"))
        assertTrue(
            "модельний флот знову підключено до опитування позицій",
            !block.contains("surfaceFeed =") && !block.contains("metroFeed ="),
        )
    }

    @Test
    fun `the hub offers a fleet tab only under a live feed`() {
        val live = hubTabs(TransitPlane.LIVE_FEED, modelCovers = false, hasMetro = false)
        assertEquals(listOf(HubTab.FLEET), live)

        val model = hubTabs(TransitPlane.SIMULATED_MODEL, modelCovers = true, hasMetro = true)
        assertEquals(listOf(HubTab.NEARBY, HubTab.ROUTES, HubTab.METRO), model)
        assertTrue("розклад заявив живий флот", HubTab.FLEET !in model)
    }

    @Test
    fun `outside the modelled network the hub offers nothing to click`() {
        assertEquals(
            emptyList<HubTab>(),
            hubTabs(TransitPlane.NONE, modelCovers = false, hasMetro = false),
        )
    }

    @Test
    fun `the card shows a number and nothing it cannot back up`() {
        val facts = transitVehicleFacts(
            vehicle = vehicle("lviv_36", TransitOrigin.LIVE_FEED, lat = 49.8397, lon = 24.0297),
            routePrefix = "№",
            surface = kyivModel(),
        )
        assertEquals("№36", facts.routeLabel)
        assertNull("напрямок вигадано за збігом номера за 500 км", facts.direction)
        assertNull("кнопка лінії веде в порожнечу", facts.line)
    }

    @Test
    fun `where the model describes the city the card gains direction and a line`() {
        val facts = transitVehicleFacts(
            vehicle = vehicle("kyiv_36", TransitOrigin.LIVE_FEED, lat = 50.4700, lon = 30.5500),
            routePrefix = "№",
            surface = kyivModel(),
        )
        assertEquals("Палац Спорту → Троєщина", facts.direction)
        assertEquals("36", facts.line?.ref)
        assertEquals("yrxlIcytdEuAmB", facts.line?.shape)
    }

    @Test
    fun `a live vehicle with only a route id shows no number at all`() {
        val v = vehicle("lviv", TransitOrigin.LIVE_FEED, lat = 49.8397, lon = 24.0297).copy(routeId = "2285", routeLabel = null)
        assertEquals("", transitVehicleFacts(v, "№", kyivModel()).routeLabel)
    }

    @Test
    fun `an unnumbered vehicle gets no orphan sign`() {
        val facts = transitVehicleFacts(
            vehicle = vehicle("x", TransitOrigin.LIVE_FEED, route = null),
            routePrefix = "№",
            surface = kyivModel(),
        )
        assertEquals("", facts.routeLabel)
        assertNull(facts.line)
    }

    /** Сира телеметрія не має вертатись у картку іншими руками. */
    @Test
    fun `the vehicle card carries no raw telemetry`() {
        val card = File(repoRoot(), "$MAP/MapChromeParts.kt").readText()
            .substringAfter("internal fun TransitVehicleCard(")
            .substringBefore("\nprivate val DOT")
        listOf("speedKmh", "occupancy", "bearingDegrees", "map_transit_course", "map_transit_load")
            .forEach { raw ->
                assertTrue("у картку машини повернулось «$raw»", !card.contains(raw))
            }
    }

    private fun mapScreen(): String = File(repoRoot(), "$MAP/MapScreen.kt").readText()

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("корінь репозиторію не знайдено") as Nothing
    }

    private companion object {
        const val MAP = "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map"
    }
}
