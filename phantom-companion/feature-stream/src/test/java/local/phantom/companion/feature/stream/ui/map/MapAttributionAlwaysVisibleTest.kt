package local.phantom.companion.feature.stream.ui.map

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Сторож авторства OSM.
 *
 * **Історія 30.08 — 13.09.** Спершу авторство жило рядком на самій мапі
 * («платівка приладів», `MapControls.kt`), і 30.08 цей рядок ненавмисно
 * зник разом з умовою `ui.toolsExpanded` — мапа малювала дані OSM без
 * жодної атрибуції в стані спокою. Тодішній сторож перевіряв саме
 * досяжність того рядка на екрані.
 *
 * **13.09, рішення власника:** авторство прибрано з мапи цілком і лишається
 * лише в Налаштування → Про застосунок/Ліцензії. ODbL вимагає доступності
 * «reasonably calculated to make aware», а не присутності в кожному кадрі
 * мапи — окремий, завжди досяжний екран ліцензій цю умову задовольняє, і
 * саме тому телефон більше не носить рядок «© OpenStreetMap» на платівці.
 *
 * Сторож тепер стоїть на новому місці: не «рядок на мапі не зникає», а
 * «рядок у Налаштуваннях не зникає» — та сама хвороба (тихе видалення
 * атрибуції), інший орган.
 *
 * **Сліпі плями, названі чесно:**
 *  - читає РЕСУРСИ, не екран: не побачить, якщо екран ліцензій перестане
 *    показувати ці рядки, залишаючись сам технічно досяжним;
 *  - не знає про `SceneMode.GUIDE` — під час ведення обсяг платівки стиснутий,
 *    і чи видно авторство на тому екрані, склом не доведено;
 *  - розпізнає `if` за текстом рядка, тож обгортка іншої форми
 *    (`when`, ранній `return`, `AnimatedVisibility`) пройде повз нього.
 */
class MapAttributionAlwaysVisibleTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("repo root with feature-stream/ not found") as Nothing
    }

    private fun mapSource(name: String): String {
        val root = File(repoRoot(), "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map")
        val file = root.walkTopDown().firstOrNull { it.isFile && it.name == name }
            ?: fail("не знайдено $name") as Nothing
        return file.readText()
    }

    /** Коментарі геть: пояснення про знятий `if` не сміє читатись як сам `if`. */
    private fun codeLines(source: String): List<String> = source.lines().map { line ->
        val t = line.trim()
        if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) "" else line
    }

    @Test
    fun `платівку приладів не загороджує умова панелі інструментів`() {
        val lines = codeLines(mapSource("MapChrome.kt"))
        val callAt = lines.indexOfFirst { it.contains("MapInstrumentPlate(") }
        assertTrue(
            "у MapChrome.kt немає виклику MapInstrumentPlate — авторство OSM " +
                "нікуди подітись не могло, отже його більше нема на мапі взагалі",
            callAt >= 0,
        )
        val window = lines.subList(maxOf(0, callAt - 20), callAt)
        val guard = window.firstOrNull { line ->
            val t = line.trim()
            t.startsWith("if (") && (t.contains("toolsExpanded") || t.contains("sceneMode"))
        }
        assertTrue(
            "виклик MapInstrumentPlate знову стоїть під умовою «$guard». Платівка несе " +
                "рядок «© OpenStreetMap», а вендорський віджет MapLibre вимкнено в " +
                "MapCanvas — отже ця умова прибирає авторство з екрана цілком. " +
                "Ховати можна ОБСЯГ довідки (параметр compact), не саму платівку.",
            guard == null,
        )
    }

    private fun stringsXml(variant: String): String {
        val root = File(repoRoot(), "app/src/main/res/$variant")
        val file = File(root, "strings.xml")
        assertTrue("не знайдено $variant/strings.xml", file.isFile)
        return file.readText()
    }

    @Test
    fun `екран ліцензій знає, що дані з OpenStreetMap`() {
        val strings = stringsXml("values-uk")
        assertTrue(
            "settings_licenses_value зник або більше не називає OpenStreetMap — " +
                "це єдиний рядок, який людина бачить у списку налаштувань до того, " +
                "як розгорне подробиці",
            Regex("name=\"settings_licenses_value\"[^>]*>([^<]*OpenStreetMap[^<]*)").containsMatchIn(strings),
        )
        assertTrue(
            "lic_map_body зник, або в ньому більше немає ODbL — це єдине місце, де " +
                "стоїть повний текст атрибуції (© OpenStreetMap contributors, ODbL 1.0)",
            Regex("name=\"lic_map_body\"[^>]*>([^<]*OpenStreetMap[^<]*ODbL[^<]*)").containsMatchIn(strings),
        )
    }

    @Test
    fun `мапа більше не носить власного рядка авторства — рішення 13-09, не регрес`() {
        val controls = mapSource("MapControls.kt")
        assertTrue(
            "map_attrib_row повернувся в MapControls.kt: якщо авторство знову малюється " +
                "на самій мапі, онови докстрінг цього файлу — рішення 13.09 могло змінитись",
            !controls.contains("map_attrib_row"),
        )
    }
}
