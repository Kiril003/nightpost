package local.phantom.companion.feature.stream.ui.map

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Мапа під замком мусить лишатися мапою.
 *
 * Проба відкриває обʼємність на 14 днів, тож на пʼятнадцятий рельєф зникає в
 * людини, яка вже звикла до нього. Пласка мапа при цьому веде й попереджає так
 * само — інакше замок навчив би, що застосунок ламається.
 */
class MapDepthFirewallTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("repo root with feature-stream/ not found") as Nothing
    }

    /**
     * ATLAS-0b розвів хост і композитор: install-виклики переїхали в
     * `MapGroundContributions`, префетч будівель лишився серед слухачів мапи.
     * Сторож читає всі три файли — перейменування каталогу тепер валить тест,
     * а не тихо звільняє платні шари з-під воріт.
     */
    private fun canvasLines(): List<String> = listOf(
        "MapGroundContributions.kt",
        "MapCanvas.kt",
        "MapCanvasEffects.kt",
    ).flatMap { name ->
        val file = File(
            repoRoot(),
            "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map/$name",
        )
        assertTrue("$name не знайдено — перевірка вже нічого не боронить", file.isFile)
        // Коментар і імпорт не малюють нічого; рахувати їх викликами означало б
        // вимагати ворота навколо рядка `import`.
        file.readLines().filterNot {
            val t = it.trimStart()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("import ")
        }
    }

        // `terrainDepth` у хості, `i.terrainDepth` у контрибуціях — одне поле,
    // дві адреси; підрядок ловить обидві.
    private val guard = "terrainDepth"

    /**
     * Ворота мусять стояти впритул до виклику — на тому ж рядку або на
     * попередньому. Далі шукати не можна: `if` за десять рядків вище нічого не
     * доводить, бо між ним і викликом може закритися будь-який блок.
     */
    private fun guarded(lines: List<String>, call: String): Boolean {
        val sites = lines.withIndex().filter { it.value.contains(call) }
        return sites.isNotEmpty() && sites.all { (i, line) ->
            line.contains(guard) || lines.getOrNull(i - 1)?.contains(guard) == true
        }
    }

    @Test
    fun `every paid depth call sits behind the licence guard`() {
        val lines = canvasLines()
        val unguarded = MapDepth.PAID_CALLS.filterNot { guarded(lines, it) }

        assertEquals(
            "платна глибина малюється без ключа: " + unguarded.joinToString(", ") +
                "\n\nПроба відкриває її на 14 днів. Виклик поза воротами означає, " +
                "що на пʼятнадцятий день людина далі отримує те, за що не заплатила.",
            emptyList<String>(),
            unguarded,
        )
    }

    @Test
    fun `the flat map is never asked about the licence`() {
        val lines = canvasLines()
        val captured = MapDepth.FREE_CALLS.filter { call ->
            lines.any { it.contains(call) && it.contains(guard) }
        }

        assertEquals(
            "воротами накрили те, чим мапа веде й попереджає: " +
                captured.joinToString(", "),
            emptyList<String>(),
            captured,
        )
    }

    @Test
    fun `the free half of the map is actually present in the canvas`() {
        // Без цього попередня перевірка проходила б і на порожньому файлі.
        val lines = canvasLines()
        val missing = MapDepth.FREE_CALLS.filterNot { call -> lines.any { it.contains(call) } }

        assertEquals(
            "перейменований шар тихо вивів би себе з-під перевірки: " +
                missing.joinToString(", "),
            emptyList<String>(),
            missing,
        )
    }

    @Test
    fun `paid depth and the free map do not overlap`() {
        assertTrue(
            "той самий шар оголошено і платним, і вільним",
            (MapDepth.PAID_CALLS intersect MapDepth.FREE_CALLS).isEmpty(),
        )
        assertEquals(3, MapDepth.PAID_CALLS.size)
    }

    @Test
    fun `the building prefetch does not run for a locked device`() {
        // Overpass-запити коштують мобільного трафіку й батареї. Замок, який
        // лише не малює, але далі качає тайли, — це рахунок без товару.
        val lines = canvasLines()
        assertTrue(
            "виклик префетчу зник — перевірка застаріла",
            lines.any { it.contains("crystalFeed.onViewport") },
        )
        assertTrue("місто далі качається без ключа", guarded(lines, "crystalFeed.onViewport"))
    }
}
