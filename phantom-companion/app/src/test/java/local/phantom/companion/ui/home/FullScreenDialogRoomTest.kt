package local.phantom.companion.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Кожен Dialog на всю ширину кладе вміст у видиму частину вікна, інакше на Android 15+ його низ іде під смуги. */
class FullScreenDialogRoomTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "app/src/main/java").isDirectory }

    private val wide = Regex("""DialogProperties\([^)]*usePlatformDefaultWidth\s*=\s*false""")

    // Звіт мапи без нижньої дії й без входу з жодного екрана — лишається як є.
    private val exempt = setOf("ReportSurface.kt")

    @Test
    fun `full-width dialogs size their content to the visible window`() {
        val files = root.listFiles()!!.filter { File(it, "src/main/java").isDirectory }
            .flatMap { File(it, "src/main/java").walkTopDown().filter { f -> f.extension == "kt" }.toList() }
            .filter { wide.containsMatchIn(it.readText()) }
        assertEquals(
            setOf("ProfileBanner.kt", "PeerScanSheet.kt", "OfflinePlacePicker.kt", "ReportSurface.kt", "LookKit.kt"),
            files.map { it.name }.toSet(),
        )
        files.filter { it.name !in exempt }.forEach { f ->
            val s = f.readText()
            assertTrue("${f.name}: вміст не у видимій частині вікна", "fillDialogRoom()" in s || "rememberDialogRoom()" in s)
        }
    }
}
