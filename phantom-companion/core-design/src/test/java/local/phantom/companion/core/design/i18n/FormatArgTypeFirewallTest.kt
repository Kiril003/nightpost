package local.phantom.companion.core.design.i18n

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * `%d` не має отримувати рядок.
 *
 * 24 серпня 2026 застосунок падав на телефоні власника:
 * `IllegalFormatConversionException: d != java.lang.String`, діалог вибору
 * офлайн-місця, `OfflinePlacePicker.kt:154`. Рядок
 * `map_places_estimate_foreign` казав «заміряно на **%2$d** ділянках», а
 * другим аргументом їхала `PlacePackPlanner.SAMPLED_ON` — константа
 * `"16.08.2026"`, тобто ДАТА. Формат ламався об тип, і екран падав щоразу,
 * коли в результатах траплявся закордонний рядок.
 *
 * Речення при цьому було неправдиве й без падіння: «заміряно на 16.08.2026
 * ділянках». Тобто одна недбалість дала і виняток, і брехню — і жоден
 * компілятор не міг про це сказати, бо `stringResource` приймає `vararg Any`.
 *
 * ## Що це ловить і чого не ловить
 *
 * Тут немає типів: це сканування вихідників. Ловиться найпоширеніший і
 * найдешевший випадок — під `%N$d` їде рядковий літерал, `const val` зі
 * значенням-рядком, або `.toString()`. Змінна, поле чи виклик функції без
 * очевидного типу **не позначаються**: перевірка радше промовчить, ніж
 * назве винним невинного.
 *
 * Тому нуль тут означає «жодного дешевого випадку», а не «формати цілком
 * безпечні». Це менше, ніж хотілося б, і рівно стільки, скільки чесно.
 *
 * GATE LAW (спільний з іншими вартовими): читає чужі вихідники як файли,
 * повз перевірку актуальності Gradle. Ганяти з `--rerun`.
 */
class FormatArgTypeFirewallTest {

    private val resRoots = listOf(
        "app/src/main/res/values-uk", "core-design/src/main/res/values-uk",
        "feature-stream/src/main/res/values-uk", "feature-onboarding/src/main/res/values-uk",
        "feature-precision/src/main/res/values-uk", "feature-ambient/src/main/res/values-uk",
        "feature-godmode/src/main/res/values-uk", "feature-car/src/main/res/values-uk",
    )

    private val srcRoots = listOf(
        "app/src/main", "core-design/src/main", "feature-stream/src/main",
        "feature-onboarding/src/main", "feature-precision/src/main",
        "feature-ambient/src/main", "feature-godmode/src/main", "feature-car/src/main",
    )

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("корінь дерева з feature-stream/ не знайдено") as Nothing
    }

    /** імʼя ресурсу → номер аргументу → буква формату. */
    private fun placeholders(): Map<String, Map<Int, Char>> {
        val out = mutableMapOf<String, Map<Int, Char>>()
        val string = Regex("""<string name="([^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        val ph = Regex("""%(\d+)\$([a-z])""")
        for (root in resRoots) {
            val f = File(repoRoot(), "$root/strings.xml")
            if (!f.isFile) continue
            string.findAll(f.readText()).forEach { m ->
                val found = ph.findAll(m.groupValues[2])
                    .associate { it.groupValues[1].toInt() to it.groupValues[2][0] }
                if (found.isNotEmpty()) out[m.groupValues[1]] = found
            }
        }
        return out
    }

    private fun ktFiles(): List<File> = srcRoots.flatMap { root ->
        val dir = File(repoRoot(), root)
        if (!dir.isDirectory) emptyList()
        else dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    /** Імена `const val` зі значенням-рядком: саме такі й підставляли під `%d`. */
    private fun stringConstants(sources: List<Pair<File, String>>): Set<String> {
        val decl = Regex("""\bconst val ([A-Z][A-Z0-9_]*)\s*(?::\s*String\s*)?=\s*"""")
        return sources.flatMap { (_, text) ->
            decl.findAll(text).map { it.groupValues[1] }.toList()
        }.toSet()
    }

    /** Аргументи виклику після `R.string.name,` — до балансу дужок. */
    private fun argsAfter(text: String, from: Int): List<String> {
        var depth = 1
        val args = mutableListOf<String>()
        val cur = StringBuilder()
        var i = from
        while (i < text.length) {
            val ch = text[i]
            when {
                ch == '(' || ch == '[' -> depth++
                ch == ')' || ch == ']' -> {
                    depth--
                    if (depth == 0) break
                }
            }
            if (ch == ',' && depth == 1) {
                args += cur.toString().trim()
                cur.clear()
            } else {
                cur.append(ch)
            }
            i++
        }
        if (cur.isNotBlank()) args += cur.toString().trim()
        return args
    }

    @Test
    fun `жоден числовий формат не отримує рядок`() {
        val formats = placeholders()
        assertTrue(
            "жодного форматного рядка не знайдено — перевірка вже нічого не боронить",
            formats.isNotEmpty(),
        )
        val sources = ktFiles().map { it to it.readText() }
        val consts = stringConstants(sources)
        val call = Regex("""R\.string\.([A-Za-z0-9_]+)\s*,""")

        val offenders = mutableListOf<String>()
        for ((file, text) in sources) {
            call.findAll(text).forEach { m ->
                val spec = formats[m.groupValues[1]] ?: return@forEach
                argsAfter(text, m.range.last + 1).forEachIndexed { idx, arg ->
                    if (spec[idx + 1] != 'd') return@forEachIndexed
                    val stringish = arg.startsWith("\"") ||
                        arg.endsWith(".toString()") ||
                        consts.any { Regex("""\b$it\b""").containsMatchIn(arg) }
                    if (stringish) {
                        val line = text.substring(0, m.range.first).count { it == '\n' } + 1
                        offenders += "${file.name}:$line  ${m.groupValues[1]}  " +
                            "аргумент №${idx + 1} під %${idx + 1}\$d ← ${arg.take(48)}"
                    }
                }
            }
        }

        assertEquals(
            "числовий формат отримує рядок — це IllegalFormatConversionException на живому " +
                "екрані, а не попередження:\n    " + offenders.joinToString("\n    ") +
                "\n\nАбо змініть формат на %s, або передайте число. І перечитайте саме " +
                "речення: якщо під «%d ділянках» їде дата, то навіть без падіння воно бреше.",
            emptyList<String>(),
            offenders,
        )
    }
}
