package local.phantom.companion.feature.stream.ui.map.icons.marks

import local.phantom.companion.feature.stream.ui.map.world.WorldKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldFiguresTest {

    private fun trace(kind: WorldKind) = RecordingMarkPen().apply { WorldFigures.draw(kind)(this) }

    @Test
    fun `кожен вид має тіло в межах коробки і стоїть на землі`() {
        WorldKind.entries.forEach { kind ->
            val pen = trace(kind)
            assertTrue("${kind.id}: порожнє тіло", pen.ops >= 6)
            assertTrue("${kind.id}: за лівий край", pen.minX >= 0f)
            assertTrue("${kind.id}: за правий край", pen.maxX <= MarkBox.W)
            assertTrue("${kind.id}: за верх", pen.minY >= 0f)
            assertTrue("${kind.id}: нижче підошви", pen.maxY <= MarkBox.BASELINE)
            assertTrue("${kind.id}: висить у повітрі", pen.maxY >= 108f)
        }
    }

    @Test
    fun `нові тіла не повторюють старих — силует різний`() {
        val shapes = WorldKind.entries.associateWith { trace(it).let { p -> listOf(p.ops, p.minX, p.minY, p.maxX, p.maxY, p.tones.sorted()) } }
        assertEquals(WorldKind.entries.size, shapes.values.toSet().size)
    }

    @Test
    fun `бурштин лише як акцент, корал лише в небезпеки`() {
        val coralPlates = setOf(0xFFD93B26.toInt(), 0xFFC7373D.toInt())
        val amber = MarkPalette.AMBER_DAY
        WorldKind.entries.forEach { kind ->
            listOf(true, false).forEach { dark ->
                val plate = WorldFigures.palette(kind, dark, accent = false).of(MarkTone.PLATE)
                val coral = plate in coralPlates
                assertEquals("${kind.id}: корал поза небезпекою", kind == WorldKind.DANGER || kind == WorldKind.CRASH, coral)
                assertTrue("${kind.id}: бурштинова плашка без акценту", plate != amber)
                val danger = kind == WorldKind.DANGER || kind == WorldKind.CRASH
                assertEquals(!danger, WorldFigures.palette(kind, dark, accent = true).of(MarkTone.PLATE) == amber)
            }
        }
    }

    @Test
    fun `ідентифікатори картинок не перетинаються`() {
        val ids = WorldKind.entries.flatMap { k ->
            listOf(true, false).flatMap { d -> listOf(true, false).map { a -> WorldFigures.imageId(k, d, a) } }
        }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals("старі знаки й світ ділять картинку", MarkPlate.imageId(MarkKind.DANGER, true), WorldFigures.imageId(WorldKind.DANGER, true, false))
    }
}
