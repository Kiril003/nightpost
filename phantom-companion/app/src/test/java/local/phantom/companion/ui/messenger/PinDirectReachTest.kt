package local.phantom.companion.ui.messenger

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** P05: «Закріпити» в розмові удвох доходить із вебу в дріт (`PH-PIN:`) і назад у рядок. */
class PinDirectReachTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: error("не знайшов корінь дерева (каталог feature-stream)")
    }

    private fun code(rel: String): String = File(repoRoot(), rel).readText().lineSequence()
        .filterNot { val t = it.trimStart(); t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") }
        .joinToString("\n")

    private val bridge by lazy { code("app/src/main/java/local/phantom/companion/ui/messenger/WebMessengerBridge.kt") }

    @Test
    fun `маршрут pin веде в pinLine, а той шле кадр PIN співрозмовнику`() {
        assertTrue(bridge.contains("""seg[5] == "pin" && method == "POST" -> pinLine(seg[2], seg[4], body)"""))
        assertTrue(bridge.contains("PeerMark.Mark(PeerMark.What.PIN, messageId, o.optBoolean(\"on\"), PeerMark.PIN_VALUE)"))
        val pinLine = bridge.substringAfter("private fun pinLine(").substringBefore("private fun react(")
        assertTrue("pinLine не шле кадр", pinLine.contains("container.mesh.send(peerId, frame)"))
        assertTrue("старій збірці кадр поїхав би сирим", pinLine.contains("peer.sceneLevel < PeerProfile.SCENE_V1"))
    }

    @Test
    fun `рядок розмови несе pinned зі згортки журналу`() {
        assertTrue(bridge.contains("PeerMark.pinned(container.mesh.outbox.value, me, peerId)"))
        assertTrue(bridge.contains("""if (pins != null) o.put("pinned", m.id in pins)"""))
        assertTrue(bridge.contains("message(m, i + 1, peerId, marks, readPoint, cardFolded, pins)"))
    }
}
