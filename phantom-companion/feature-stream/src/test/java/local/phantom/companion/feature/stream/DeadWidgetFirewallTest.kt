package local.phantom.companion.feature.stream

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Task #81 — the demolished surfaces stay demolished.
 *
 * Each name below had a renderer, a type constant, a history branch and a
 * debug-gallery entry, and NO production emitter: nothing a user could do
 * ever produced one. The cost of that is not bytes — it is that every later
 * reader has to re-derive whether the thing is real.
 *
 * GATE LAW (shared with the core-design firewalls): this test reads sources
 * as raw file I/O, invisible to Gradle's up-to-date check. Run with
 * `--rerun` at a wave gate.
 */
class DeadWidgetFirewallTest {

    /** Widget `type` strings removed with their renderers. */
    private val demolishedTypes = listOf(
        "ar-map",
        "vitals-constellation",
        "code-block",
        "audio-waveform",
        "file-transfer",
        "spreadsheet-preview",
        "model-3d",
        "reasoning",
    )

    /**
     * `LifeLogEntryEntity` (the Room row behind LifeGraphScreen) is alive and
     * must not be caught, so the qualifier is part of the pattern.
     */
    private val demolishedItems = listOf(
        "StreamItem.PluginSurface",
        "StreamItem.LifeLogEntry",
        "StreamItem.VisionFeed",
    )

    private val roots = listOf("feature-stream/src/main", "feature-godmode/src/main")

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("repo root with feature-stream/ not found") as Nothing
    }

    private fun kotlinFiles(): List<File> = roots.flatMap { rel ->
        val dir = File(repoRoot(), rel)
        if (!dir.isDirectory) emptyList()
        else dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private fun hitsFor(needles: List<String>, quoted: Boolean): List<String> {
        val out = mutableListOf<String>()
        for (file in kotlinFiles()) {
            file.readText().lineSequence().forEachIndexed { i, line ->
                val t = line.trimStart()
                if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) return@forEachIndexed
                for (n in needles) {
                    val probe = if (quoted) "\"$n\"" else n
                    if (probe in line) out += "${file.name}:${i + 1}  $n"
                }
            }
        }
        return out
    }

    @Test
    fun `demolished widget types have no live mention`() {
        val hits = hitsFor(demolishedTypes, quoted = true)
        assertTrue(
            "a widget type with no production emitter came back:\n" +
                hits.joinToString("\n") { "  $it" } +
                "\nIf it now has a real emitter, name the call path here and drop it from the list.",
            hits.isEmpty(),
        )
    }

    @Test
    fun `demolished StreamItem subtypes have no live mention`() {
        val hits = hitsFor(demolishedItems, quoted = false)
        assertTrue(
            "a StreamItem subtype with no constructor came back:\n" +
                hits.joinToString("\n") { "  $it" },
            hits.isEmpty(),
        )
    }

}
