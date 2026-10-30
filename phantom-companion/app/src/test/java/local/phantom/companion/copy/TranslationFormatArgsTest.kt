package local.phantom.companion.copy

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A translation that invents a format argument crashes only on the phone that speaks it:
 * `getString(id, a)` against «%1$s … %2$s» throws MissingFormatArgumentException in Czech
 * and never in the language the developer tests in.
 *
 * Reads what AAPT2 actually packed (escapes, `%%`, positions), per locale, against the
 * default resources, which the unknown locale «xx» falls back to. Layout-agnostic: it
 * does not care which language `values/` carries.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranslationFormatArgsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun resourcesFor(tag: String): Resources {
        val config = Configuration(context.resources.configuration)
        config.setLocale(Locale.forLanguageTag(tag))
        return context.createConfigurationContext(config).resources
    }

    private val comment = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    private val named = Regex("""<(string|plurals|string-array)\s+name="([^"]+)"([^>]*)>""")
    private val formatArg = Regex("""%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[sdfxXcbeEgG]""")

    private fun args(text: CharSequence): List<String> = formatArg.findAll(text).map { it.value }.sorted().toList()

    /** `kind` → names, from every module's `values*` files; lookup goes through Resources, not the files. */
    private fun declared(): Map<String, Set<String>> {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "feature-stream").isDirectory }
        val out = mutableMapOf("string" to mutableSetOf<String>(), "plurals" to mutableSetOf(), "array" to mutableSetOf())
        root.walkTopDown().onEnter { it.name != "build" && it.name != ".gradle" }
            .filter { it.isFile && it.extension == "xml" && it.parentFile.name.startsWith("values") && "src" in it.path }
            .forEach { file ->
                for (m in named.findAll(comment.replace(file.readText(), ""))) {
                    if ("translatable=\"false\"" in m.groupValues[3]) continue
                    val kind = when (m.groupValues[1]) { "string" -> "string"; "plurals" -> "plurals"; else -> "array" }
                    out.getValue(kind) += m.groupValues[2]
                }
            }
        return out
    }

    private val tags = listOf("uk", "en", "cs")
    private val counts = listOf(0, 1, 2, 3, 4, 5, 11, 12, 21, 22, 100, 101)

    @Test
    fun `every locale keeps the format arguments of the default resources`() {
        val pkg = context.packageName
        val base = resourcesFor("xx")
        val localized = tags.associateWith(::resourcesFor)
        val names = declared()
        val broken = mutableListOf<String>()
        var seen = 0

        for (name in names.getValue("string")) {
            val id = base.getIdentifier(name, "string", pkg).takeIf { it != 0 } ?: continue
            seen++
            val ref = args(base.getText(id))
            for ((tag, res) in localized) {
                val got = args(res.getText(id))
                if (got != ref) broken += "$tag: string/$name  $ref → $got"
            }
        }
        for (name in names.getValue("plurals")) {
            val id = base.getIdentifier(name, "plurals", pkg).takeIf { it != 0 } ?: continue
            seen++
            val ref = args(base.getQuantityText(id, 5))
            for ((tag, res) in localized) for (n in counts) {
                val got = args(res.getQuantityText(id, n))
                if (got != ref) broken += "$tag: plurals/$name[$n]  $ref → $got"
            }
        }
        for (name in names.getValue("array")) {
            val id = base.getIdentifier(name, "array", pkg).takeIf { it != 0 } ?: continue
            seen++
            val ref = base.getStringArray(id).map { args(it) }
            for ((tag, res) in localized) {
                val got = res.getStringArray(id).map { args(it) }
                if (got != ref) broken += "$tag: array/$name  $ref → $got"
            }
        }

        assertTrue("тест не бачить ресурсів у Robolectric: розібрано $seen", seen > 3000)
        assertEquals(
            "переклад змінив аргументи формату — впаде лише цією мовою:\n" + broken.take(60).joinToString("\n") { "    $it" },
            emptyList<String>(),
            broken,
        )
    }

    @Test
    fun `the locales really differ, so the comparison is not against itself`() {
        val pkg = context.packageName
        val id = resourcesFor("xx").getIdentifier("app_name", "string", pkg)
        assertTrue("app_name не знайдено", id != 0)
        val cancel = tags.associateWith { resourcesFor(it).getText(android.R.string.cancel).toString() }
        assertEquals(tags.size, cancel.values.toSet().size)
    }
}
