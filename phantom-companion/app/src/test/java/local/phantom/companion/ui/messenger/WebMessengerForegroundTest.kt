package local.phantom.companion.ui.messenger

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DL-2 (ніч 09.10): «прочитано» в відправника, поки телефон адресата на
 * робочому столі. Хост мусить казати сторінці, що застосунок у фоні, а бандл —
 * слухати саме цього приймача.
 */
class WebMessengerForegroundTest {

    private val mono: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "phantom-os/src/frontend/src").isDirectory }

    @Test
    fun `the push names the sink and the state`() {
        assertEquals("window.__phantomForeground && window.__phantomForeground(false);", webForegroundPushJs(false))
        assertEquals("window.__phantomForeground && window.__phantomForeground(true);", webForegroundPushJs(true))
    }

    @Test
    fun `the host pushes background on ON_STOP and foreground on ON_START`() {
        val host = File(mono, "phantom-companion/app/src/main/java/local/phantom/companion/ui/messenger/WebMessengerHost.kt").readText()
        assertTrue("хост не каже сторінці про фон", Regex("""ON_STOP\s*->\s*webView\.evaluateJavascript\(webForegroundPushJs\(false\)""").containsMatchIn(host))
        assertTrue("хост не каже сторінці про повернення", Regex("""ON_START\s*->\s*webView\.evaluateJavascript\(webForegroundPushJs\(true\)""").containsMatchIn(host))
        assertTrue("спостерігач не підписаний на життя застосунку", "lifecycle.addObserver(watch)" in host)
    }

    @Test
    fun `the bundle declares the same sink and gates reads on it`() {
        val sink = File(mono, "phantom-os/src/frontend/src/services/appForeground.ts").readText()
        assertTrue(Regex("""FOREGROUND_SINK\s*=\s*'$WEB_FOREGROUND_SINK'""").containsMatchIn(sink))
        val store = File(mono, "phantom-os/src/frontend/src/stores/messengerStore.ts").readText()
        assertTrue("стор розчитує, не питаючи, чи людина бачить", "if (appSeen())" in store)
    }
}
