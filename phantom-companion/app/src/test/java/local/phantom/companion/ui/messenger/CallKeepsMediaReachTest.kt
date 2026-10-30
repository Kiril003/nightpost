package local.phantom.companion.ui.messenger

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** WebMessengerHost is a Composable; `:app` composes nothing in tests, so the wiring is read from source. */
class CallKeepsMediaReachTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "app/src/main/java").isDirectory }

    private fun code(path: String) = File(root, "app/src/main/java/local/phantom/companion/$path").readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""//[^\n]*"""), "")

    private val host = code("ui/messenger/WebMessengerHost.kt")

    @Test
    fun `the page says it is ready before call frames are handed to it`() {
        assertTrue("the host never polls for the call sink", host.contains("awaitCallSink("))
        assertTrue("the poll never reaches the hub", Regex("""callHub(::|\.)webReady""").containsMatchIn(host))
    }

    @Test
    fun `remote audio plays without a tap inside the page`() {
        assertTrue(host.contains("mediaPlaybackRequiresUserGesture = false"))
        assertFalse(host.contains("mediaPlaybackRequiresUserGesture = true"))
    }

    @Test
    fun `a call keeps the webview running behind other tabs`() {
        val sleep = Regex("""LaunchedEffect\(webView, active[^)]*\)\s*\{.*?\n    \}""", RegexOption.DOT_MATCHES_ALL)
            .find(host)?.value.orEmpty()
        assertTrue("sleep effect not found", sleep.contains("webView.onPause()"))
        assertTrue("the webview sleeps even while a call is open", sleep.contains("if (active || callOpen)"))
        assertTrue(host.contains("callHub.lineOpen"))
    }
}
