package local.phantom.companion.feature.stream.i18n

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.w3c.dom.Element

/**
 * I18N-2 — the two locales of feature-stream must describe the same app.
 *
 * `values/strings.xml` is the Ukrainian default and `values-en/strings.xml` its
 * English mirror. A key in one and not the other is not a cosmetic gap:
 *  - missing in `values-en` → an English operator silently reads Ukrainian;
 *  - missing in `values` → `stringResource` throws Resources.NotFoundException
 *    on every locale that is not English, which is the shipping default.
 *
 * The third failure is quieter and worse: the same key with different format
 * placeholders. `hero_greet_day_named` takes a `%1$s` in Ukrainian; drop it
 * from the English string and `getString(id, who)` is fine, but add one the
 * Ukrainian lacks and the call throws `MissingFormatArgumentException` at the
 * moment the operator opens the screen. So arity is checked, not just names.
 *
 * The counterpart of core-design's `HardcodedCyrillicFirewallTest`: that one
 * pushes literals out of composables, this one makes sure they land in both
 * files when they do.
 */
class StringResourceParityTest {

    private fun resDir(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        val root = dir ?: fail("repo root with feature-stream/ not found") as Nothing
        return File(root, "feature-stream/src/main/res")
    }

    /** Every named resource in one values file: name → its text. */
    private fun resources(file: File): Map<String, String> {
        assertTrue("${file.path} is missing", file.isFile)
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val out = LinkedHashMap<String, String>()
        val children = doc.documentElement.childNodes
        for (i in 0 until children.length) {
            val el = children.item(i) as? Element ?: continue
            val name = el.getAttribute("name").takeIf { it.isNotBlank() } ?: continue
            val key = "${el.tagName}/$name"
            assertTrue("${file.name}: duplicate resource $key", key !in out)
            out[key] = el.textContent
        }
        return out
    }

    /**
     * `<plurals>` items, kept separate instead of concatenated. Locales are
     * free to declare a DIFFERENT NUMBER of quantity forms — Ukrainian needs
     * one/few/many/other, English only one/other, and that is plural-rule
     * shape, not a mirroring gap. [resources]'s `el.textContent` flattens
     * every `<item>` into one string, so it was fine for the two tests that
     * only need a name→text map, but wrong for placeholder comparison: it
     * turned "how many forms" into part of the diff, so a correctly
     * 4-form uk plural could never match a correctly 2-form en one (caught
     * on `map_prov_age_days` — every item was individually correct).
     */
    private fun pluralItemTexts(file: File): Map<String, List<String>> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val out = LinkedHashMap<String, MutableList<String>>()
        val children = doc.documentElement.childNodes
        for (i in 0 until children.length) {
            val el = children.item(i) as? Element ?: continue
            if (el.tagName != "plurals") continue
            val name = el.getAttribute("name").takeIf { it.isNotBlank() } ?: continue
            val texts = out.getOrPut(name) { mutableListOf() }
            val items = el.childNodes
            for (j in 0 until items.length) {
                val item = items.item(j) as? Element ?: continue
                if (item.tagName == "item") texts += item.textContent
            }
        }
        return out
    }

    /** `%1$s`, `%d`, `%.1f` — but not the literal `%%`. */
    private val placeholder = Regex("""%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[a-zA-Z]""")

    private fun placeholdersOf(text: String): List<String> =
        placeholder.findAll(text.replace("%%", "")).map { it.value }.sorted().toList()

    private fun uk() = resources(File(resDir(), "values-uk/strings.xml"))
    private fun en() = resources(File(resDir(), "values/strings.xml"))
    private fun ukPlurals() = pluralItemTexts(File(resDir(), "values-uk/strings.xml"))
    private fun enPlurals() = pluralItemTexts(File(resDir(), "values/strings.xml"))

    @Test
    fun `every uk string has an en mirror and back`() {
        val uk = uk()
        val en = en()

        val missingEn = (uk.keys - en.keys).sorted()
        val missingUk = (en.keys - uk.keys).sorted()

        assertTrue(
            buildString {
                if (missingEn.isNotEmpty()) {
                    append("missing from values/strings.xml: ")
                    append(missingEn.joinToString())
                    append('\n')
                }
                if (missingUk.isNotEmpty()) {
                    append("missing from values-uk/strings.xml (the uk default — ")
                    append("reading these crashes on any non-en locale): ")
                    append(missingUk.joinToString())
                    append('\n')
                }
                append("add the key to BOTH files in the same commit.")
            },
            missingEn.isEmpty() && missingUk.isEmpty(),
        )

        // Belt and braces: a same-size/different-content pair would already
        // have failed above, but the count is the line a reviewer scans.
        assertEquals("uk and en resource counts diverged", uk.size, en.size)
    }

    @Test
    fun `mirrored strings take the same format arguments`() {
        val uk = uk()
        val en = en()
        val ukPlurals = ukPlurals()
        val enPlurals = enPlurals()

        val drift = (uk.keys intersect en.keys).sorted().mapNotNull { key ->
            if (key.startsWith("plurals/")) {
                // Two checks, not one pooled comparison (teammate `chat-lock`'s
                // refinement): first, every form WITHIN a locale must agree
                // with its own siblings — a form that quietly drops a
                // placeholder its siblings carry is a translation-quality bug
                // a pooled cross-locale set would hide. Second, the two
                // locales' (now-internally-consistent) placeholder sets must
                // match each other — this is the crash-preventing check, and
                // it stays independent of how many quantity FORMS each locale
                // declares (uk needs four, en needs two; that's plural-rule
                // shape, not a mirroring gap).
                val name = key.removePrefix("plurals/")
                val ukForms = ukPlurals[name].orEmpty().map { placeholdersOf(it) }
                val enForms = enPlurals[name].orEmpty().map { placeholdersOf(it) }
                val ukCanonical = ukForms.firstOrNull()
                val enCanonical = enForms.firstOrNull()
                val withinUk = ukForms.firstOrNull { it != ukCanonical }
                val withinEn = enForms.firstOrNull { it != enCanonical }
                when {
                    withinUk != null -> "$key: uk forms disagree with each other — $ukCanonical vs $withinUk"
                    withinEn != null -> "$key: en forms disagree with each other — $enCanonical vs $withinEn"
                    ukCanonical != enCanonical -> "$key: uk$ukCanonical vs en$enCanonical"
                    else -> null
                }
            } else {
                val a = placeholdersOf(uk.getValue(key))
                val b = placeholdersOf(en.getValue(key))
                if (a == b) null else "$key: uk$a vs en$b"
            }
        }

        assertTrue(
            "format placeholders differ between locales — the locale with the " +
                "extra argument throws at getString() time:\n" +
                drift.joinToString("\n"),
            drift.isEmpty(),
        )
    }

    @Test
    fun `no resource is blank in either locale`() {
        val blanks = listOf("values-uk" to uk(), "values" to en())
            .flatMap { (label, map) ->
                map.filterValues { it.isBlank() }.keys.map { "$label/$it" }
            }
        assertTrue(
            "a blank string resource renders as nothing at all: $blanks",
            blanks.isEmpty(),
        )
    }
}
