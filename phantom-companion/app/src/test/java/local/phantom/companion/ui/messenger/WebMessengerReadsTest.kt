package local.phantom.companion.ui.messenger

import local.phantom.companion.core.net.peer.PeerGroup
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.net.peer.PeerRead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebMessengerReadsTest {

    private val t0 = 1_788_517_702_898L

    private var log = mutableListOf<PeerMessage>()
    private var on = true
    private val blocked = mutableSetOf<String>()
    private val sent = mutableListOf<PeerMessage>()

    private val reads = WebMessengerReads(
        log = { log },
        me = { "me" },
        enabled = { on },
        canSend = { it !in blocked },
        send = { to, body ->
            PeerMessage("r${sent.size}", "me", to, body, t0 + 100 + sent.size).also { sent += it; log += it }
        },
    )

    private fun word(id: String, from: String, at: Long, to: String = if (from == "me") "them" else "me") =
        PeerMessage(id, from, to, "слово", at)

    private fun readFrom(reader: String, upto: Long, at: Long, group: String = "") =
        PeerMessage("x$upto", reader, "me", PeerRead.encode(PeerRead.Read(upto, group))!!, at)

    @Test
    fun `прочитання їде один раз на зсув і лише вперед`() {
        val feed = listOf(word("a", "them", t0), word("b", "them", t0 + 10), word("c", "me", t0 + 20))
        assertEquals(t0, reads.uptoOf(feed, "them", 1))
        assertEquals(t0 + 10, reads.uptoOf(feed, "them", 0))
        assertTrue(reads.readConversation("them", t0 + 10))
        assertFalse(reads.readConversation("them", t0 + 10))
        assertFalse(reads.readConversation("them", t0))
        assertEquals(PeerRead.Read(t0 + 10), PeerRead.decode(sent.single().body))
    }

    @Test
    fun `вимкнений перемикач не шле й ховає чужі прочитання`() {
        log += readFrom("them", t0 + 10, t0 + 50)
        on = false
        assertFalse(reads.readConversation("them", t0 + 10))
        assertTrue(sent.isEmpty())
        assertFalse(reads.visible())
        assertNull(reads.pointOf("them"))
    }

    @Test
    fun `заблокованому прочитання не шлеться`() {
        blocked += "them"
        assertFalse(reads.readConversation("them", t0 + 10))
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `read_at стоїть на власних рядках до точки співрозмовника`() {
        log += readFrom("them", t0 + 10, t0 + 50)
        log += readFrom("stranger", t0 + 99, t0 + 60)
        val point = reads.pointOf("them")
        assertEquals(t0 + 50, reads.readAtMs(word("a", "me", t0 + 10), point))
        assertEquals(0L, reads.readAtMs(word("b", "me", t0 + 11), point))
        assertEquals(0L, reads.readAtMs(word("c", "them", t0), point))
    }

    @Test
    fun `у групі прочитання рахується по учасниках`() {
        val group = PeerGroup.Group("a1b2c3d4e5f6", "ланка", listOf("me", "a", "b"))
        val line = word("g", "me", t0 + 10, to = "a")
        log += readFrom("a", t0 + 10, t0 + 50, group.id)
        log += readFrom("b", t0 + 10, t0 + 50)
        assertEquals(1, reads.fanRead(group, line))
        val feed = listOf(word("fa", "a", t0 + 1), word("fb", "b", t0 + 2))
        assertEquals(2, reads.readGroup(group, feed, 0))
        assertEquals(setOf("a", "b"), sent.map { it.toId }.toSet())
        assertTrue(sent.all { PeerRead.decode(it.body)?.groupId == group.id })
        on = false
        assertNull(reads.fanRead(group, line))
    }

    @Test
    fun `міст шле прочитання з обох ручок read і віддає read_at та fan_read`() {
        var dir: java.io.File? = java.io.File("").absoluteFile
        while (dir != null && !java.io.File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        val bridge = java.io.File(dir, "app/src/main/java/local/phantom/companion/ui/messenger/WebMessengerBridge.kt").readText()
        assertTrue(bridge.contains("reads.readConversation("))
        assertTrue(bridge.contains("reads.readGroup("))
        assertTrue(bridge.contains("\"read_at\""))
        assertTrue(bridge.contains("\"fan_read\""))
        // Умови й сама відправка — у WebMessengerReads.of: ту саму збірку бере й шторка.
        assertTrue(bridge.contains("WebMessengerReads.of(container)"))
        val reads = java.io.File(dir, "app/src/main/java/local/phantom/companion/ui/messenger/WebMessengerReads.kt").readText()
        assertTrue(reads.contains("container.mesh.sendRead("))
    }
}
