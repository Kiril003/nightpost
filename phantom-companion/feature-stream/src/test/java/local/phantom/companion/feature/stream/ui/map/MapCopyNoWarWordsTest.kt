package local.phantom.companion.feature.stream.ui.map

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class MapCopyNoWarWordsTest {

    private val stems = listOf(
        "patrol", "checkpoint", "tactical", "exfil", "UAV", "missile", "ballistic", "glide bomb",
        "artillery", "combat", "radiation", "MiG", "threat",
        "патрул", "блокпост", "тактич", "загроз", "ракет", "шахед", "КАБ",
        "hlídk", "hrozb", "taktick", "блекаут", "blackout",
    )
    private val caseSensitive = setOf("UAV", "MiG", "КАБ")

    // «threatens the operator's independence» — аксіома, не війна.
    private val allowedKeys = setOf("widget_refusal_axiom_operator_advocate", "diag_axiom_operator_advocate")

    private val root: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "feature-stream/src/main/res").isDirectory }

    private fun hits(value: String): List<String> = stems.filter { stem ->
        val word = stem in caseSensitive
        val opts = if (word) emptySet() else setOf(RegexOption.IGNORE_CASE)
        Regex("(?<!\\p{L})" + Regex.escape(stem) + if (word) "(?!\\p{L})" else "", opts).containsMatchIn(value)
    }

    private fun strings(): List<Triple<String, String, String>> =
        listOf("feature-stream", "app").flatMap { module ->
            File(root, "$module/src/main/res").listFiles { f -> f.name.startsWith("values") }.orEmpty()
                .flatMap { dir -> dir.listFiles { f -> f.extension == "xml" }.orEmpty().toList() }
                .flatMap { file ->
                    val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
                    listOf("string", "item").flatMap { tag ->
                        val nodes = doc.getElementsByTagName(tag)
                        (0 until nodes.length).map { nodes.item(it) as Element }
                    }.map { e ->
                        val key = e.getAttribute("name").ifEmpty { (e.parentNode as? Element)?.getAttribute("name").orEmpty() }
                        Triple("${file.parentFile.name}/${file.name}", key, e.textContent)
                    }
                }
        }

    @Test
    fun `no war words in map and app copy - uk en cs`() {
        val all = strings()
        assertTrue("сканер не знайшов рядків", all.size > 1000)
        val found = all.filter { (_, key, value) -> key !in allowedKeys && hits(value).isNotEmpty() }
            .map { (file, key, value) -> "$file $key=${hits(value)}: $value" }
        assertEquals(emptyList<String>(), found)
    }

    private val literal = Regex(""""((?:[^"\\\n]|\\.)*)"""")
    private val inputMatcher = Regex("""contains\(|keywords|_UK = listOf|listOf\("""")

    private fun kotlinLiterals(): List<String> =
        listOf("feature-stream/src/main/java", "core-nav/src/main/java", "core-geo/src/main/java").flatMap { dir ->
            File(root, dir).walkTopDown()
                .filter { it.extension == "kt" && (!dir.startsWith("feature-stream") || "/ui/map/" in it.path) }
                .flatMap { file ->
                    file.readLines().withIndex().filter { (_, line) ->
                        val t = line.trim()
                        !t.startsWith("//") && !t.startsWith("*") && !t.startsWith("/*") && !inputMatcher.containsMatchIn(line)
                    }.flatMap { (i, line) ->
                        literal.findAll(line).map { it.groupValues[1].replace(Regex("""\$\{[^}]*}"""), "") }
                            .filter { v -> v.any { it in '\u0400'..'\u04FF' } || ' ' in v }
                            .map { "${file.name}:${i + 1} $it" }
                    }
                }
        }

    @Test
    fun `no war words in kotlin copy of the map, navigation and radar`() {
        val all = kotlinLiterals()
        assertTrue("сканер не знайшов літералів", all.size > 500)
        assertEquals(emptyList<String>(), all.filter { hits(it.substringAfter(' ')).isNotEmpty() })
    }

    @Test
    fun `the scanner catches what it must`() {
        assertEquals(listOf("патрул"), hits("Патруль"))
        assertEquals(listOf("threat"), hits("Ballistic Threat").filter { it == "threat" })
        assertEquals(emptyList<String>(), hits("Památky a vyhlídky"))
        assertEquals(emptyList<String>(), hits("КАБІНЕТ"))
        assertEquals(listOf("\"Попереду блокпост.\""), literal.findAll("""cue("Попереду блокпост.")""").map { it.value }.toList())
    }
}
