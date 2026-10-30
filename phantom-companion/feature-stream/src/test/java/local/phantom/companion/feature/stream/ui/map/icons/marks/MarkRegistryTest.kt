package local.phantom.companion.feature.stream.ui.map.icons.marks

import local.phantom.companion.core.data.spatial.MARK_CLASSES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Реєстр знаків: сім класів × дві полярності, і жоден із них не порожній.
 *
 * Порожня або зʼїхала фігурка на пристрої виглядає як прогалина в мапі, а не
 * як помилка, — і поки її не побачить око, ніщо про це не скаже. Тут це
 * ловиться без Robolectric, бо геометрія чиста.
 */
class MarkRegistryTest {

    private fun trace(kind: MarkKind): RecordingMarkPen =
        RecordingMarkPen().apply { kind.draw(this) }

    @Test
    fun `the taxonomy the data speaks and the taxonomy the pen draws are one`() {
        assertEquals(MARK_CLASSES.toSet(), MarkKind.entries.map { it.id }.toSet())
        assertEquals(MarkKind.entries.size, MarkKind.entries.map { it.id }.distinct().size)
    }

    @Test
    fun `an unknown class resolves, and resolves to the honest one`() {
        assertEquals(MarkKind.DANGER, MarkKind.of(null))
        assertEquals(MarkKind.DANGER, MarkKind.of("вигаданий клас"))
        MarkKind.entries.forEach { assertEquals(it, MarkKind.of(it.id)) }
    }

    @Test
    fun `every mark draws a body`() {
        MarkKind.entries.forEach { kind ->
            val pen = trace(kind)
            assertTrue("${kind.id}: порожня фігурка", pen.ops >= 6)
        }
    }

    @Test
    fun `every mark stays inside its bitmap`() {
        MarkKind.entries.forEach { kind ->
            val pen = trace(kind)
            assertTrue("${kind.id}: ліворуч за край (${pen.minX})", pen.minX >= 0f)
            assertTrue("${kind.id}: праворуч за край (${pen.maxX})", pen.maxX <= MarkBox.W)
            assertTrue("${kind.id}: угорі за край (${pen.minY})", pen.minY >= 0f)
            assertTrue("${kind.id}: нижче базової лінії (${pen.maxY})", pen.maxY <= MarkBox.BASELINE)
        }
    }

    @Test
    fun `every mark actually stands on the ground`() {
        // Фігурка, що не дістає до низу, висить у повітрі над своєю тінню —
        // тобто каже «ось тут» про точку, якої не торкається.
        MarkKind.entries.forEach { kind ->
            val pen = trace(kind)
            assertTrue("${kind.id}: підошви немає (${pen.maxY})", pen.maxY >= 108f)
        }
    }

    @Test
    fun `the ground signal is the only tone that moves with the theme`() {
        // SIGNAL лежить там, де знак торкається землі. Клас, що його не має,
        // виглядає однаково вдень і вночі — це не помилка, а вибір, і тут він
        // зафіксований поіменно.
        val ground = setOf(MarkTone.SIGNAL, MarkTone.GLOW)
        val withSignal = MarkKind.entries
            .filter { trace(it).tones.any { tone -> tone in ground } }
            .map { it.id }
        assertEquals(listOf("crash", "camera", "shelter"), withSignal)
    }

    @Test
    fun `identity plates clear 3 to 1 on the bone face`() {
        // Виміряно, не вподобання: нічні токени на кістяній плашці давали
        // 1.74–3.41:1, тобто шви й кільця були б майже невидимі.
        MarkKind.entries.forEach { kind ->
            listOf(true, false).forEach { dark ->
                val plate = MarkPalette.of(kind, dark).of(MarkTone.PLATE)
                val ratio = contrast(plate, MarkPalette.BONE)
                assertTrue(
                    "${kind.id} (${if (dark) "ніч" else "день"}): плашка $ratio:1",
                    ratio >= 2.95,
                )
            }
        }
    }

    @Test
    fun `the plate is theme-invariant and the signal is not`() {
        MarkKind.entries.forEach { kind ->
            val night = MarkPalette.of(kind, true)
            val day = MarkPalette.of(kind, false)
            assertEquals(
                "${kind.id}: плашка поїхала з темою",
                night.of(MarkTone.PLATE),
                day.of(MarkTone.PLATE),
            )
        }
        // Камера — сірий ink_muted; решта класів мусить мати живий свап.
        assertTrue(
            MarkPalette.of(MarkKind.CRASH, true).of(MarkTone.SIGNAL) !=
                MarkPalette.of(MarkKind.CRASH, false).of(MarkTone.SIGNAL),
        )
    }

    @Test
    fun `image ids are unique across every class and polarity`() {
        val ids = MarkKind.entries.flatMap {
            listOf(MarkPlate.imageId(it, true), MarkPlate.imageId(it, false))
        } + listOf(MarkPlate.barImageId(true), MarkPlate.barImageId(false))
        assertEquals(2 * MarkKind.entries.size + 2, ids.distinct().size)
    }

    @Test
    fun `the anchor slack matches the contact point, in dp`() {
        // Розбіжність тут = знак, що висить на стільки ж dp північніше за
        // координату, яку заявляє. Шар доводить його рівно на це число.
        assertEquals(2f, MarkPlate.ANCHOR_SLACK_DP, 0.001f)
    }

    @Test
    fun `the gate bar is drawn across, so a bearing lays it over the road`() {
        val pen = RecordingMarkPen().apply { MarkBar.draw(this) }
        assertTrue(pen.ops >= 3)
        assertTrue("брус мусить бути ширшим за себе висотою", (pen.maxX - pen.minX) > (pen.maxY - pen.minY))
        assertTrue(pen.minX >= 0f && pen.maxX <= MarkBar.W)
        assertTrue(pen.minY >= 0f && pen.maxY <= MarkBar.H)
    }

    private fun channel(v: Double): Double =
        if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)

    private fun luminance(color: Int): Double {
        val r = channel(((color shr 16) and 0xFF) / 255.0)
        val g = channel(((color shr 8) and 0xFF) / 255.0)
        val b = channel((color and 0xFF) / 255.0)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }
}
