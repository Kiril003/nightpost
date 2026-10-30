package local.phantom.companion.core.geo.cameras

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Перенесено з `nav/NavEngineTest` (перепис мапи 24.09): той файл перевіряв ще
 * два мертві рушії — `TurnByTurnGuidanceEngine` і `OfflineGraphRouter`, які
 * ніхто не кликав поза тестом і які знято. Радар безпеки живий — його кличе
 * `MapLiveSession`, тож його тест лишається.
 */
class SafetyRadarEngineTest {

    @Test
    fun `safety radar triggers alerts within 300m radius`() {
        val hazard = SafetyHazard(
            id = "cam_1",
            lat = 50.4505,
            lon = 30.5238,
            title = "Камера швидкості",
            type = HazardType.SPEED_CAMERA,
            speedLimitKmh = 50,
        )

        val radar = SafetyRadarEngine(listOf(hazard), alertRadiusMeters = 300.0)
        val alerts = radar.evaluate(50.4501, 30.5234)

        assertEquals(1, alerts.size)
        assertEquals("Камера швидкості", alerts[0].title)
        assertTrue(alerts[0].audioPrompt.contains("50 кілометрів"))
    }
}
