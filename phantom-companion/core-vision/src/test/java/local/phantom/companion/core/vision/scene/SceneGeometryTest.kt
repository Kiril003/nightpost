package local.phantom.companion.core.vision.scene

import org.junit.Assert.assertEquals
import org.junit.Test

class SceneGeometryTest {

    @Test
    fun rotationSwapsSidesOnlyOnQuarterTurns() {
        assertEquals(640, uprightWidth(640, 480, 0))
        assertEquals(480, uprightHeight(640, 480, 0))

        assertEquals(480, uprightWidth(640, 480, 90))
        assertEquals(640, uprightHeight(640, 480, 90))

        assertEquals(640, uprightWidth(640, 480, 180))
        assertEquals(640, uprightWidth(640, 480, 360))

        // Камери віддають і 270, і відʼємні. Звичайний `%` дає -90 і мовчки
        // лишає сторони неперевернутими.
        assertEquals(480, uprightWidth(640, 480, 270))
        assertEquals(480, uprightWidth(640, 480, -90))
    }

    @Test
    fun overlapIsZeroForStrangersAndOneForTwins() {
        val a = VisionBox(0f, 0f, 10f, 10f)
        assertEquals(1f, a.overlapWith(a), 0.001f)
        assertEquals(0f, a.overlapWith(VisionBox(50f, 50f, 60f, 60f)), 0.001f)
        assertEquals(0f, a.overlapWith(VisionBox(10f, 10f, 20f, 20f)), 0.001f)
    }

    @Test
    fun distanceIsZeroInsideAndGrowsOutside() {
        val box = VisionBox(0f, 0f, 10f, 10f)
        assertEquals(0f, box.distanceTo(5f, 5f), 0.001f)
        assertEquals(0f, box.distanceTo(0f, 0f), 0.001f)
        assertEquals(5f, box.distanceTo(15f, 5f), 0.001f)
    }

    @Test
    fun smoothingLandsBetweenNotOnTop() {
        val from = VisionBox(0f, 0f, 10f, 10f)
        val to = VisionBox(10f, 10f, 20f, 20f)
        val half = from.towards(to, 0.5f)
        assertEquals(5f, half.left, 0.001f)
        assertEquals(from, from.towards(to, 0f))
        assertEquals(to, from.towards(to, 1f))
    }
}
