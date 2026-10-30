package local.phantom.companion.core.geo

import local.phantom.companion.core.geo.pmtiles.DirectoryEntry
import local.phantom.companion.core.geo.pmtiles.PmTilesDirectory
import local.phantom.companion.core.geo.pmtiles.TileCoord
import local.phantom.companion.core.geo.pmtiles.TileId
import local.phantom.companion.core.geo.pmtiles.Varint
import local.phantom.companion.core.geo.pmtiles.VarintReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Тести розбору PMTiles — формату, що прибирає тайловий сервер як клас.
 *
 * Практична вага саме для нас: той самий файл, з якого карта читається
 * range-запитами з мережі, лежить на диску й дає повністю офлайнову карту.
 * Під РЕБ це принципово — глушити нема чого, бо мережі в контурі немає.
 *
 * Тому перевірка тут не «функція не кинула виняток», а зустрічна: беремо
 * структуру, серіалізуємо її кодом модуля, читаємо назад і вимагаємо збігу
 * байт-у-байт. Помилка в дельта-кодуванні або в кривій Гільберта інакше
 * спливе аж на реальному архіві в 60 ГБ, де її вже не локалізувати.
 */
class PmTilesTest {

    // ————————————————————————————————————————————————————————————————
    // Крива Гільберта
    // ————————————————————————————————————————————————————————————————

    @Test
    fun `перетворення z-x-y в ідентифікатор оборотне на всіх зумах`() {
        // PMTiles упорядковує тайли вздовж кривої Гільберта саме тому, що
        // просторово близькі тайли лягають поруч у файлі — інакше кожен зсув
        // карти на екран коштував би окремого range-запиту.
        for (z in 0..12) {
            val side = 1 shl z
            val probes = if (side <= 8) (0 until side).toList() else listOf(0, 1, side / 3, side / 2, side - 2, side - 1)
            for (x in probes) {
                for (y in probes) {
                    val id = TileId.fromZxy(z, x, y)
                    val back = TileId.toZxy(id)
                    assertEquals("зум не відновився для $z/$x/$y", z, back.z)
                    assertEquals("x не відновився для $z/$x/$y", x, back.x)
                    assertEquals("y не відновився для $z/$x/$y", y, back.y)
                }
            }
        }
    }

    @Test
    fun `ідентифікатори унікальні в межах зуму і не перетинаються між зумами`() {
        val seen = HashSet<Long>()
        for (z in 0..6) {
            val side = 1 shl z
            for (x in 0 until side) {
                for (y in 0 until side) {
                    val id = TileId.fromZxy(z, x, y)
                    assertTrue("ідентифікатор $id повторився на $z/$x/$y", seen.add(id))
                }
            }
        }
    }

    @Test
    fun `сусідні тайли лежать поруч у файлі`() {
        // Це вся причина існування кривої Гільберта. Якби впорядкування було
        // рядковим, вертикальний сусід відстояв би на цілий рядок тайлів, і
        // прокрутка карти вниз ламала б локальність читання.
        val z = 8
        var far = 0
        var total = 0
        for (x in 100 until 116) {
            for (y in 100 until 116) {
                val id = TileId.fromZxy(z, x, y)
                for ((nx, ny) in listOf(x + 1 to y, x to y + 1)) {
                    val gap = kotlin.math.abs(TileId.fromZxy(z, nx, ny) - id)
                    total++
                    if (gap > 16) far++
                }
            }
        }
        assertTrue(
            "надто багато сусідів опинилось далеко у файлі: $far з $total",
            far < total / 4,
        )
    }

    @Test
    fun `нульовий зум має рівно один тайл`() {
        assertEquals(1L, TileId.tileCountAtZoom(0))
        assertEquals(0L, TileId.fromZxy(0, 0, 0))
        assertEquals(TileCoord(0, 0, 0), TileId.toZxy(0L))
    }

    // ————————————————————————————————————————————————————————————————
    // Varint (LEB128)
    // ————————————————————————————————————————————————————————————————

    @Test
    fun `varint зберігає значення на всій робочій області`() {
        val values = listOf(
            0L, 1L, 63L, 64L, 127L, 128L, 300L, 16_383L, 16_384L,
            1L shl 20, 1L shl 34, Int.MAX_VALUE.toLong(), Long.MAX_VALUE,
        )
        val out = ByteArrayOutputStream()
        for (v in values) Varint.write(out, v)
        val reader = VarintReader(out.toByteArray())
        for (v in values) {
            assertEquals("varint не відновив $v", v, reader.next())
        }
    }

    @Test
    fun `оголошений розмір varint збігається з фактичним`() {
        // Розмір рахується наперед при складанні каталогу; розбіжність тут
        // зсунула б усі наступні зміщення у файлі.
        for (v in listOf(0L, 127L, 128L, 16_383L, 16_384L, 1L shl 40, Long.MAX_VALUE)) {
            assertEquals("невірний розмір для $v", Varint.encode(v).size, Varint.sizeOf(v))
        }
    }

    // ————————————————————————————————————————————————————————————————
    // Каталог
    // ————————————————————————————————————————————————————————————————

    @Test
    fun `каталог переживає серіалізацію без втрат`() {
        val entries = listOf(
            DirectoryEntry(tileId = 0, offset = 0, length = 120, runLength = 1),
            DirectoryEntry(tileId = 1, offset = 120, length = 340, runLength = 3),
            DirectoryEntry(tileId = 4, offset = 460, length = 90, runLength = 1),
            DirectoryEntry(tileId = 21, offset = 550, length = 1024, runLength = 8),
        )

        val restored = PmTilesDirectory.deserialize(PmTilesDirectory.serialize(entries))

        assertEquals("кількість записів змінилась", entries.size, restored.size)
        for (i in entries.indices) {
            assertEquals("запис $i: tileId", entries[i].tileId, restored[i].tileId)
            assertEquals("запис $i: offset", entries[i].offset, restored[i].offset)
            assertEquals("запис $i: length", entries[i].length, restored[i].length)
            assertEquals("запис $i: runLength", entries[i].runLength, restored[i].runLength)
        }
    }

    @Test
    fun `пошук знаходить тайл усередині серії`() {
        // runLength > 1 означає, що один запис покриває кілька однакових тайлів
        // (типово — суцільне море або порожній квадрат). Пошук мусить це знати,
        // інакше карта матиме дірки там, де насправді все гаразд.
        val entries = listOf(
            DirectoryEntry(tileId = 10, offset = 0, length = 50, runLength = 5),
            DirectoryEntry(tileId = 30, offset = 50, length = 70, runLength = 1),
        )

        val inside = PmTilesDirectory.find(entries, 13)
        assertNotNull("тайл 13 входить у серію 10..14", inside)
        assertEquals(0L, inside!!.offset)

        assertNotNull("точний збіг має знаходитись", PmTilesDirectory.find(entries, 30))
        assertNull("тайл поза серіями не має знаходитись", PmTilesDirectory.find(entries, 20))
        assertNull("тайл після останнього запису не має знаходитись", PmTilesDirectory.find(entries, 99))
    }

    @Test
    fun `листовий вказівник не видається за тайл`() {
        // runLength = 0 — це посилання на листовий каталог. Якщо сплутати його з
        // тайлом, клієнт віддасть рендереру байти каталогу замість геометрії.
        val leaf = DirectoryEntry(tileId = 100, offset = 900, length = 256, runLength = 0)
        assertTrue("запис із runLength=0 — листовий вказівник", leaf.isLeafPointer)
        assertTrue("листовий вказівник не покриває власний id", !leaf.covers(100))
    }

    @Test
    fun `порожній каталог не ламає розбір`() {
        val restored = PmTilesDirectory.deserialize(PmTilesDirectory.serialize(emptyList()))
        assertTrue("порожній каталог має лишитись порожнім", restored.isEmpty())
        assertNull(PmTilesDirectory.find(restored, 0))
    }
}
