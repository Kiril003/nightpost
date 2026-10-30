package local.phantom.companion.navigation

import local.phantom.companion.core.data.locale.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Хвильові мови (es, pt-BR, de, fr, pl, tr, it, id) у рідних екранах — частковий
 * переклад головних екранів; решта рядків падає на `values/` (англійська), ніколи
 * на українську. Цей сторож: тека `values-<мова>` має лише ключі з джерела, без
 * кирилиці й з тими самими плейсхолдерами; в APK (`resourceConfigurations`) і в
 * «Як у телефоні» йде лише мова з [AppLanguage.NATIVE_READY], перекладена до кінця.
 */
class WaveLocaleResourcesTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "app/src/main/java").isDirectory }

    private val wave = listOf("es", "pt-rBR", "de", "fr", "pl", "tr", "it", "in", "id")
    private val entry = Regex("""<(string|plurals|string-array)\s+name="([^"]+)"[^>]*>(.*?)</\1>""", RegexOption.DOT_MATCHES_ALL)
    private val placeholder = Regex("""%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[sdfxXc]""")
    private val cyrillic = Regex("[А-Яа-яІіЇїЄєҐґ]")

    private fun entries(dir: File): Map<String, String> =
        dir.listFiles { f -> f.extension == "xml" }.orEmpty()
            .flatMap { f -> entry.findAll(f.readText()).map { it.groupValues[2] to it.groupValues[3] }.toList() }
            .toMap()

    private val fixed = Regex("""<(?:string|plurals|string-array)\s+name="([^"]+)"[^>]*translatable="false"""")

    private fun translatable(dir: File): Set<String> =
        entries(dir).keys - dir.listFiles { f -> f.extension == "xml" }.orEmpty()
            .flatMap { f -> fixed.findAll(f.readText()).map { it.groupValues[1] }.toList() }.toSet()

    private fun waveDirs(): List<Pair<File, String>> =
        root.listFiles().orEmpty().flatMap { module ->
            wave.mapNotNull { tag -> File(module, "src/main/res/values-$tag").takeIf { it.isDirectory }?.let { it to tag } }
        }

    @Test
    fun `wave translations only carry source keys, no Cyrillic and the same placeholders`() {
        val gaps = mutableListOf<String>()
        for ((dir, tag) in waveDirs()) {
            val source = entries(File(dir.parentFile, "values"))
            for ((name, text) in entries(dir)) {
                val en = source[name]
                if (en == null) { gaps += "${dir.path}: $name без джерела"; continue }
                if (cyrillic.containsMatchIn(text)) gaps += "$tag/$name: кирилиця «${text.take(40)}»"
                val want = placeholder.findAll(en).map { it.value }.toSortedSet()
                val got = placeholder.findAll(text).map { it.value }.toSortedSet()
                if (want != got && !text.contains("<item")) gaps += "$tag/$name: плейсхолдери $got ≠ $want"
            }
        }
        assertTrue(gaps.take(20).joinToString("\n"), gaps.isEmpty())
    }

    private fun gradleTag(language: String) = when (language) {
        "pt" -> "pt-rBR"
        "id" -> "in"
        else -> language
    }

    @Test
    fun `the build packages a wave language only once its native screens are complete`() {
        val gradle = File(root, "app/build.gradle.kts").readText()
        val kept = Regex("""resourceConfigurations\s*\+=\s*listOf\(([^)]*)\)""").find(gradle)?.groupValues?.get(1).orEmpty()
            .split(',').map { it.trim().trim('"') }.filter { it.isNotEmpty() }.toSet()
        val ready = AppLanguage.NATIVE_READY.map(::gradleTag).toSet()
        val packaged = kept.filter { it in wave }.toSet()
        assertEquals("в APK лише повні хвильові мови (resourceConfigurations = $kept)", ready, packaged)
        assertTrue("наші мови лишаються: $kept", kept.containsAll(listOf("uk", "en", "cs")))

        val gaps = mutableListOf<String>()
        for (tag in ready) {
            root.listFiles().orEmpty().forEach { module ->
                val source = File(module, "src/main/res/values")
                if (!source.isDirectory) return@forEach
                val have = entries(File(module, "src/main/res/values-$tag")).keys
                val missing = translatable(source) - have
                if (missing.isNotEmpty()) gaps += "$tag/${module.name}: ${missing.size} без перекладу, напр. ${missing.first()}"
            }
        }
        assertTrue(gaps.joinToString("\n"), gaps.isEmpty())
    }
}
