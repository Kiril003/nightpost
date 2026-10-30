package local.phantom.obsidian

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Документ токенів і код не мають права розійтися мовчки.
 *
 * `docs/atlas/OBSIDIAN_TOKENS_V0.md` — контракт на три поверхні (мобілка, ПК,
 * агент). Якщо мапа тихо підмінить один hex «бо так гарніше», решта продукту
 * лишиться зі старим, і власник побачить два різні продукти в одному застосунку.
 */
class TokensDocTest {

    private val doc: List<String> by lazy {
        val path = System.getProperty("obsidian.tokens.doc")
            ?: "../../docs/atlas/OBSIDIAN_TOKENS_V0.md"
        val file = File(path)
        assertTrue("не знайдено документ токенів: ${file.absolutePath}", file.isFile)
        file.readLines()
    }

    private val colorRow = Regex("""^\|\s*(ph\.color\.[a-z-]+)\s*\|\s*(\S+)\s*\|\s*(\S+)\s*\|\s*$""")
    private val typeRow = Regex("""^\|\s*(ph\.type\.[a-z]+)\s*\|\s*([\d.]+)\s*/\s*(\d+)\s*/\s*(\S+)\s*\|\s*$""")

    @Test
    fun `every colour token matches the document, both polarities`() {
        val fromDoc = doc.mapNotNull { colorRow.find(it) }
            .associate { it.groupValues[1] to (it.groupValues[2] to it.groupValues[3]) }

        assertTrue("у документі не знайдено таблиці кольорів", fromDoc.size >= 20)

        val drift = Tokens.colors.mapNotNull { token ->
            val doc = fromDoc[token.name] ?: return@mapNotNull "${token.name}: нема в документі"
            when {
                doc.first != token.night -> "${token.name} ніч: документ ${doc.first}, код ${token.night}"
                doc.second != token.day -> "${token.name} день: документ ${doc.second}, код ${token.day}"
                else -> null
            }
        }
        assertEquals("токени розійшлися з документом", emptyList<String>(), drift)
    }

    @Test
    fun `the code carries every colour row the document declares`() {
        val inDoc = doc.mapNotNull { colorRow.find(it)?.groupValues?.get(1) }
        val inCode = Tokens.colors.map { it.name }.toSet()
        assertEquals(
            "рядок документа не має відповідника в коді",
            emptyList<String>(),
            inDoc.filterNot { it in inCode },
        )
    }

    @Test
    fun `the type scale matches the document`() {
        val fromDoc = doc.mapNotNull { typeRow.find(it) }.associate {
            // У документі мінус — типографський U+2212, у Kotlin такого літерала нема.
            it.groupValues[1] to Triple(
                it.groupValues[2].toDouble(),
                it.groupValues[3].toInt(),
                it.groupValues[4].replace('−', '-').toDouble(),
            )
        }
        assertTrue("у документі не знайдено типографічної таблиці", fromDoc.size >= 5)

        val drift = Tokens.types.mapNotNull { t ->
            val d = fromDoc[t.name] ?: return@mapNotNull "${t.name}: нема в документі"
            if (d.first != t.size || d.second != t.weight || d.third != t.tracking) {
                "${t.name}: документ $d, код ${Triple(t.size, t.weight, t.tracking)}"
            } else {
                null
            }
        }
        assertEquals("типографіка розійшлася з документом", emptyList<String>(), drift)
    }
}
