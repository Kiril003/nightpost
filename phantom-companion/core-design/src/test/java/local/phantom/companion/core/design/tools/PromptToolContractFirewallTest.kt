package local.phantom.companion.core.design.tools

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The system prompt hands the model a list of instruments by name — «спершу
 * виклич `reminder.add`», «рядки бери з результату task.list». Those names are
 * plain Ukrainian prose in a Kotlin string; nothing links them to the tools
 * that actually exist. Rename `place.save` and the prompt keeps confidently
 * telling the model to call it, the call dead-ends, and the operator gets a
 * reminder that was never set.
 *
 * The rule reads the shape, not a list: a dotted token whose namespace belongs
 * to a real tool family (`place.*`, `task.*`, `map.*` …) must name a real tool.
 * Code and prose survive it untouched — `it.trim`, `com.spotify` and
 * `facts.map` have no tool family behind them.
 *
 * GATE LAW (shared with the other firewalls): this reads other modules'
 * sources as raw file I/O, invisible to Gradle's up-to-date check. Run with
 * `--rerun-tasks` at every gate.
 */
class PromptToolContractFirewallTest {

    private val idDecl = Regex("""override val id(?::\s*String)?\s*=\s*"([^"]+)"""")
    private val dotted = Regex("""\b([a-z_]+)\.([a-z_]+)\b""")

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "core-symbiote").isDirectory) dir = dir.parentFile
        return dir ?: fail("repo root with core-symbiote/ not found") as Nothing
    }

    private fun registeredIds(root: File): Set<String> {
        val impls = File(root, "core-symbiote/src/main/java/local/phantom/companion/core/tool/impl")
        assertTrue("tool impl directory missing: $impls", impls.isDirectory)
        return impls.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { f -> idDecl.findAll(f.readText()).map { it.groupValues[1] } }
            .toSet()
    }

    @Test
    fun `every instrument the prompt names is an instrument that exists`() {
        val root = repoRoot()
        val ids = registeredIds(root)
        assertTrue("no tools found — the id regex stopped matching", ids.size > 30)

        val families = ids.mapNotNull { it.substringBefore('.').takeIf { _ -> '.' in it } }.toSet()
        val prompts = File(root, "core-ai/src/main/java/local/phantom/companion/core/ai/SystemPrompts.kt")
        assertTrue("SystemPrompts.kt missing at $prompts", prompts.isFile)

        val unknown = prompts.readText().lineSequence().withIndex().flatMap { (i, line) ->
            dotted.findAll(line)
                .map { it.value }
                .filter { it.substringBefore('.') in families && it !in ids }
                .map { "SystemPrompts.kt:${i + 1}  $it" }
        }.distinct().toList()

        assertEquals(
            "the prompt tells the model to call an instrument that is not registered:\n" +
                unknown.joinToString("\n") { "    $it" } +
                "\n\nEither the tool was renamed and the prompt was not, or the name is a " +
                "typo. A call to a name nothing answers is silence with a confident tone.",
            emptyList<String>(),
            unknown,
        )
    }
}
