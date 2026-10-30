package local.phantom.companion.ui.messenger

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * K3 reach: the day door is wired into the real bridge, its data comes from the real
 * stores, and the calendar permission is asked from exactly one place.
 *
 * GATE LAW: reads sources as text, comments stripped.
 */
class WebMessengerDayReachTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("tree root not found") as Nothing
    }

    private val main get() = File(repoRoot(), "app/src/main/java/local/phantom/companion")

    private fun code(f: File): String = f.readText().lineSequence()
        .filterNot { val t = it.trimStart(); t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") }
        .joinToString("\n")

    private fun code(rel: String): String = code(File(main, rel))

    @Test
    fun `the bridge builds the door from the container and routes its paths to it`() {
        val bridge = code("ui/messenger/WebMessengerBridge.kt")
        assertTrue(bridge.contains("private val day by lazy { WebMessengerDay.of(container) }"))
        assertTrue(bridge.contains("if (day.claims(seg)) return day.handle(method, seg, body)"))
    }

    @Test
    fun `the door claims its segment on one line and reads the real stores`() {
        val door = code("ui/messenger/WebMessengerDay.kt")
        assertTrue(door.contains("fun claims(seg: List<String>): Boolean = seg.size >= 2 && seg[1] == \"day\""))
        val of = door.substringAfter("fun of(container: AppContainer)")
        assertTrue(of.contains("container.commitments"))
        assertTrue(of.contains("container.reminderStore"))
        assertTrue(of.contains("PhoneCalendar(container.applicationContext)"))
    }

    @Test
    fun `the permission window is shown by the host and the page is told the answer`() {
        val host = code("ui/messenger/WebMessengerHost.kt")
        assertTrue(host.contains("CalendarPermit.requests.collect"))
        assertTrue(host.contains("CalendarPermit.PERMISSION"))
        assertTrue(host.contains("__phantomDayChanged"))
    }

    @Test
    fun `the calendar permission is asked only from the day door, never at start`() {
        val askers = main.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && code(it).contains("CalendarPermit.ask(") }
            .map { it.name }
            .toList()
        assertEquals(listOf("WebMessengerDay.kt"), askers)
    }
}
