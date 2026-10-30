package local.phantom.obsidian

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Жодного кольору поза [Tokens].
 *
 * Це той самий храповик, що й у решті продукту: варто дозволити один «тимчасовий»
 * hex у шарі, і за півроку палітра живе у двадцяти місцях, а документ токенів
 * описує стиль, якого нема.
 */
class NoLiteralInkTest {

    private val hex = Regex("""#[0-9A-Fa-f]{6}\b""")

    private fun sources(): List<File> {
        val root = File(System.getProperty("obsidian.src") ?: "src/main/kotlin")
        assertTrue("не знайдено джерела: ${root.absolutePath}", root.isDirectory)
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    @Test
    fun `only the token file may spell a colour`() {
        val files = sources()
        assertTrue("прохід не побачив жодного файла", files.size >= 6)

        val offenders = files
            .filter { it.name != "Tokens.kt" }
            .flatMap { file ->
                file.readLines().withIndex()
                    .filter { (_, line) -> hex.containsMatchIn(line) }
                    .map { (i, line) -> "${file.name}:${i + 1} ${line.trim()}" }
            }

        assertEquals(
            "колір поза Tokens.kt — палітра почала розповзатись",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `the token file really is where the colours live`() {
        val tokens = sources().first { it.name == "Tokens.kt" }
        assertTrue(
            "у Tokens.kt менше кольорів, ніж рядків у таблиці — перевірка стала порожньою",
            hex.findAll(tokens.readText()).count() >= Tokens.colors.size,
        )
    }
}
