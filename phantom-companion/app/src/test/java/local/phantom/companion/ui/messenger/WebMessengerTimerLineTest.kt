package local.phantom.companion.ui.messenger

import java.io.File
import local.phantom.companion.core.net.peer.PeerDisappear
import local.phantom.companion.core.net.peer.PeerMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Власник 20.10: строк зникнення ставиться, але «нікому не відображає».
 * Кадр `PH-TTL:` лежав у журналі обох, а стрічка будувалась із
 * [local.phantom.companion.core.net.peer.PeerOutbox.conversation], яка пускає
 * лише сказане, тож рядок [WebMessengerKinds.TIMER] не доходив до вебу.
 */
class WebMessengerTimerLineTest {

    private val me = "me_fp"
    private val peer = "peer_fp"
    private val stranger = "other_fp"

    private fun line(id: String, from: String, to: String, body: String, at: Long) =
        PeerMessage(id = id, fromId = from, toId = to, body = body, sentAtMs = at)

    private val log = listOf(
        line("a", peer, me, "привіт", 1_000),
        line("t1", me, peer, PeerDisappear.encode(PeerDisappear.DAY_S, 2_000)!!, 2_000),
        line("b", me, peer, "ок", 3_000),
        line("t2", peer, me, PeerDisappear.encode(0, 4_000)!!, 4_000),
        line("x", stranger, me, PeerDisappear.encode(PeerDisappear.WEEK_S, 2_500)!!, 2_500),
    )

    @Test
    fun `the timer line reaches the feed on the side that set it and on the side that got it`() {
        val feed = WebMessengerKinds.dmFeed(log, peer)
        assertEquals(listOf("a", "t1", "b", "t2"), feed.map { it.id })
        val mine = WebMessengerKinds.fields(feed[1].body)
        assertEquals(WebMessengerKinds.TIMER, mine[WebMessengerKinds.KIND])
        assertEquals(PeerDisappear.DAY_S, mine[WebMessengerKinds.TIMER_S])
        assertEquals(0L, WebMessengerKinds.fields(feed[3].body)[WebMessengerKinds.TIMER_S])
    }

    @Test
    fun `the timer line is neither unread nor the last line of the chat list`() {
        assertEquals(1, WebMessengerKinds.unread(log, peer, 0L))
        assertEquals("b", WebMessengerKinds.lastVisible(log, peer)?.id)
    }

    @Test
    fun `a seq from before the renumber never reads past what the person saw`() {
        val old = listOf(
            line("a", peer, me, "раз", 1_000),
            line("b", me, peer, "два", 3_000),
            line("c", peer, me, "три", 5_000),
        )
        val now = old + line("t", peer, me, PeerDisappear.encode(PeerDisappear.DAY_S, 2_000)!!, 2_000)
        val reads = WebMessengerReads(log = { now }, me = { me }, enabled = { true }, canSend = { true }, send = { _, _ -> null })
        val upto = reads.uptoOf(WebMessengerKinds.dmFeed(now, peer), peer, seq = 2)
        assertTrue("stale seq 2 read up to $upto, past line c", upto < 5_000)
        assertEquals(1, WebMessengerKinds.unread(now, peer, upto))
    }

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("repo root not found") as Nothing
    }

    private val code by lazy {
        File(repoRoot(), "app/src/main/java/local/phantom/companion/ui/messenger/WebMessengerBridge.kt")
            .readText().lineSequence()
            .filterNot { val t = it.trimStart(); t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") }
            .joinToString("\n")
    }

    @Test
    fun `the bridge numbers and serves the 1-to-1 feed through dmFeed only`() {
        assertTrue(
            "messages() does not build the 1-to-1 feed with dmFeed",
            code.contains("WebMessengerKinds.dmFeed(container.mesh.outbox.value, peerId)\n        }\n        val lines = WebMessengerKinds.feed(allMessages)"),
        )
        assertFalse(
            "a seq is still counted from mesh.conversation, so seqs drift once a timer line sits in the feed",
            Regex("""WebMessengerKinds\.feed\((container\.)?mesh\.conversation\(""").containsMatchIn(code),
        )
    }
}
