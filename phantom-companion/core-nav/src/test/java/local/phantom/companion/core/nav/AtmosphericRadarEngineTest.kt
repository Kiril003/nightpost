package local.phantom.companion.core.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AtmosphericRadarEngineTest {

    @Test
    fun classifiesClearAtmosphere() {
        val engine = AtmosphericRadarEngine()
        val state = engine.evaluateAtmosphere(
            lat = 50.450,
            lon = 30.520,
            temperatureC = 21.5,
            precipitationMmH = 0.0,
            windKmh = 12.0,
            windDeg = 270, // West
        )

        assertEquals(AtmosphericRadarEngine.PrecipitationType.CLEAR, state.precipitationType)
        assertEquals(0, state.reflectivityDbz)
        assertEquals("Західний", state.windCompassUk)
        assertNull(state.activeMapLayer)
        assertTrue(state.briefUk.contains("22°C"))
    }

    @Test
    fun classifiesModerateRainAndRadarLayer() {
        val engine = AtmosphericRadarEngine()
        val state = engine.evaluateAtmosphere(
            lat = 50.450,
            lon = 30.520,
            temperatureC = 14.0,
            precipitationMmH = 4.5,
            windKmh = 25.0,
            windDeg = 180, // South
        )

        assertEquals(AtmosphericRadarEngine.PrecipitationType.MODERATE_RAIN, state.precipitationType)
        assertTrue(state.reflectivityDbz > 30)
        assertEquals("layer_radar_rain_moderate", state.activeMapLayer)
        assertEquals("Південний", state.windCompassUk)
        assertTrue(state.briefUk.contains("Помірний дощ"))
    }

    /**
     * Напрямку не назвали — його немає ні в полі, ні в реченні.
     *
     * Єдиний виклик у продукті передавав сюди сталу `270`, бо клієнт
     * Open-Meteo не питав `wind_direction_10m`: бриф казав «вітер Західний» у
     * будь-яку погоду й на будь-якій широті, і його читали вголос як вимір.
     * Перевіряється саме РЕЧЕННЯ: поле легко лишити порожнім і водночас
     * зібрати румб у текст.
     */
    @Test
    fun unknownWindDirectionLeavesTheBriefWithoutOne() {
        val engine = AtmosphericRadarEngine()
        val state = engine.evaluateAtmosphere(
            lat = 50.450,
            lon = 30.520,
            temperatureC = 21.5,
            precipitationMmH = 0.0,
            windKmh = 12.0,
            windDeg = null,
        )

        assertNull(state.windCompassUk)
        assertNull(state.windDirectionDeg)
        val named = listOf(
            "Північний", "Північно-Східний", "Східний", "Південно-Східний",
            "Південний", "Південно-Західний", "Західний", "Північно-Західний",
        ).filter { state.briefUk.contains(it) }
        assertTrue("бриф назвав невиміряний румб $named: ${state.briefUk}", named.isEmpty())
        assertTrue("швидкість вітру зникла разом із напрямком", state.briefUk.contains("12 км/год"))
    }

    @Test
    fun classifiesThunderstormByWmoCode() {
        val engine = AtmosphericRadarEngine()
        val state = engine.evaluateAtmosphere(
            lat = 50.450,
            lon = 30.520,
            temperatureC = 19.0,
            precipitationMmH = 15.0,
            windKmh = 45.0,
            windDeg = 90,
            weatherCodeWmo = 95, // Thunderstorm
        )

        assertEquals(AtmosphericRadarEngine.PrecipitationType.THUNDERSTORM, state.precipitationType)
        assertEquals("layer_radar_storm", state.activeMapLayer)
        assertTrue(state.briefUk.contains("Гроза"))
    }
}
