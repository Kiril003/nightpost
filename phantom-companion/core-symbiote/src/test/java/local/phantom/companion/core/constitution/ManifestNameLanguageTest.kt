package local.phantom.companion.core.constitution

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * A manifest's `displayName` is not internal. `AxiomAuditor` copies it into
 * the verdict and the diagnostics card renders it verbatim, so the first
 * time a feature is refused the operator reads that string — and 42 of them
 * said «Notification», «Settings», «File», «Launch», while one passed the
 * raw tool id («mission.pause») as if it were a name.
 *
 * The rule is the product's rule: Ukrainian first. A name with no Cyrillic
 * in it has not been written for the person who reads it.
 */
class ManifestNameLanguageTest {

    /**
     * Names that are proper nouns or protocol tokens the whole way through.
     * A word only belongs here if translating it would make it WORSE, and
     * each entry must still carry a Ukrainian noun beside it in the source.
     */
    private val allowed = setOf<String>()

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "core-design").isDirectory) dir = dir.parentFile
        return dir ?: fail("repo root not found") as Nothing
    }

    private val cyrillic = Regex("""\p{IsCyrillic}""")

    @Test
    fun `every manifest display name is written for a Ukrainian operator`() {
        val root = repoRoot()
        val roots = listOf("core-symbiote/src/main", "app/src/main", "feature-godmode/src/main")
        val decl = Regex("""displayName\s*=\s*"((?:[^"\\]|\\.)*)"""")

        var checked = 0
        val strangers = mutableListOf<String>()
        for (r in roots) {
            val dir = File(root, r)
            if (!dir.isDirectory) continue
            dir.walkTopDown().filter { it.extension == "kt" }.forEach { f ->
                f.readLines().forEachIndexed { i, line ->
                    if (line.trimStart().startsWith("//") || line.trimStart().startsWith("*")) return@forEachIndexed
                    val m = decl.find(line) ?: return@forEachIndexed
                    val name = m.groupValues[1]
                    // A template that interpolates a runtime value carries its
                    // language in the literal part; judge that part only.
                    val literal = name.replace(Regex("""\$\{[^}]*\}"""), "").replace(Regex("""\$\w+"""), "")
                    if (literal.isBlank()) return@forEachIndexed
                    checked++
                    if (name in allowed) return@forEachIndexed
                    if (!cyrillic.containsMatchIn(literal)) {
                        strangers += "${f.name}:${i + 1}  \"$name\""
                    }
                }
            }
        }

        assertTrue("found no displayName declarations — the scan broke, not the code", checked >= 20)
        if (strangers.isNotEmpty()) {
            fail(
                "these names reach the operator through the constitution refusal card, " +
                    "in a language the product does not speak:\n" + strangers.joinToString("\n") { "  $it" },
            )
        }
    }
}
