package local.phantom.companion.core.design.i18n

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * A second language is a promise that every screen speaks it. A key missing
 * from `values-cs` or `values-uk` is invisible: Android falls back to `values/`,
 * which is English, so a Czech phone shows one English sentence in the middle
 * of a Czech screen. A dropped `%1$d` crashes only in that locale, and a Czech
 * plural with no `few` says «5 zpráva» only to people who count to five.
 *
 * Unlike [TranslationParityFirewallTest] this reads EVERY xml in `values/`
 * (the split `strings_*.xml` files too) and covers strings, plurals and
 * string-arrays, in every module and in the debug source set.
 *
 * GATE LAW: raw file I/O over other modules; run with `--rerun-tasks`.
 */
class LocaleParityFirewallTest {

    private class Res(
        val strings: Map<String, String>,
        val plurals: Map<String, Map<String, String>>,
        val arrays: Map<String, List<String>>,
    ) {
        val keys: Set<String> get() = strings.keys + plurals.keys + arrays.keys
    }

    /** Quantities a language needs for whole numbers plus the fractional form Android may ask for. */
    private val pluralForms = mapOf(
        "uk" to setOf("one", "few", "many", "other"),
        "cs" to setOf("one", "few", "many", "other"),
    )


    private val stringTag = Regex("""<string\s+name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
    private val pluralTag = Regex("""<plurals\s+name="([^"]+)"[^>]*>(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)
    private val arrayTag = Regex("""<string-array\s+name="([^"]+)"[^>]*>(.*?)</string-array>""", RegexOption.DOT_MATCHES_ALL)
    private val quantityItem = Regex("""<item\s+quantity="(\w+)"[^>]*>(.*?)</item>""", RegexOption.DOT_MATCHES_ALL)
    private val plainItem = Regex("""<item\b[^>]*>(.*?)</item>""", RegexOption.DOT_MATCHES_ALL)
    private val comment = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    private val formatArg = Regex("""%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[sdfxXcbeEgG%]""")
    private val cyrillic = Regex("[А-Яа-яІіЇїЄєҐґ]")

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("repo root with feature-stream/ not found") as Nothing
    }

    private fun load(dir: File): Res {
        val xml = dir.listFiles().orEmpty().filter { it.extension == "xml" }.sortedBy { it.name }
            .joinToString("\n") { comment.replace(it.readText(), "") }
        val plurals = pluralTag.findAll(xml).associate { m ->
            m.groupValues[1] to quantityItem.findAll(m.groupValues[2]).associate { it.groupValues[1] to it.groupValues[2] }
        }
        val arrays = arrayTag.findAll(xml).associate { m ->
            m.groupValues[1] to plainItem.findAll(m.groupValues[2]).map { it.groupValues[1] }.toList()
        }
        val withoutGroups = arrayTag.replace(pluralTag.replace(xml, ""), "")
        val strings = stringTag.findAll(withoutGroups).associate { it.groupValues[1] to it.groupValues[2] }
        return Res(strings, plurals, arrays)
    }

    /** `<module>/src/<set>/res` directories whose `values/` carries translatable text. */
    private fun resDirs(root: File): List<File> =
        root.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }
            .flatMap { m -> listOf("main", "debug").map { File(m, "src/$it/res") } }
            .filter { res -> load(File(res, "values")).keys.isNotEmpty() }

    private fun tagOf(res: File) = res.parentFile.parentFile.parentFile.name + "/" + res.parentFile.name

    private fun args(text: String) = formatArg.findAll(text).map { it.value }.sorted().toList()

    private val locales = listOf("uk", "cs")

    private data class Side(val where: String, val locale: String, val source: Res, val target: Res?)

    private fun pairs(): List<Side> {
        val resDirs = resDirs(repoRoot())
        assertTrue("сторож дивиться в порожнє: жодного res/values із рядками", resDirs.size >= 9)
        return resDirs.flatMap { res ->
            val source = load(File(res, "values"))
            locales.map { l ->
                val dir = File(res, "values-$l")
                Side(tagOf(res), l, source, if (dir.isDirectory) load(dir) else null)
            }
        }
    }

    @Test
    fun `every locale carries every string, plural and array of the source and nothing else`() {
        val gaps = pairs().flatMap { (where, l, src, tgt) ->
            if (tgt == null) return@flatMap listOf("$where: немає values-$l")
            (src.keys - tgt.keys).sorted().map { "$where/values-$l: немає $it" } +
                (tgt.keys - src.keys).sorted().map { "$where/values-$l: $it без джерела" } +
                src.strings.keys.intersect(tgt.plurals.keys + tgt.arrays.keys).map { "$where/values-$l: $it змінило вид" } +
                src.plurals.keys.intersect(tgt.strings.keys + tgt.arrays.keys).map { "$where/values-$l: $it змінило вид" } +
                src.arrays.keys.intersect(tgt.strings.keys + tgt.plurals.keys).map { "$where/values-$l: $it змінило вид" }
        }
        assertEquals(
            "переклад не повний — телефон цією мовою покаже український рядок посеред екрана:\n" +
                gaps.take(80).joinToString("\n") { "    $it" } + if (gaps.size > 80) "\n    … і ще ${gaps.size - 80}" else "",
            emptyList<String>(),
            gaps,
        )
    }

    @Test
    fun `a translation keeps every format argument of its source`() {
        val broken = pairs().flatMap { (where, l, src, tgt) ->
            if (tgt == null) return@flatMap emptyList<String>()
            val s = src.strings.mapNotNull { (k, v) ->
                val other = tgt.strings[k] ?: return@mapNotNull null
                if (args(v) == args(other)) null else "$where/values-$l: $k  ${args(v)} → ${args(other)}"
            }
            val p = src.plurals.flatMap { (k, forms) ->
                val reference = args(forms["other"] ?: forms.values.first())
                (tgt.plurals[k] ?: emptyMap()).mapNotNull { (q, v) ->
                    if (args(v) == reference) null else "$where/values-$l: $k[$q]  $reference → ${args(v)}"
                }
            }
            val a = src.arrays.flatMap { (k, items) ->
                val other = tgt.arrays[k] ?: return@flatMap emptyList()
                items.zip(other).mapIndexedNotNull { i, (x, y) ->
                    if (args(x) == args(y)) null else "$where/values-$l: $k[$i]  ${args(x)} → ${args(y)}"
                }
            }
            s + p + a
        }
        assertEquals(
            "переклад загубив або вигадав аргумент формату — впаде лише цією мовою:\n" +
                broken.take(80).joinToString("\n") { "    $it" },
            emptyList<String>(),
            broken,
        )
    }

    @Test
    fun `every plural carries the forms its language counts with`() {
        val thin = pairs().flatMap { (where, l, _, tgt) ->
            val need = pluralForms.getValue(l)
            (tgt?.plurals ?: emptyMap()).mapNotNull { (k, forms) ->
                val missing = need - forms.keys
                val extra = forms.keys - setOf("zero", "one", "two", "few", "many", "other")
                if (missing.isEmpty() && extra.isEmpty() && forms.values.none { it.isBlank() }) null
                else "$where/values-$l: $k  бракує $missing${if (extra.isEmpty()) "" else " зайве $extra"}"
            }
        }
        assertEquals("множина без потрібної форми — «5 zpráva» виглядає саме так:\n" + thin.joinToString("\n") { "    $it" }, emptyList<String>(), thin)
    }

    @Test
    fun `a string array keeps its length`() {
        val short = pairs().flatMap { (where, l, src, tgt) ->
            src.arrays.mapNotNull { (k, items) ->
                val other = tgt?.arrays?.get(k) ?: return@mapNotNull null
                if (other.size == items.size) null else "$where/values-$l: $k  ${items.size} → ${other.size}"
            }
        }
        assertEquals("масив іншої довжини — індекс за межі в коді, що знає лише довжину джерела:\n" + short.joinToString("\n") { "    $it" }, emptyList<String>(), short)
    }

    private fun cyrillicKeys(r: Res): List<String> =
        r.strings.filterValues { cyrillic.containsMatchIn(it) }.keys.toList() +
            r.plurals.filterValues { forms -> forms.values.any { cyrillic.containsMatchIn(it) } }.keys +
            r.arrays.filterValues { items -> items.any { cyrillic.containsMatchIn(it) } }.keys

    @Test
    fun `only Ukrainian carries Cyrillic`() {
        val leaked = pairs().filter { it.locale == "cs" }.flatMap { (where, _, src, tgt) ->
            cyrillicKeys(src).map { "$where/values: $it" } +
                cyrillicKeys(tgt ?: return@flatMap emptyList<String>()).map { "$where/values-cs: $it" }
        }
        assertEquals("українське слово в чужій мові — скопійоване, а не перекладене:\n" + leaked.joinToString("\n") { "    $it" }, emptyList<String>(), leaked)
    }

    @Test
    fun `the guard sees the whole tree`() {
        val all = pairs()
        val cs = all.filter { it.locale == "cs" }
        assertTrue("жодна пара джерело/чеська", cs.isNotEmpty())
        assertTrue("сторож бачить менше 3000 рядків джерела: ${cs.sumOf { it.source.strings.size }}", cs.sumOf { it.source.strings.size } > 3000)
        assertTrue("сторож не бачить множин", cs.sumOf { it.source.plurals.size } >= 20)
        assertTrue("сторож не бачить масивів", cs.sumOf { it.source.arrays.size } >= 2)
    }
}
