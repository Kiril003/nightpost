package local.phantom.companion.ui.messenger

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** «Тиша вночі»: міст віддає й приймає години, а шторка й дзвінок їх справді слухають. */
class WebMessengerQuietReachTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: error("не знайшов корінь дерева (каталог feature-stream)")
    }

    private fun code(rel: String): String = File(repoRoot(), rel).readText().lineSequence()
        .filterNot { val t = it.trimStart(); t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") }
        .joinToString("\n")

    private val app = "app/src/main/java/local/phantom/companion"

    @Test
    fun `міст має GET і PATCH тихих годин`() {
        val bridge = code("$app/ui/messenger/WebMessengerBridge.kt")
        assertTrue(bridge.contains("""seg[1] == "quiet" && seg.size == 2 && method == "GET" -> ok(quietJson())"""))
        assertTrue(bridge.contains("""seg[1] == "quiet" && seg.size == 2 && method == "PATCH" -> patchQuiet(body)"""))
        assertTrue(bridge.contains("container.quietHours.set(next)"))
    }

    @Test
    fun `лист і дзвінок питають тишу про автора`() {
        val container = code("$app/data/AppContainer.kt")
        assertTrue("лист у тишу дзвенить", container.contains("quiet = notice.quiet || quietHours.hushesNow(notice.fromId) &&"))
        assertTrue("згадка не проходить тишу", container.contains("!local.phantom.companion.service.PeerNotifications.mentionBreaks(applicationContext, notice)"))
        assertTrue("дзвінок у тишу дзвенить", container.contains("?.let(quietHours::hushesNow) == true"))

        val shade = code("$app/service/PeerNotifications.kt")
        assertTrue("плашка не глушиться", shade.contains(".setSilent(quiet)"))

        val calls = code("$app/service/PeerCallNotifications.kt")
        assertTrue(calls.contains("if (quiet) missed(context, callerName) else ringing(context, line, callerName)"))
        val missed = calls.substringAfter("private fun missed(").substringBefore("private fun ongoing(")
        assertTrue("пропущений дзвенить", missed.contains(".setSilent(true)"))
        assertTrue("пропущений підіймає апарат", !missed.contains("setFullScreenIntent"))
    }
}
