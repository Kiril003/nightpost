package local.phantom.companion.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** sh -c під uid застосунку читає будь-який приватний файл, зокрема сховок гаманця: у field/store його немає. */
class ShellOnlyInDebugTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: error("no repo root")
    }

    @Test
    fun `release builds carry no shell and never even look for termux`() {
        assertTrue(devOnlyTools(debug = false) { error("termux probed in release") }.isEmpty())
        assertEquals(listOf("shell"), devOnlyTools(debug = true) { false }.map { it.id })
    }

    @Test
    fun `the registry takes shell only through the debug gate`() {
        val root = repoRoot()
        val factory = File(root, "app/src/main/java/local/phantom/companion/data/ToolRegistryFactory.kt").readText()
        assertTrue(factory.contains("devOnlyTools(local.phantom.companion.BuildConfig.DEBUG)"))
        val builders = root.walkTopDown()
            .onEnter { it.name != "build" && it.name != ".git" && it.name != "test" }
            .filter { it.isFile && it.extension == "kt" && it.path.contains("/src/main/") }
            .flatMap { f -> f.readLines().filter { "ShellTool(" in it && "class ShellTool(" !in it }.map { f.name + ": " + it.trim() } }
            .toList()
        assertEquals(listOf("ToolRegistryFactory.kt: if (debug) listOf(local.phantom.companion.core.tool.impl.ShellTool(termuxAvailable = termux())) else emptyList()"), builders)
    }
}
