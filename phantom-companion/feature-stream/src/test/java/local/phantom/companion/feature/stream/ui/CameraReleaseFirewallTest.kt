package local.phantom.companion.feature.stream.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.fail

/**
 * A camera nobody closes.
 *
 * `bindToLifecycle` hands the device to CameraX and nothing takes it back until
 * either the bound lifecycle is destroyed or somebody unbinds. A surface that
 * leaves composition while its destination is still alive — a licence flipping
 * the screen to its locked state, a permission branch swapping the preview out —
 * takes the preview away and leaves the camera running: the indicator lit with
 * nothing visibly using it, and the next app that asks for the camera refused.
 *
 * The check is structural because the bug was: every one of these files already
 * calls `unbindAll()` at the head of its bind block, so a scan that only asked
 * whether the word appears passes on a file that never releases anything. What
 * has to be true is narrower — the release sits on the dispose path.
 *
 * GATE LAW (shared with the peer firewalls): this reads other modules' sources
 * as raw file I/O, invisible to Gradle's up-to-date check. Run with
 * `--rerun-tasks`, as scripts/verify.sh does.
 */
class CameraReleaseFirewallTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("repo root with feature-stream/ not found") as Nothing
    }

    private fun mainSourceRoots(): List<File> =
        repoRoot().listFiles().orEmpty()
            .map { File(it, "src/main") }
            .filter { it.isDirectory }

    private fun kotlinFiles(): List<File> = mainSourceRoots()
        .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" } }

    /**
     * Comments, string bodies and char literals blanked, length preserved.
     *
     * Without this the scan reads «unbind» out of a KDoc paragraph explaining
     * why unbinding matters and calls the file safe — and a brace inside a
     * comment throws off the block matching below.
     */
    private fun codeOnly(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        fun blank(c: Char) = out.append(if (c == '\n') '\n' else ' ')
        while (i < text.length) {
            val c = text[i]
            val next = if (i + 1 < text.length) text[i + 1] else '\u0000'
            when {
                c == '/' && next == '/' -> {
                    while (i < text.length && text[i] != '\n') blank(text[i++])
                }
                c == '/' && next == '*' -> {
                    var depth = 0
                    while (i < text.length) {
                        if (text[i] == '/' && i + 1 < text.length && text[i + 1] == '*') {
                            depth++
                            out.append("  ")
                            i += 2
                        } else if (text[i] == '*' && i + 1 < text.length && text[i + 1] == '/') {
                            depth--
                            out.append("  ")
                            i += 2
                            if (depth == 0) break
                        } else {
                            blank(text[i++])
                        }
                    }
                }
                text.startsWith("\"\"\"", i) -> {
                    out.append("   ")
                    i += 3
                    while (i < text.length && !text.startsWith("\"\"\"", i)) blank(text[i++])
                    if (i < text.length) {
                        out.append("   ")
                        i += 3
                    }
                }
                c == '"' || c == '\'' -> {
                    val quote = c
                    blank(text[i++])
                    while (i < text.length && text[i] != quote) {
                        if (text[i] == '\\' && i + 1 < text.length) blank(text[i++])
                        blank(text[i++])
                    }
                    if (i < text.length) blank(text[i++])
                }
                else -> out.append(text[i++])
            }
        }
        return out.toString()
    }

    /** The brace-balanced block starting at [open], which must index a `{`. */
    private fun block(code: String, open: Int): String {
        var depth = 0
        var i = open
        while (i < code.length) {
            if (code[i] == '{') depth++
            if (code[i] == '}') {
                depth--
                if (depth == 0) return code.substring(open, i + 1)
            }
            i++
        }
        return code.substring(open)
    }

    private fun releasesOnDispose(code: String): Boolean =
        Regex("""onDispose\s*\{""").findAll(code)
            .any { block(code, it.range.last).contains("unbindAll(") }

    @Test
    fun everyCameraBindIsReleasedOnDispose() {
        val binders = kotlinFiles()
            .filter { it.readText().contains("bindToLifecycle") }
            .map { it to codeOnly(it.readText()) }
            .filter { (_, code) -> code.contains("bindToLifecycle(") }

        // Єдина поверхня камери на весь застосунок — LiveQrCamera: PairScreen,
        // WebSignInScreen, PeerScanSheet і ScanScreen використовують її, а не
        // власні копії bindToLifecycle.
        assertEquals(
            "one camera surface: a second bindToLifecycle is a copy of LiveQrCamera",
            setOf("LiveQrCamera.kt"),
            binders.map { it.first.name }.toSet(),
        )

        val leaking = binders
            .filterNot { (_, code) -> releasesOnDispose(code) }
            .map { it.first.relativeTo(repoRoot()).path }

        assertTrue(
            "камеру відкрито і не звільнено / camera opened and never released — " +
                "a file calling bindToLifecycle( must release it from an " +
                "onDispose { } block that calls unbindAll(). An unbindAll() at " +
                "the head of the bind block does not count: it does not run on " +
                "the way out. Leaking: $leaking",
            leaking.isEmpty(),
        )
    }

    /**
     * The scanner itself, against the exact shape that fooled the earlier one.
     */
    @Test
    fun scanRejectsAnUnbindThatIsNotOnTheDisposePath() {
        val leaks = """
            // onDispose { provider.unbindAll() } would be the fix
            /** Docs saying onDispose { unbindAll() } matter. */
            LaunchedEffect(view) {
                val note = "onDispose { provider.unbindAll() }"
                provider.unbindAll()
                provider.bindToLifecycle(owner, selector, preview)
            }
        """.trimIndent()
        val releases = """
            DisposableEffect(view) {
                provider.unbindAll()
                provider.bindToLifecycle(owner, selector, preview)
                onDispose { runCatching { bound?.unbindAll() } }
            }
        """.trimIndent()

        assertEquals(false, releasesOnDispose(codeOnly(leaks)))
        assertEquals(true, releasesOnDispose(codeOnly(releases)))
        assertTrue(codeOnly(leaks).contains("bindToLifecycle("))
    }
}
