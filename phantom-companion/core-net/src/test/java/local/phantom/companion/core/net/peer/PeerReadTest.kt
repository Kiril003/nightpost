package local.phantom.companion.core.net.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PeerReadTest {

    private val t0 = 1_788_517_702_898L

    private fun read(id: String, from: String, to: String, upto: Long, at: Long, group: String = "") =
        PeerMessage(id = id, fromId = from, toId = to, body = PeerRead.encode(PeerRead.Read(upto, group))!!, sentAtMs = at)

    @Test
    fun `кадр читається тим, чим складений`() {
        assertEquals(PeerRead.Read(t0), PeerRead.decode(PeerRead.encode(PeerRead.Read(t0))!!))
        assertEquals(PeerRead.Read(t0, "a1b2c3d4e5f6"), PeerRead.decode(PeerRead.encode(PeerRead.Read(t0, "a1b2c3d4e5f6"))!!))
        assertNull(PeerRead.encode(PeerRead.Read(0L)))
        assertNull(PeerRead.encode(PeerRead.Read(t0, "a;b")))
        assertNull(PeerRead.decode("PH-RDU:абв"))
        assertNull(PeerRead.decode("PH-RDU:1;g;x"))
    }

    @Test
    fun `точку рухає лише співрозмовник, і лише вперед`() {
        val log = listOf(
            read("r2", "them", "me", t0 + 2_000, t0 + 10),
            read("r1", "them", "me", t0 + 1_000, t0 + 20),
            read("x", "stranger", "me", t0 + 9_000, t0 + 30),
        )
        assertEquals(PeerRead.Point(t0 + 2_000, t0 + 10), PeerRead.point(log, "them", "me"))
        assertNull(PeerRead.point(log, "me", "them"))
    }

    @Test
    fun `груповий кадр не змішується з розмовою вдвох`() {
        val log = listOf(read("g", "them", "me", t0 + 5_000, t0, "a1b2c3d4e5f6"))
        assertNull(PeerRead.point(log, "them", "me"))
        assertEquals(t0 + 5_000, PeerRead.point(log, "them", "me", "a1b2c3d4e5f6")?.uptoMs)
    }

    @Test
    fun `журнал тримає по одному кадру на читача`() {
        val word = PeerMessage("w", "me", "them", "привіт", t0)
        val log = listOf(
            word,
            read("r1", "them", "me", t0 + 1_000, t0 + 10),
            read("r2", "them", "me", t0 + 2_000, t0 + 20),
            read("g1", "them", "me", t0 + 1_000, t0 + 30, "a1b2c3d4e5f6"),
            read("m1", "me", "them", t0 + 1_000, t0 + 40),
        )
        assertEquals(listOf("w", "r2", "g1", "m1"), PeerRead.compact(log).map { it.id })
    }

    @Test
    fun `кадр прочитання не рахується листом`() {
        val frame = read("r", "them", "me", t0, t0)
        assertEquals(0, PeerOutbox.unread(listOf(frame), "them", 0L))
        assertEquals(0, PeerOutbox.waiting(listOf(frame.copy(fromId = "me", state = PeerMessage.State.QUEUED))))
    }
}
