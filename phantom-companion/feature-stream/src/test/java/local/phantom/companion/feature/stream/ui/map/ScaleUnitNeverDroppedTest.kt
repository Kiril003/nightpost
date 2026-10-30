package local.phantom.companion.feature.stream.ui.map

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Число без одиниці — гірше за відсутнє число.
 *
 * **Знайдено на склі 31.08.** Відсунувши мапу, я побачив у смузі масштабу
 * голе **«20000.0»**. Одиниця не зникла з логіки — `fmtDistance` завжди її
 * додає; її **зрізала розкладка**: рядок «20000.0 км» не вміщався в платівку
 * приладів (`PLATE_MAX` 176 dp, з якої на текст лишається менше третини), і
 * хвіст обрізало.
 *
 * Наслідок гірший за порожнє місце: «20000.0» **виглядає як показник**. Людина
 * читає число й не знає, чи то метри, чи кілометри, чи хвилини — а мапа при
 * цьому не зізнається, що щось приховала.
 *
 * Лікування у два кроки, і цей сторож стереже перший:
 *  1. **коротший рядок** — десятина зникає на сотнях кілометрів, бо десята
 *     частка кілометра на двадцяти тисячах не означає нічого (тут);
 *  2. **стеля віддалення**, щоб такі числа взагалі не зʼявлялись — це камера,
 *     файл Чата 4, запитано окремо.
 *
 * **Сліпа пляма, названа прямо:** тест міряє ДОВЖИНУ рядка, а не його ширину
 * на екрані. Він не побачить обрізання, якщо хтось звузить платівку або
 * збільшить кегель. Справжня межа — ширина в dp при масштабі шрифту 1,15, і
 * вона доводиться лише кадром.
 */
class ScaleUnitNeverDroppedTest {

    /** Найдовше, що влазить у платівку приладів поруч зі смугою — заміряно на склі. */
    private val maxChars = 10

    @Test
    fun `масштаб ніколи не лишається без одиниці`() {
        val ladder = listOf(50.0, 900.0, 1_000.0, 3_200.0, 99_000.0, 100_000.0, 5_000_000.0, 20_000_000.0)
        ladder.forEach { m ->
            val s = fmtDistance(m)
            assertTrue("«$s» ($m м) не має одиниці — саме так на екрані зʼявилось голе 20000.0", s.any { it.isLetter() })
            assertTrue("«$s» не має пробілу між числом і одиницею", s.contains(' '))
        }
    }

    /**
     * Довжина — не педантизм: саме через неї одиниця й полетіла. Двадцять тисяч
     * кілометрів мусять писатись так само коротко, як три з половиною.
     */
    @Test
    fun `найбільші числа лишаються короткими`() {
        listOf(5_000_000.0, 20_000_000.0, 40_000_000.0).forEach { m ->
            val s = fmtDistance(m)
            assertTrue("«$s» — $${s.length} знаків при стелі $maxChars; хвіст зріже платівка", s.length <= maxChars)
            assertTrue("«$s» не сміє нести десятину на такій відстані", !s.contains(','))
        }
    }

    /** Локалізований різновид мусить брати одиницю з ресурсів у КОЖНІЙ гілці. */
    @Test
    fun `жодна гілка локалізованого форматувальника не лишає число без одиниці`() {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        val root = dir ?: fail("корінь репозиторію не знайдено") as Nothing
        val src = File(
            root,
            "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map/MapStyleLayers.kt",
        ).readText()
        val body = src.substringAfter("internal fun fmtDistance(res:").substringBefore("internal fun fmtDuration(res:")
        val formats = Regex("\"([^\"]*%[.0-9]*[fds][^\"]*)\"").findAll(body).map { it.groupValues[1] }.toList()
        assertTrue("не знайшов жодного формату — тест осліп, а не полагодився", formats.isNotEmpty())
        formats.forEach {
            assertTrue("формат «$it» друкує число без одиниці", it.contains("%s") || it.contains("\${"))
        }
    }
}
