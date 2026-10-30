package local.phantom.companion.core.tool.impl

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Закон власника 26.09 доходить і сюди: асистент переказує людині те, що
 * повернув інструмент, дослівно або майже. «Небо під сумнівом», «Overpass не
 * відповів», «фікс ±12 м» звучали в розмові як механіка. Опис інструмента
 * (`description`) і поля `put(…)` читає лише модель — вони поза сторожем.
 */
class ToolWordsNoMechanicsTest {

    private val root = File("src/main/java/local/phantom/companion/core/tool/impl")

    @Test
    fun `інструменти мапи й місця говорять людською мовою`() {
        val files = root.listFiles { f -> f.extension == "kt" && f.name !in MESSENGER }!!.toList()
        assertTrue("інструментів не знайдено — сторож дивиться в порожнечу", files.size > 40)
        val hits = files.flatMap { file ->
            val lines = file.readLines()
            var inDescription = false
            lines.withIndex().flatMap { (i, line) ->
                val t = line.trim()
                if (inDescription) {
                    inDescription = t.endsWith("+")
                    return@flatMap emptyList()
                }
                if (MODEL_ONLY.containsMatchIn(line)) {
                    inDescription = t.endsWith("=") || t.endsWith("+")
                    return@flatMap emptyList()
                }
                if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") || PUT.containsMatchIn(line)) {
                    return@flatMap emptyList()
                }
                LITERAL.findAll(line.substringBefore(" // ")).map { it.groupValues[1] }
                    .filter { CYRILLIC.containsMatchIn(it) }
                    .mapNotNull { s -> FORBIDDEN.find(s)?.let { "${file.name}:${i + 1} «${it.value}» у «$s»" } }
                    .toList()
            }
        }
        assertEquals("механіка в словах інструментів", emptyList<String>(), hits)
    }

    private companion object {
        // Ключі й звірка — мова месенджера; її веде інша сесія.
        val MESSENGER = setOf("PeopleTools.kt")
        val MODEL_ONLY = Regex("""\b(description|displayName)\s*[:=]""")
        val PUT = Regex("""\bput(Provenance)?\(""")
        val LITERAL = Regex(""""((?:[^"\\]|\\.)*)"""")
        val CYRILLIC = Regex("[А-Яа-яІіЇїЄєҐґ]")
        // «Небо» не в списку: погода й літаки говорять про справжнє небо.
        private const val UA = "А-Яа-яІіЇїЄєҐґ"
        val FORBIDDEN = Regex(
            listOf(
                """GPS""", """GNSS""", """HDOP""", """\bOSM\b""", """Overpass""", """OSRM""", """ADS-B""",
                """\bEW\b""", """±\s*\d""", """тайл""", """супутник""", """звірен""", """відбит(ок|ки|ків)""",
                """(?<![$UA])фікс(?:у|ом|и|ів)?(?![$UA])""",
            ).joinToString("|"),
        )
    }
}
