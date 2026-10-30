package local.phantom.companion.ui.messenger

import local.phantom.companion.core.net.peer.PeerFile
import local.phantom.companion.core.net.peer.PeerMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * DL-4 (ніч 09.10, A52 кадр a52-q): «5 повідомлень чекають звʼязку» після
 * одного набраного листа; A56 — «2», нічого не набравши. Рахувався кожен
 * рядок черги, разом зі службовими кадрами.
 */
class WebMessengerQueueCountTest {

    private var n = 0
    private fun row(body: String, state: PeerMessage.State = PeerMessage.State.QUEUED) =
        PeerMessage(id = "m${++n}", fromId = "me", toId = "nina", body = body, sentAtMs = 1L, state = state)

    @Test
    fun `one typed letter among profile, prekeys and read frames is one waiting letter`() {
        val outbox = listOf(
            row("привіт"),
            row("PH-PRF:{\"name\":\"oleh\"}"),
            row("PH-PKB:bundle"),
            row("PH-RDU:m0"),
            row("PH-TYP:1"),
        )
        assertEquals(1, WebMessengerKinds.queuedLetters(outbox))
    }

    @Test
    fun `adding a person queues frames only - nothing waits`() {
        assertEquals(0, WebMessengerKinds.queuedLetters(listOf(row("PH-PRF:{}"), row("PH-PKB:x"))))
    }

    @Test
    fun `a file in many chunks is one letter, and sent letters are not waiting`() {
        val blob = PeerFile.sha256Hex(byteArrayOf(9)).take(PeerFile.BLOB_ID_CHARS)
        val chunks = (0 until 7).map { row(PeerFile.encodeChunk(blob, it, 7, byteArrayOf(1, 2))) }
        val sent = row("вже пішло", PeerMessage.State.SENT)
        assertEquals(1, WebMessengerKinds.queuedLetters(chunks + sent))
    }

    @Test
    fun `the queue status endpoint uses the letter count`() {
        val src = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "app/src/main/java/local/phantom/companion/ui/messenger/WebMessengerBridge.kt") }
            .first { it.isFile }.readText()
        assertTrue(src.contains("\"queued\", WebMessengerKinds.queuedLetters(mesh.outbox.value)"))
    }
}
