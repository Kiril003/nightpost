package local.phantom.companion.core.net.peer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Межа прочитаного.
 *
 * Вона рухається лише вперед. Інакше відкрити стару розмову означало б
 * «розпрочитати» свіжішу — лічильник, який оживає сам собою, оператор
 * перестає читати вже на другий раз.
 */
class PeerReadStateTest {

    private fun peer(id: String, readUpTo: Long = 0L) = PeerIdentity(
        id = id,
        name = "Хтось",
        kind = PeerIdentity.Kind.PERSON,
        pubKey = "k",
        readUpToMs = readUpTo,
    )

    @Test
    fun `marking read moves the boundary`() {
        val out = PeerRoster.markRead(listOf(peer("a")), "a", 500L)
        assertEquals(500L, out.single().readUpToMs)
    }

    @Test
    fun `an older mark never un-reads a newer one`() {
        val out = PeerRoster.markRead(listOf(peer("a", readUpTo = 900L)), "a", 100L)
        assertEquals(900L, out.single().readUpToMs)
    }

    @Test
    fun `marking one conversation leaves the others alone`() {
        val out = PeerRoster.markRead(listOf(peer("a"), peer("b", 700L)), "a", 500L)
        assertEquals(500L, out.first { it.id == "a" }.readUpToMs)
        assertEquals(700L, out.first { it.id == "b" }.readUpToMs)
    }

    @Test
    fun `re-adding somebody does not swallow their unread`() {
        val existing = listOf(peer("a", readUpTo = 400L))
        val out = PeerRoster.upsert(existing, peer("a", readUpTo = 0L))
        assertEquals(400L, out.single().readUpToMs)
    }

    @Test
    fun `unread counts only what they said, and only past the boundary`() {
        fun m(from: String, at: Long) = PeerMessage(
            id = "$from$at",
            fromId = from,
            toId = if (from == "a") "me" else "a",
            body = "x",
            sentAtMs = at,
        )
        val log = listOf(m("a", 10), m("me", 20), m("a", 30))
        assertEquals(2, PeerOutbox.unread(log, "a", readUpToMs = 0L))
        assertEquals(1, PeerOutbox.unread(log, "a", readUpToMs = 10L))
        assertEquals(0, PeerOutbox.unread(log, "a", readUpToMs = 30L))
    }

    @Test
    fun `lastLine is the newest of either direction`() {
        fun m(from: String, to: String, at: Long, body: String) =
            PeerMessage(id = "$from$at", fromId = from, toId = to, body = body, sentAtMs = at)
        val log = listOf(m("a", "me", 10, "їхнє"), m("me", "a", 40, "моє"), m("b", "me", 99, "чуже"))
        assertEquals("моє", PeerOutbox.lastLine(log, "a")?.body)
    }
}
