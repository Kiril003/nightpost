package local.phantom.companion.feature.stream.ui.map

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Закон власника 26.09: «навіщо мені як користувачу щось чути про відбитки…
 * людині треба показувати щось гарне». Механіка не повертається на мапу —
 * ні рядком ресурсу, ні літералом у коді ui/map.
 */
class MapNoMechanicsTest {

    private val root = File("src/main/java/local/phantom/companion/feature/stream/ui/map")

    @Test
    fun `рядки мапи не говорять мовою механіки`() {
        val keys = root.walk().filter { it.extension == "kt" }
            .flatMap { KEY.findAll(it.readText()).map { m -> m.groupValues[1] } }
            .toSet()
        assertTrue("ключі мапи не знайдено — сторож дивиться в порожнечу", keys.size > 300)
        val hits = listOf("values-uk", "values").flatMap { loc ->
            File("src/main/res/$loc").listFiles { f -> f.name.startsWith("strings") }!!.flatMap { file ->
                ENTRY.findAll(file.readText())
                    .filter { it.groupValues[2] in keys }
                    .mapNotNull { m -> FORBIDDEN.find(m.groupValues[3])?.let { "$loc/${m.groupValues[2]}: «${it.value}» у «${m.groupValues[3]}»" } }
                    .toList()
            }
        }
        assertEquals("механіка в рядках мапи", emptyList<String>(), hits)
    }

    @Test
    fun `літерали ui map не говорять мовою механіки`() {
        val hits = root.walk()
            .filter { it.extension == "kt" && "prefetch" !in it.path }
            .flatMap { file ->
                val lines = file.readLines()
                lines.withIndex().flatMap { (i, line) ->
                    val t = line.trim()
                    if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) return@flatMap emptyList()
                    if (INTERNAL.containsMatchIn(line) || (i > 0 && INTERNAL.containsMatchIn(lines[i - 1]))) {
                        return@flatMap emptyList()
                    }
                    LITERAL.findAll(line.substringBefore(" // ")).map { it.groupValues[1].replace(TEMPLATE, "") }
                        .filter { CYRILLIC.containsMatchIn(it) }
                        .mapNotNull { s -> FORBIDDEN.find(s)?.let { "${file.name}:${i + 1} «${it.value}» у «$s»" } }
                        .toList()
                }
            }.toList()
        assertEquals("механіка в літералах мапи", emptyList<String>(), hits)
    }

    private companion object {
        val KEY = Regex("""R\.(?:string|plurals)\.([a-z0-9_]+)""")
        val ENTRY = Regex("""<(string|plurals) name="([^"]+)"[^>]*>(.*?)</\1>""", RegexOption.DOT_MATCHES_ALL)
        val LITERAL = Regex(""""((?:[^"\\]|\\.)*)"""")
        val TEMPLATE = Regex("""\$\{[^}]*\}?""")
        val CYRILLIC = Regex("[А-Яа-яІіЇїЄєҐґ]")
        val INTERNAL = Regex("""\b(require|check|error|Log\.[a-z])\s*\(""")

        // \b у Java не бачить кирилиці як слова — межі для неї задано явно.
        val FORBIDDEN = Regex(
            listOf(
                """GPS""", """GNSS""", """HDOP""", """EPSG""", """NMEA""", """\bOSM\b""", """\bWMM\b""",
                """\b[rR]8\b""", """\bH3\b""", """[Rr]elay""", """сховок""", """ключ""",
                """звірен""", """звірк""", """незвір""", """відбит(ок|ки|ків)""", """[Ff]ingerprint""", """неб[оа](?![А-Яа-яІіЇїЄєҐґ])""", """супутник(и|ів)""",
                """тайл""", """\bz\d""", """(?<![А-Яа-яІіЇїЄєҐґ])фід(?![а-яіїєґ])""", """\bfeed\b""", """±\s*\d""",
                """\b[0-9A-Fa-f]{8,}\b""", """Exception""", """\bnull\b""",
                // Регістр землі — внутрішня мова мапи; людині лишилось лише «мапа давня».
                """виміряно""", """похідне""", """здогад""", """спогад""", """регістр""",
                """\b(measured|derived|guessed|remembered)\b""",
            ).joinToString("|"),
        )
    }
}
