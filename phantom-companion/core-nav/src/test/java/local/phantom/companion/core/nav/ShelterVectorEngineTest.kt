package local.phantom.companion.core.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShelterVectorEngineTest {

    @Test
    fun calculatesBearingAndCompassQuadrantCorrectly() {
        val engine = ShelterVectorEngine()

        // Pointing directly north
        val northBearing = engine.calculateBearing(50.450, 30.520, 50.460, 30.520)
        assertEquals(0, northBearing)
        assertEquals("Північ", engine.compassQuadrantUk(northBearing))

        // Pointing directly east
        val eastBearing = engine.calculateBearing(50.450, 30.520, 50.450, 30.535)
        assertEquals(90, eastBearing)
        assertEquals("Схід", engine.compassQuadrantUk(eastBearing))
    }

    @Test
    fun calculatesClockPositionsAccurately() {
        val engine = ShelterVectorEngine()

        // Facing North (0°), target at East (90°) -> 3 o'clock
        assertEquals("на 3 годин", engine.calculateClockPosition(0.0, 90.0))

        // Facing North (0°), target at North (0°) -> 12 o'clock
        assertEquals("на 12 годин", engine.calculateClockPosition(0.0, 0.0))

        // Facing East (90°), target at South (180°) -> 3 o'clock
        assertEquals("на 3 годин", engine.calculateClockPosition(90.0, 180.0))
    }

    @Test
    fun generatesFullGuidanceReport() {
        val engine = ShelterVectorEngine()
        val target = ShelterVectorEngine.ShelterTarget(
            id = "sh_1",
            name = "Метро Театральна",
            lat = 50.445,
            lon = 30.518,
            type = "метро / підземне укриття",
            address = "вул. Богдана Хмельницького",
        )

        val guidance = engine.calculateVector(
            currentLat = 50.450,
            currentLon = 30.520,
            currentHeadingDeg = 0f, // heading north
            target = target,
        )

        assertTrue(guidance.distanceMeters > 0)
        assertEquals("на 6 годин", guidance.clockPositionUk)
        assertTrue(guidance.instructionUk.contains("Метро Театральна"))
        assertTrue(guidance.instructionUk.contains("хв"))
    }
}
