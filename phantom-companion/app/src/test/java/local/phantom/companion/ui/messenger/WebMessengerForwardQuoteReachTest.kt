package local.phantom.companion.ui.messenger

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** P03: міст бере `fw`/`qt` з листа веба, кладе їх у кадр чату й групи і віддає назад у рядку. */
class WebMessengerForwardQuoteReachTest {

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
    fun `чат і група шлють автора пересланого й цитату`() {
        val bridge = code("$app/ui/messenger/WebMessengerBridge.kt")
        assertTrue(bridge.contains("container.mesh.send(peerId, composed, replyToId, quiet, fw, qt)"))
        assertTrue(bridge.contains("container.mesh.sendTyped(peerId, text, replyToId, quiet, fw, qt)"))
        assertTrue(bridge.contains("container.mesh.ingestUndeliverable(peerId, composed ?: text, replyToId, fw, qt)"))
        assertTrue(bridge.contains("container.mesh.sendTypedToGroup(group, text, replyToId, o.optBoolean(\"quiet\"), mentions, fw, qt)"))
        assertEquals(2, Regex("""sendToGroup\(group, framed\([a-z]+\), replyToId, o\.optBoolean\("quiet"\), readers, fw, qt\)""").findAll(bridge).count())
    }

    @Test
    fun `рядок чату й групи віддає fw і qt вебу`() {
        val bridge = code("$app/ui/messenger/WebMessengerBridge.kt")
        assertEquals(2, Regex("""\.put\("reply_to_id", [^\n]+\n\s+\.carried\(m\)""").findAll(bridge).count())
    }

    @Test
    fun `кадр кладе поля, а цитата без відповіді не їде`() {
        val mesh = code("$app/data/PeerMesh.kt")
        assertEquals(3, Regex("""fw = fw,\s+qt = if \(replyToId\.isBlank\(\)\) "" else qt""").findAll(mesh).count())
    }
}
