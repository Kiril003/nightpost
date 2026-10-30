package local.phantom.companion.feature.stream.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File
import org.junit.Test

/**
 * Мапа без мережі мусить лишатись мапою, а не кольоровими плямами.
 *
 * Стиль тягнув гліфи підписів із гліф-сервера OpenFreeMap. Тайли теж були
 * мережеві, тож у режимі польоту не було нічого — і вада ховалась за іншою.
 * Щойно тайли стали локальними, ця стала видимою: без мережі не було б ЖОДНОЇ
 * назви — ні міста, ні вулиці.
 *
 * Ці твердження пильнують три речі, кожна з яких сама по собі повертає порожні
 * підписи: адресу гліфів у стилі, назву стека в шарах і наявність самих
 * діапазонів у пакунку.
 */
class OfflineGlyphsTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: error("не знайшов корінь дерева")
    }

    private val assets get() = File(repoRoot(), "feature-stream/src/main/assets")
    private val styles get() = File(assets, "styles").listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }

    /** Діапазони, які ми свідомо кладемо в пакунок. Див. `fonts/README.md`. */
    private val ranges = listOf(
        "0-255", "256-511", "512-767", "768-1023",
        "1024-1279", "1280-1535", "7680-7935", "8192-8447",
    )

    @Test
    fun `every style asks the package for its glyphs, not the network`() {
        assertTrue("стилів не знайдено", styles.isNotEmpty())
        for (f in styles) {
            val glyphs = Regex(""""glyphs"\s*:\s*"([^"]+)"""").find(f.readText())?.groupValues?.get(1)
            assertEquals(
                "${f.name} тягне гліфи з мережі — без неї підписів не буде взагалі",
                "asset://fonts/{fontstack}/{range}.pbf",
                glyphs,
            )
        }
    }

    @Test
    fun `the only font stack in the layers is the one we ship`() {
        // Інший стек — це 404 на кожному діапазоні, тобто підпис без тексту.
        // Той самий наслідок, що й раніше давав чужий гліф-сервер.
        val stacks = styles.flatMap { f ->
            Regex(""""text-font"\s*:\s*\[\s*"([^"]+)"""").findAll(f.readText()).map { it.groupValues[1] }
        }.toSet()
        assertEquals(setOf("Noto Sans Regular"), stacks)
    }

    @Test
    fun `each shipped range exists, under both spellings of the stack`() {
        // Дві теки навмисно: MapLibre просить шлях із відсотковим кодуванням, а
        // чи розкодовує його читач асетів назад — НЕ перевірено на склі. Поки
        // не перевірено, лежать обидві; див. fonts/README.md.
        for (dir in listOf("Noto Sans Regular", "Noto%20Sans%20Regular")) {
            for (r in ranges) {
                val f = File(assets, "fonts/$dir/$r.pbf")
                assertTrue("бракує $dir/$r.pbf — підписи з цими знаками будуть порожні", f.isFile)
                assertTrue("$dir/$r.pbf порожній", f.length() > 1024)
            }
        }
    }

    /**
     * Діапазон, що не читається, MapLibre не вважає порожнім: тайл чекає на нього
     * вічно і не малює нічого. «№» в назві аптеки стирав із мапи весь квартал.
     */
    @Test
    fun `every range a name can ask for resolves inside the package`() {
        for (dir in listOf("Noto Sans Regular", "Noto%20Sans%20Regular")) {
            val missing = (0 until 65536 step 256).map { "$it-${it + 255}" }
                .filterNot { File(assets, "fonts/$dir/$it.pbf").isFile }
            assertEquals("$dir: діапазони поза пакунком зупиняють тайл цілком", emptyList<String>(), missing)
        }
    }

    @Test
    fun `cyrillic is among them`() {
        // Найважливіший діапазон продукту, названий окремо: без нього
        // українська мапа лишається без жодної назви.
        assertTrue(ranges.contains("1024-1279"))
        assertTrue(File(assets, "fonts/Noto Sans Regular/1024-1279.pbf").length() > 100_000)
    }

    @Test
    fun `the reason both spellings ship is written down`() {
        val readme = File(assets, "fonts/README.md")
        assertTrue("зникло пояснення, чому тек дві", readme.isFile)
        assertTrue(readme.readText().contains("не перевірено на склі"))
    }
}
