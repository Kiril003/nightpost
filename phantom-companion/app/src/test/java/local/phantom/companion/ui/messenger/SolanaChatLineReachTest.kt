package local.phantom.companion.ui.messenger

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Рядок переказу в списку розмов і речення під карткою складає
 * [local.phantom.companion.core.solana.SolanaNotice.chatLine]; людину в нього
 * кладе лише міст. Сторож читає джерело: доводить, що обидва входи (рядок
 * розмови й тіло листа) передають імʼя й напрям, а не лишають адресу гаманця.
 */
class SolanaChatLineReachTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "feature-stream").isDirectory }
        ?: error("не знайшла кореня сховища")

    private val bridge = File(root, "app/src/main/java/local/phantom/companion/ui/messenger/WebMessengerBridge.kt").readText()

    @Test
    fun `рядок розмови й тіло листа беруть імʼя людини для картки переказу`() {
        assertTrue("рядок розмови", "kinds[WebMessengerKinds.NOTICE] != null -> notice(kinds, solanaWho(p.id, last?.fromId == me))" in bridge)
        assertTrue("тіло листа", ".put(\"body\", bodyOf(kinds, m.body, solanaWho(peerId, m.fromId == me)))" in bridge)
        assertTrue("міст віддає імʼя в chatLine", "SolanaNotice.chatLine(ctx, at(0), who)" in bridge)
    }

    @Test
    fun `хеш без імені людиною не називається`() {
        val who = bridge.substringAfter("private fun solanaWho(").substringBefore("private fun notice(")
        assertTrue(who, "takeIf { it.isNotBlank() }" in who && "PeerRoster.shortId" !in who)
    }
}
