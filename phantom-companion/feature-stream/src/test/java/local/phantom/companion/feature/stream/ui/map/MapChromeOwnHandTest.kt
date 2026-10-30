package local.phantom.companion.feature.stream.ui.map

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Одна рука на всю мапу — і на те, що вона показує, і на те, чим її гортають.
 *
 * **Знайдено 30.08.** Реєстр [local.phantom.companion.feature.stream.ui.map.icons.PhantomGlyph]
 * у власному KDoc стверджує: «жодного іншого джерела гліфів у мапи нема: ні
 * системного емодзі, ні шрифту, ні drawable». На ДАНИХ це було правдою — пін
 * укриття, загрози, транспорту вже носили нашу руку. А дека, орби й чипи —
 * тобто рівно те, чим людина мапою користується — лишались на стокових
 * `material.icons`: **131 імпорт на поверхні, 0 власних у деці**.
 *
 * Родину знаків заводили саме проти різнорукості: у її KDoc написано, що доти
 * кожен пін ніс системне емодзі й разом вони читались «як набір наліпок, а не
 * як мапа». Ту хворобу вилікували на даних **і не помітили на контролях** —
 * робота зупинилась на межі, якої ніхто не бачив, а власник дивиться на цю
 * колону щодня.
 *
 * **Дві різні вимоги, і вони навмисно різні:**
 *  1. **дека й орби — тверда нуль-межа.** Це найгучніше місце мапи, воно
 *     вичищене, і повертати туди чужу руку не можна взагалі;
 *  2. **решта поверхні — ратчет.** Аркуші, панелі й списки ще носять стокові
 *     значки, і одномоментно це не переписати. Стеля лише падає.
 *
 * **Сліпі плями:**
 *  - рахує ІМПОРТИ, а не вживання: один імпорт може малювати пʼять кнопок;
 *  - повнокваліфіковане звернення (`androidx.compose.material.icons.…` прямо в
 *    коді) не побачить;
 *  - не знає, чи новий власний гліф гарний — лише те, що він наш.
 */
class MapChromeOwnHandTest {

    /** Найгучніші файли мапи: те, що людина тисне пальцем. */
    private val chromeFiles = listOf("MapChrome.kt", "MapControls.kt")

    /**
     * Стеля стокових імпортів на всій поверхні мапи. Тільки вниз.
     *
     * 30.08: було 131, стало 123 — дека перейшла на власну руку. Наступні
     * кроки: `SheetHost.kt` (17), `MapCommandSheet.kt` (10), `MapToolsCapsule.kt` (5).
     */
    private val stockCeiling = 96

    private fun mapRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        val root = dir ?: fail("корінь репозиторію не знайдено") as Nothing
        return File(root, "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map")
    }

    /** Чиста функція — тому доказ червоного не потребує справжнього файла. */
    private fun stockImportsIn(source: String): Int =
        source.lines().count { it.trimStart().startsWith("import androidx.compose.material.icons") }

    @Test
    fun `дека і орби малюються власною рукою`() {
        val offenders = chromeFiles.mapNotNull { name ->
            val f = File(mapRoot(), name)
            assertTrue("$name зник — сторож більше нічого не стереже", f.isFile)
            val n = f.readText().lines().count { it.contains("Icons.") }
            if (n > 0) "$name: $n звернень до стокових Icons." else null
        }
        assertEquals(
            "У деку мапи повернулась чужа рука. Родина PhantomGlyph уже має знаки контролів " +
                "(LAYERS, GROUND, PACKS, COMPASS, LOCATE_*) — бери звідти: " +
                "PhantomGlyph.X.asImageVector(). Якщо потрібного знака нема — намалюй його тим " +
                "самим пером у GlyphsControls.kt, а не приводь чужий.",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `стокових значків на мапі не більшає`() {
        val total = mapRoot().walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sumOf { stockImportsIn(it.readText()) }
        assertTrue(
            "стокових імпортів на поверхні мапи $total при стелі $stockCeiling — стеля лише падає. " +
                "Новий значок бери з PhantomGlyph або малюй пером; якщо стелю треба підняти, " +
                "це окреме рішення, і його видно в RatchetCeilingLedgerTest.",
            total <= stockCeiling,
        )
    }

    /** ДОКАЗ ЧЕРВОНОГО: лічильник мусить бачити саме той рядок, через який усе почалось. */
    @Test
    fun `лічильник бачить стоковий імпорт`() {
        assertEquals(1, stockImportsIn("import androidx.compose.material.icons.outlined.Layers"))
        assertEquals(1, stockImportsIn("    import androidx.compose.material.icons.Icons"))
        assertEquals(0, stockImportsIn("import local.phantom.companion.feature.stream.ui.map.icons.PhantomGlyph"))
    }
}
