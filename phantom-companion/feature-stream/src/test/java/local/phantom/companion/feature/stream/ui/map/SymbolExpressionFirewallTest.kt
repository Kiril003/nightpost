package local.phantom.companion.feature.stream.ui.map

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * ВЛАСТИВОСТІ, ЯКІ MAPLIBRE ВИКИДАЄ МОВЧКИ.
 *
 * Помилка цього класу не валить застосунок і не псує збірку — вона лишає в
 * логові один рядок `Error setting property`, а на склі властивість, якої
 * просто немає. Саме так шар маневрів цілий вал їздив без `icon-size`,
 * `icon-opacity` й `icon-allow-overlap`, і жоден тест цього не бачив.
 *
 * Два правила зі style-spec, обидва — про форму виразу, не про значення:
 *
 *  1. `icon-allow-overlap` не приймає ВИРАЗІВ ВІД ДАНИХ. Тільки літерал.
 *     Пріоритет у тісноті задається `symbol-sort-key`, а не цією властивістю.
 *
 *  2. `zoom()` мусить бути ПРЯМИМ входом зовнішнього `interpolate`/`step`.
 *     Схований усередині `match`, `case` чи `product` — і style-spec відкидає
 *     властивість цілком. Розгалуження за даними йде у ВИХОДИ зупинок.
 *
 * GATE LAW: тест читає джерела як файли, повз up-to-date Gradle — на валі
 * запускати з `--rerun`.
 */
class SymbolExpressionFirewallTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("repo root with feature-stream/ not found") as Nothing
    }

    private fun mapSources(): List<File> =
        File(repoRoot(), "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    /**
     * Текст аргументів виклику [call] — від дужки після імені до її пари.
     * Рахунок дужок, а не regex: вирази тут вкладені на шість рівнів.
     */
    private fun argumentsOf(source: String, call: String): List<String> {
        val out = mutableListOf<String>()
        var from = 0
        while (true) {
            val start = source.indexOf(call, from)
            if (start < 0) return out
            var depth = 0
            var i = start + call.length - 1
            val begin = i + 1
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
            out += source.substring(begin, i.coerceAtMost(source.length))
            from = i + 1
        }
    }

    @Test
    fun `icon-allow-overlap is never a data expression`() {
        val offenders = mapSources().flatMap { file ->
            argumentsOf(file.readText(), "iconAllowOverlap(")
                .filter { it.contains("Expression.") }
                .map { "${file.name}: iconAllowOverlap(${it.trim().take(60)}…)" }
        }
        assertEquals(
            "icon-allow-overlap приймає лише літерал — вираз від даних MapLibre " +
                "відкидає разом із властивістю (у логові: «icon-allow-overlap data " +
                "expressions not supported»). Пріоритет задавай symbolSortKey.\n" +
                offenders.joinToString("\n"),
            emptyList<String>(),
            offenders,
        )
    }

    /**
     * Позиція `Expression.zoom()` серед аргументів `interpolate`/`step` не
     * перевіряється — перевіряється лише те, що між початком властивості й
     * `zoom()` немає нічого, крім однієї камерної обгортки.
     */
    @Test
    fun `zoom stays the top-level input of icon-size and icon-opacity`() {
        val camera = Regex("""^\s*Expression\.(interpolate|step)\(""")
        val offenders = mutableListOf<String>()
        for (file in mapSources()) {
            val text = file.readText()
            for (call in listOf("iconSize(", "iconOpacity(", "iconRotate(")) {
                for (arg in argumentsOf(text, call)) {
                    if (!arg.contains("Expression.zoom()")) continue
                    if (!camera.containsMatchIn(arg)) {
                        offenders += "${file.name}: $call zoom() без камерної обгортки"
                        continue
                    }
                    // Те, що стоїть перед зовнішнім interpolate/step: якщо там
                    // будь-який інший виклик Expression, zoom уже вкладений.
                    val head = arg.substringBefore("Expression.interpolate(")
                        .substringBefore("Expression.step(")
                    if (head.contains("Expression.")) {
                        offenders += "${file.name}: $call — zoom() вкладений у " +
                            head.trim().substringAfterLast("Expression.").take(40)
                    }
                }
            }
        }
        assertEquals(
            "«zoom expression may only be used as input to a top-level step or " +
                "interpolate expression». Постав interpolate(zoom) ЗОВНІ, а вибір " +
                "за даними — у виходи зупинок.\n" + offenders.joinToString("\n"),
            emptyList<String>(),
            offenders,
        )
    }
}
