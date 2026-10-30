package local.phantom.companion.core.design.i18n

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * `values-en` falls back to `values` per key, so a missing translation is
 * invisible until an English-locale phone shows a Ukrainian sentence in the
 * middle of an English screen. Nothing fails, nothing logs.
 *
 * The two files must therefore carry the same key set, and a translation may
 * not lose a format argument — `%1$d` dropped in one locale crashes only in
 * that locale, on a device the author is not holding.
 *
 * GATE LAW: raw file I/O over other modules; run with `--rerun-tasks`.
 */
class TranslationParityFirewallTest {

    private val stringKey = Regex("""<string name="([^"]+)"""")
    private val formatArg = Regex("""%(\d+\$)?[sdf]""")

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("repo root with feature-stream/ not found") as Nothing
    }

    private fun entries(f: File): Map<String, String> =
        if (!f.isFile) emptyMap()
        else Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(f.readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    private fun modules(root: File): List<File> =
        root.listFiles().orEmpty()
            .filter { it.isDirectory && File(it, "src/main/res/values/strings.xml").isFile }
            .sortedBy { it.name }

    @Test
    fun `every english string has a ukrainian counterpart`() {
        val root = repoRoot()
        val gaps = modules(root).flatMap { m ->
            val en = entries(File(m, "src/main/res/values/strings.xml")).keys
            val uk = entries(File(m, "src/main/res/values-uk/strings.xml")).keys
            (en - uk).sorted().map { "${m.name}: $it" } +
                (uk - en).sorted().map { "${m.name}: $it (orphan in values-uk)" }
        }

        assertEquals(
            "a locale is missing strings — a Ukrainian phone will read English:\n" +
                gaps.joinToString("\n") { "    $it" },
            emptyList<String>(),
            gaps,
        )
    }

    @Test
    fun `a translation keeps every format argument`() {
        val root = repoRoot()
        val broken = modules(root).flatMap { m ->
            val uk = entries(File(m, "src/main/res/values-uk/strings.xml"))
            val en = entries(File(m, "src/main/res/values/strings.xml"))
            uk.mapNotNull { (k, v) ->
                val other = en[k] ?: return@mapNotNull null
                val a = formatArg.findAll(v).map { it.value }.sorted().toList()
                val b = formatArg.findAll(other).map { it.value }.sorted().toList()
                if (a == b) null else "${m.name}: $k  uk=$a en=$b"
            }
        }

        assertEquals(
            "a translation dropped or invented a format argument — it will crash " +
                "only in that locale:\n" + broken.joinToString("\n") { "    $it" },
            emptyList<String>(),
            broken,
        )
    }

    /**
     * Українська множина має ТРИ форми, англійська — дві.
     *
     * 19.09 на апараті список кроків маршруту казав «44 маневрів»: число
     * склеювалось із єдиним іменником просто в Kotlin, тож «2 маневрів» і
     * «21 маневрів» були так само неминучі. `<plurals>` це лікує — але лише
     * якщо в НЬОМУ є всі три: `one` (1, 21, 31…), `few` (2-4, 22-24), `many`
     * (5-20, 0), плюс `other` для дробових. Набір із самих `one`/`other` — та
     * сама вада, просто перенесена з коду в XML, де вона виглядає зробленою.
     *
     * `values-en` тут навмисно не перевіряється: `few`/`many` в англійській
     * не існує, і вимагати їх означало б вимагати мертвих рядків.
     */
    @Test
    fun `every ukrainian plural carries all three forms`() {
        val plural = Regex("""<plurals name="([^"]+)">(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)
        val quantity = Regex("""quantity="(\w+)"""")
        val required = setOf("one", "few", "many", "other")

        val files = repoRoot().listFiles().orEmpty()
            .filter { it.isDirectory }
            .map { File(it, "src/main/res/values-uk") }
            .filter { it.isDirectory }
            .flatMap { dir -> dir.listFiles().orEmpty().filter { it.name.endsWith(".xml") } }

        if (files.isEmpty()) fail("сторож дивиться в порожнє: жодного values-uk/*.xml")

        val thin = files.flatMap { f ->
            plural.findAll(f.readText()).mapNotNull { m ->
                val have = quantity.findAll(m.groupValues[2]).map { it.groupValues[1] }.toSet()
                val missing = required - have
                if (missing.isEmpty()) null else "${f.name}: ${m.groupValues[1]} missing $missing"
            }
        }.sorted()

        assertEquals(
            "a Ukrainian plural is missing a form — «44 маневрів» is what that " +
                "looks like on the phone:\n" + thin.joinToString("\n") { "    $it" },
            emptyList<String>(),
            thin,
        )
    }
}
