package local.phantom.companion.navigation

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Copy lives in res/values* (en) and values-<tag>/, never in a Kotlin literal:
 * owner 29.10 saw Ukrainian on an English phone. Every module's src/main .kt
 * file is lexed (comments skipped, string templates followed), and a literal
 * with Cyrillic counts.
 *
 * `cyrillic-ledger.tsv` is the ratchet: a file may only lose literals, and a
 * file not in it must have none. What stays there is the assistant speech and
 * parsing layer (card _briefs/lang/L-VOICE.md).
 */
class NoHardcodedCyrillicTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "app/src/main/java").isDirectory }

    private val ledger: Map<String, Int> by lazy {
        val text = javaClass.classLoader!!.getResource("cyrillic-ledger.tsv")!!.readText()
        text.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }
            .associate { line -> line.split('\t').let { it[1] to it[0].toInt() } }
    }

    private fun found(): Map<String, List<String>> = root.walkTopDown()
        .onEnter { it.name != "build" && !it.name.startsWith(".") }
        .filter { it.isFile && it.extension == "kt" && "/src/main/" in it.invariantSeparatorsPath }
        .mapNotNull { f ->
            val text = f.readText()
            if (!CYRILLIC.containsMatchIn(text)) return@mapNotNull null
            val hits = cyrillicLiterals(text)
            if (hits.isEmpty()) null else f.relativeTo(root).invariantSeparatorsPath to hits
        }.toMap()

    @Test
    fun `no file gains a Cyrillic literal and files outside the ledger have none`() {
        val grown = found().filter { (f, hits) -> hits.size > (ledger[f] ?: 0) }
            .map { (f, hits) -> "$f (${hits.size} > ${ledger[f] ?: 0}): ${hits.take(3)}" }
        assertTrue("move these strings to res/values*:\n" + grown.joinToString("\n"), grown.isEmpty())
    }

    @Test
    fun `the ledger only shrinks`() {
        val now = found()
        val stale = ledger.filter { (f, n) -> (now[f]?.size ?: 0) < n }
            .map { (f, n) -> "$f: ledger $n, now ${now[f]?.size ?: 0}" }
        assertTrue("lower these lines in cyrillic-ledger.tsv:\n" + stale.joinToString("\n"), stale.isEmpty())
    }

    @Test
    fun `the lexer skips comments and reads templates`() {
        val src = "// коментар\n/* ще */ val a = \"Привіт \${f(\"x\")}\"\nval b = \"\"\"Рядок\"\"\"\nval c = 'ї'\nval d = \"ok\""
        assertTrue(cyrillicLiterals(src).toString(), cyrillicLiterals(src).size == 2)
    }

    companion object {
        private val CYRILLIC = Regex("[Ѐ-ӿ]")

        fun cyrillicLiterals(src: String): List<String> {
            val out = mutableListOf<String>()
            var i = 0
            val n = src.length
            while (i < n) {
                when {
                    src.startsWith("//", i) -> i = src.indexOf('\n', i).let { if (it < 0) n else it }
                    src.startsWith("/*", i) -> i = src.indexOf("*/", i + 2).let { if (it < 0) n else it + 2 }
                    src.startsWith("\"\"\"", i) -> {
                        var j = src.indexOf("\"\"\"", i + 3).let { if (it < 0) n else it + 3 }
                        while (j < n && src[j] == '"') j++
                        val body = src.substring(i + 3, maxOf(i + 3, j - 3))
                        if (CYRILLIC.containsMatchIn(body)) out += body.take(60)
                        i = j
                    }
                    src[i] == '"' -> {
                        var j = i + 1
                        var depth = 0
                        while (j < n) {
                            val c = src[j]
                            if (c == '\\') { j += 2; continue }
                            if (src.startsWith("\${", j)) { depth++; j += 2; continue }
                            if (depth > 0 && c == '}') { depth--; j++; continue }
                            if ((c == '"' || c == '\n') && depth == 0) break
                            j++
                        }
                        val body = src.substring(i + 1, minOf(j, n))
                        if (CYRILLIC.containsMatchIn(body)) out += body.take(60)
                        i = j + 1
                    }
                    src[i] == '\'' -> {
                        val close = src.indexOf('\'', if (i + 1 < n && src[i + 1] == '\\') i + 3 else i + 2)
                        i = if (close < 0) n else close + 1
                    }
                    else -> i++
                }
            }
            return out
        }
    }
}
