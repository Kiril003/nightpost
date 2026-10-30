package local.phantom.companion.core.tool

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Every `argsSchema` type word must be one [ToolArgsValidator] understands.
 *
 * The schema is now enforced at dispatch, so a typo in a type word silently
 * turns into "accept anything" — the exact hole this closes. Source scan
 * rather than a registry walk because tools live across three modules and
 * most need a Context to construct.
 */
class SchemaVocabularyTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "core-symbiote").isDirectory) dir = dir.parentFile
        return dir ?: fail("repo root not found") as Nothing
    }

    private val schemaBlock =
        Regex("""override val argsSchema[^=]*=\s*buildJsonObject\s*\{(.*?)\n\s{4}\}""", RegexOption.DOT_MATCHES_ALL)
    private val entry =
        Regex("""put\(\s*"([^"]+)"\s*,\s*(?:kotlinx\.serialization\.json\.)?JsonPrimitive\(\s*"([^"]*)"\s*\)""")

    @Test
    fun `every schema type word is one the validator knows`() {
        val offenders = mutableListOf<String>()
        var checked = 0
        for (module in listOf("core-symbiote", "app", "feature-stream")) {
            val dir = File(repoRoot(), "$module/src/main")
            if (!dir.isDirectory) continue
            dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
                schemaBlock.findAll(f.readText()).forEach { block ->
                    entry.findAll(block.groupValues[1]).forEach { e ->
                        val (key, raw) = e.destructured
                        checked++
                        val word = raw.trim().substringBefore(' ').substringBefore('—')
                            .trim().removeSuffix("?").lowercase()
                        if (word !in ToolArgsValidator.KNOWN_TYPES) {
                            offenders += "${f.name}: '$key' declares '$raw'"
                        }
                    }
                }
            }
        }
        assertTrue("no schemas found — the scan is broken, not the code", checked > 100)
        assertTrue(
            "argsSchema type words outside ${ToolArgsValidator.KNOWN_TYPES}:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }
}
