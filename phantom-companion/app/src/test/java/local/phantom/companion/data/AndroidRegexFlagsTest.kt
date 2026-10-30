package local.phantom.companion.data

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ICU на Android не знає прапорця `U` (UNICODE_CHARACTER_CLASS): `(?iU)` у
 * `ContactRequestBook` падав на ініціалізації класу, і стрічка розмов на телефоні
 * щоразу казала «Розмови не завантажилися». JVM прапорець знає, тож лише скан.
 */
class AndroidRegexFlagsTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: error("не знайшов корінь дерева (каталог feature-stream)")
    }

    @Test
    fun noUnicodeClassFlagInMainSources() {
        val files = repoRoot().walkTopDown()
            .onEnter { it.name != "build" && it.name != ".gradle" }
            .filter { it.isFile && it.extension == "kt" && it.path.contains("/src/main/") }
            .toList()
        check(files.size > 200) { "підозріло мало .kt у src/main (${files.size})" }
        val inline = Regex("""\(\?[a-zA-Z]*U[a-zA-Z]*[):]""")
        val hits = files.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                val bad = inline.containsMatchIn(line) || "UNICODE_CHARACTER_CLASS" in line
                if (bad) "${f.relativeTo(repoRoot())}:${i + 1}" else null
            }
        }
        assertTrue("прапорець U не існує в ICU Android: $hits", hits.isEmpty())
    }
}
