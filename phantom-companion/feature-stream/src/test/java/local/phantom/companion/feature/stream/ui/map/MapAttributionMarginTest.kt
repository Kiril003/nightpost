package local.phantom.companion.feature.stream.ui.map

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Вендорський віджет MapLibre лишається вимкненим — і тепер це ЄДИНА умова
 * ліцензії, яку боронить цей файл.
 *
 * До 17 серпня файл боронив протилежне (що чужий віджет увімкнений). Тоді
 * його зняли й замінили власним рядком «© OpenStreetMap» у платівці приладів.
 *
 * **13.09, рішення власника:** той власний рядок теж прибрано з мапи —
 * авторство тепер живе лише в Налаштування → Про застосунок/Ліцензії
 * (перевірено `MapAttributionAlwaysVisibleTest`). Два тести, що боронили
 * рядок © на платівці, звідси прибрано разом з ним — залишати їх зеленими
 * підробленою перевіркою гірше, ніж не мати їх зовсім.
 *
 * Лишається доводити ДВОЄ, і обидва не про атрибуцію саму по собі, а про
 * те, щоб чужий вендорський віджет не повернувся тихцем замість неї:
 *
 *   1. вендорський віджет справді вимкнено — обидва прапорці;
 *   2. платівка не має раннього виходу на чотирьох мовчазних приладах.
 */
class MapAttributionMarginTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("repo root with feature-stream/ not found") as Nothing
    }

    private fun source(relative: String): List<String> {
        val file = File(
            repoRoot(),
            "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map/$relative",
        )
        assertTrue("$relative не знайдено — перевірка вже нічого не боронить", file.isFile)
        return file.readLines()
    }

    /** Коментар може містити що завгодно, зокрема й назву прапорця. */
    private fun code(line: String): String = line.substringBefore("//")

    @Test
    fun `вендорський віджет авторства вимкнено обома прапорцями`() {
        val lines = source("MapCanvas.kt").map { code(it) }
        listOf("isAttributionEnabled = false", "isLogoEnabled = false").forEach { flag ->
            assertTrue(
                "$flag не виставлено — на мапі знову чужий віджет поверх наших приладів",
                lines.any { it.contains(flag) },
            )
        }
        assertFalse(
            "isAttributionEnabled = true повернулось: два авторства одночасно",
            lines.any { it.contains("isAttributionEnabled = true") },
        )
    }

    @Test
    fun `платівка не зникає цілком`() {
        val lines = source("MapControls.kt").map { code(it) }
        assertFalse(
            "у платівці повернувся ранній вихід на чотирьох мовчазних приладах — " +
                "разом із ними з екрана піде й авторство",
            lines.any {
                it.contains("bar == null") && it.contains("declination == null") &&
                    it.contains("return")
            },
        )
    }

    @Test
    fun `підйом чужого віджета більше не потрібен і не лишився`() {
        val lines = source("MapCanvas.kt").map { code(it) }
        listOf("setAttributionMargins", "setLogoMargins", "attributionBottomPx").forEach { dead ->
            assertFalse(
                "$dead лишився в дереві, хоч віджет вимкнено — код, що рухає те, " +
                    "чого немає, наступного разу прочитають як живий контроль",
                lines.any { it.contains(dead) },
            )
        }
    }
}
