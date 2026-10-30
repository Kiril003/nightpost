package local.phantom.companion.feature.stream.ui.map.indoor

import java.io.File
import local.phantom.companion.core.geo.indoor.BuildingEntrance
import local.phantom.companion.core.geo.indoor.EntranceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ПІДПИСИ ВХОДІВ НЕ ЗАСИПАЮТЬ ЕКРАН.
 *
 * Вердикт тестувальника 11.09 і кадр: перехрестя, десятки плашок «Вхід /
 * Головний вхід / Аварійний вихід» поверх усього. `entrance=yes` в OSM стоїть
 * на дверях кожного підʼїзду, а план засівався квадратом 900×900 px — на
 * міському зумі це квартал.
 */
class EntranceLabelRuleTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "feature-stream").isDirectory }
        ?: error("не знайшла кореня сховища: жоден предок не має теки feature-stream")

    private val lat = 50.4501
    private val lon = 30.5234

    /** Зміщення на схід у метрах — щоб ставити двері на відомій відстані. */
    private fun east(metres: Double) = lon + metres / (111_320.0 * Math.cos(Math.toRadians(lat)))

    private fun door(kind: EntranceKind, metres: Double, id: String = "d$metres") =
        BuildingEntrance(id = id, lat = lat, lon = east(metres), kind = kind)

    @Test
    fun `на міському зумі жодного підпису входу`() {
        val doors = (1..40).map { door(EntranceKind.MAIN, it * 1.0, "d$it") }
        assertTrue(
            "зум 16 — це квартал, а не двері",
            EntranceLabelRule.visible(doors, zoom = 16.0, lat, lon).isEmpty(),
        )
    }

    @Test
    fun `звичайний вхід зʼявляється лише з вісімнадцятого`() {
        val doors = listOf(door(EntranceKind.MAIN, 10.0))
        assertTrue(EntranceLabelRule.visible(doors, zoom = 17.9, lat, lon).isEmpty())
        assertEquals(1, EntranceLabelRule.visible(doors, zoom = 18.0, lat, lon).size)
    }

    @Test
    fun `аварійний вихід видно на зум раніше за решту`() {
        val doors = listOf(
            door(EntranceKind.EMERGENCY, 10.0, "emg"),
            door(EntranceKind.MAIN, 10.0, "main"),
        )
        val seen = EntranceLabelRule.visible(doors, zoom = 17.0, lat, lon)
        assertEquals(listOf("emg"), seen.map { it.id })
        assertTrue(EntranceLabelRule.MIN_ZOOM_EMERGENCY < EntranceLabelRule.MIN_ZOOM)
    }

    @Test
    fun `двері сусіднього кварталу не підписуються`() {
        val doors = listOf(
            door(EntranceKind.MAIN, 10.0, "своя"),
            door(EntranceKind.MAIN, 250.0, "чужа"),
        )
        assertEquals(listOf("своя"), EntranceLabelRule.visible(doors, 19.0, lat, lon).map { it.id })
    }

    @Test
    fun `торговий центр не дає більше плашок, ніж стеля`() {
        val doors = (1..40).map { door(EntranceKind.MAIN, it * 1.0, "d$it") }
        val seen = EntranceLabelRule.visible(doors, zoom = 19.0, lat, lon)
        assertEquals(EntranceLabelRule.MAX_LABELS, seen.size)
        assertEquals("d1", seen.first().id)
    }

    @Test
    fun `шар входів малює лише те, що дозволило правило`() {
        val src = File(
            root,
            "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map/indoor/IndoorFloorOverlay.kt",
        ).readText()
        assertTrue("шар входів не знайдено", src.isNotBlank())
        assertTrue(
            "шар мусить питати EntranceLabelRule про кожен зум",
            src.contains("EntranceLabelRule.visible("),
        )
        assertFalse(
            "цикл по всіх входах повертає повінь плашок",
            src.contains("for (ent in entrances)"),
        )
    }
}
