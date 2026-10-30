package local.phantom.companion.core.net.peer

import org.junit.Assert.assertEquals
import org.junit.Test

class PeerLaneTest {

    private fun row(id: String, body: String, at: Long) =
        PeerMessage(id = id, fromId = "me", toId = "you", body = body, sentAtMs = at)

    private val chunk = row("c", PeerFile.encodeChunk(PeerFile.newBlobId(), 0, 3, ByteArray(8)), 1L)
    private val read = row("r", PeerRead.encode(PeerRead.Read(5L))!!, 2L)
    private val words = row("w", "де ти?", 3L)

    @Test
    fun `words overtake an older file and older read marks even on a wide link`() {
        assertEquals(listOf("w", "r", "c"), PeerLane.order(listOf(chunk, read, words), narrow = false, nowMs = 10L).map { it.id })
    }

    @Test
    fun `a narrow link sends only the words while any wait, and never a file chunk`() {
        val due = listOf(chunk, read, words)
        assertEquals(listOf("w"), PeerLane.order(due, narrow = true, nowMs = 60_000L).map { it.id })
        assertEquals(listOf("r"), PeerLane.order(listOf(chunk, read), narrow = true, nowMs = 60_000L).map { it.id })
    }

    @Test
    fun `on a narrow link a fresh read mark waits so the next one can replace it`() {
        assertEquals(emptyList<String>(), PeerLane.order(listOf(read), narrow = true, nowMs = 3_000L).map { it.id })
        assertEquals(listOf("r"), PeerLane.order(listOf(read), narrow = true, nowMs = 2L + PeerLane.READ_HOLD_MS).map { it.id })
    }

    @Test
    fun `a peer on the same wifi is not held back by a narrow relay`() {
        assertEquals(
            listOf("w", "c"),
            PeerLane.order(listOf(chunk, words), narrow = true, nowMs = 10L) { it.id == "c" }.map { it.id },
        )
    }
}
