package local.phantom.companion.navigation

import java.io.File
import java.util.Locale
import local.phantom.companion.core.data.locale.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A phone in a language the native screens lack (de, es, …) gets English from
 * `res/values/`, never Ukrainian: AppLanguage does not claim it, the default
 * resources hold no Cyrillic, and any future `values-<wave>/` holds none either.
 */
class NoUkrainianOnOtherPhonesTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "app/src/main/java").isDirectory }

    private val cyrillic = Regex("[\\u0400-\\u04FF]")
    private val entry = Regex("""<(string|item)\b([^>]*)>([\s\S]*?)</\1>""")
    private val waves = listOf("es", "pt-BR", "de", "fr", "pl", "tr", "it", "id")

    @Test
    fun `native has no language of its own for wave phones, so they fall to the resource default`() {
        for (tag in waves) assertNull(tag, AppLanguage.of(Locale.forLanguageTag(tag)))
        val config = File(root, "app/src/main/res/xml/locales_config.xml").readText()
        assertTrue("values/ must stay English", "android:name=\"en\"" in config)
    }

    @Test
    fun `what a de or es phone reads natively has no Ukrainian`() {
        val dirs = root.listFiles().orEmpty().flatMap { module ->
            File(module, "src/main/res").listFiles().orEmpty().filter { d ->
                d.name == "values" || waves.any { d.name == "values-$it" || d.name == "values-${it.replace("-", "-r")}" }
            }
        }
        assertTrue("no default resources found", dirs.any { it.path.endsWith("app/src/main/res/values") })
        val leaks = dirs.flatMap { dir ->
            dir.listFiles { f -> f.extension == "xml" }.orEmpty().flatMap { f ->
                entry.findAll(f.readText())
                    .filter { "translatable=\"false\"" !in it.groupValues[2] && cyrillic.containsMatchIn(it.groupValues[3]) }
                    .map { "${f.relativeTo(root)}: ${it.groupValues[3].take(40)}" }
                    .toList()
            }
        }
        assertEquals(emptyList<String>(), leaks)
    }
}
