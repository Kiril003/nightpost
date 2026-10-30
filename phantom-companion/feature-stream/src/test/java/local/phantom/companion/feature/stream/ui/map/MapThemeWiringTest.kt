package local.phantom.companion.feature.stream.ui.map

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Світла основа мусить малюватися світлою палітрою.
 *
 * `MapSymbolPalette.resolve(isDark)` існує рівно для цього, але кожна функція,
 * що його читає, мала `isDark: Boolean = true` — і жоден виклик у MapCanvas
 * не передавав живого значення. Усе компілювалося, усі тести були зелені, а на
 * денній мапі підписи, маршрути й позначки впевненості малювалися в темній
 * полярності: акценти регістру давали 1.61–2.34:1 замість 4.35–5.00:1.
 *
 * Компілятор боронить лише ті функції, що живуть у MapStyleLayers.kt — там
 * дефолти прибрано. Решта оголошена в чужих файлах, тож сторожем тут є текст
 * самого MapCanvas.kt (той самий прийом, що в MapDepthFirewallTest).
 */
class MapThemeWiringTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("repo root with feature-stream/ not found") as Nothing
    }

    private fun sourceOf(name: String): String {
        val file = File(
            repoRoot(),
            "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map/$name",
        )
        assertTrue("$name не знайдено — перевірка вже нічого не боронить", file.isFile)
        return file.readLines().joinToString("\n") { line ->
            val t = line.trimStart()
            if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) "" else line
        }
    }

    /**
     * ATLAS-0b: кожен фарбувальний виклик переїхав у контрибуції. Полярність
     * там зветься так само (`mapIsDark`) саме тому, що цей сторож читає імʼя —
     * перейменування локальної змінної не сміє звільнити виклик від воріт.
     */
    private fun themedSource(): String = sourceOf("MapGroundContributions.kt")

    /** Хост, де полярність ОГОЛОШЕНА. */
    private fun canvasSource(): String = sourceOf("MapCanvas.kt")

    /**
     * Текст усередині дужок виклику, від імені до парної закривної. Виклик
     * часто розкладений на кілька рядків, тож перевіряти лише рядок з іменем
     * означало б пропускати саме довгі виклики.
     */
    private fun callArguments(source: String, call: String): List<String> {
        val needle = "$call("
        val found = mutableListOf<String>()
        var from = 0
        while (true) {
            val at = source.indexOf(needle, from)
            if (at < 0) return found
            var depth = 0
            var i = at + needle.length - 1
            while (i < source.length) {
                when (source[i]) {
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) break
                    }
                }
                i++
            }
            found += source.substring(at, minOf(i + 1, source.length))
            from = at + needle.length
        }
    }

    @Test
    fun `every themed call in the canvas is handed the live polarity`() {
        val source = themedSource()
        val blind = MapTheme.THEMED_CALLS.filter { call ->
            callArguments(source, call).any { !it.contains(MapTheme.LIVE) }
        }.sorted()

        assertEquals(
            "виклик фарбує шар, не питаючи, яка зараз основа: " + blind.joinToString(", ") +
                "\n\nТемна палітра на світлій основі — це не «трохи не той відтінок»: " +
                "акценти регістру падають до 1.6:1, штрихування «не завантажено» — до 1.5:1.",
            emptyList<String>(),
            blind,
        )
    }

    @Test
    fun `the list guards calls that are actually in the canvas`() {
        // Без цього перевірка проходила б і на порожньому файлі, і на
        // перейменованій функції.
        val source = themedSource()
        val missing = MapTheme.THEMED_CALLS.filter { callArguments(source, it).isEmpty() }.sorted()

        assertEquals(
            "перейменований виклик тихо вивів себе з-під сторожа: " + missing.joinToString(", "),
            emptyList<String>(),
            missing,
        )
    }

    @Test
    fun `the live polarity follows the forced basemap, not only the app theme`() {
        // Оператор може примусово поставити світлу основу на нічній темі. Якби
        // палітра читала тему застосунку, вона малювала б темним по світлому
        // саме тоді, коли людина свідомо просила світлу мапу.
        val source = canvasSource()
        val declaration = source.substringAfter("val ${MapTheme.LIVE} =", "")
        assertTrue("${MapTheme.LIVE} більше не оголошено в MapCanvas", declaration.isNotEmpty())
        val head = declaration.take(320)
        listOf("basemapOverride", "MapBasemap.DARK", "MapBasemap.LIGHT", "isDarkTheme").forEach {
            assertTrue("${MapTheme.LIVE} не враховує $it", head.contains(it))
        }
    }
}
