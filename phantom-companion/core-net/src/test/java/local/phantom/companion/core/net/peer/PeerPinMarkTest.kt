package local.phantom.companion.core.net.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P05: закріплення в розмові удвох — один кадр `PH-PIN:`, останнє слово будь-кого з двох. */
class PeerPinMarkTest {

    private fun pin(from: String, to: String, at: Long, target: String, on: Boolean) = PeerMessage(
        id = "$from-$at",
        fromId = from,
        toId = to,
        body = PeerMark.encode(PeerMark.Mark(PeerMark.What.PIN, target, on, PeerMark.PIN_VALUE))!!,
        sentAtMs = at,
    )

    @Test
    fun `кадр читається назад, ховається зі стрічки і не стає листом`() {
        val wire = pin("me", "oleh", 1, "line-1", true).body
        assertTrue(wire.startsWith("PH-PIN:"))
        assertEquals(PeerMark.Mark(PeerMark.What.PIN, "line-1", true, PeerMark.PIN_VALUE), PeerMark.decode(wire))
        assertTrue(PeerMark.isMark(wire))
        assertEquals(PeerFrames.Kind.PIN, PeerFrames.kindOf(wire))
        assertFalse(PeerFrames.Kind.PIN.reachesConversation)
    }

    @Test
    fun `я закріпив, співрозмовник зняв — знято в обох, бо згортка та сама`() {
        val log = listOf(
            pin("me", "oleh", 10, "a", true),
            pin("oleh", "me", 20, "b", true),
            pin("oleh", "me", 30, "a", false),
        )
        assertEquals(setOf("b"), PeerMark.pinned(log, "me", "oleh"))
        assertEquals(PeerMark.pinned(log, "me", "oleh"), PeerMark.pinned(log.reversed(), "oleh", "me"))
    }

    @Test
    fun `кадри приїхали навпаки — вирішує годинник автора, не черга`() {
        val log = listOf(pin("me", "oleh", 50, "a", true), pin("oleh", "me", 40, "a", false))
        assertEquals(setOf("a"), PeerMark.pinned(log, "me", "oleh"))
    }

    @Test
    fun `чужа розмова не закріплює нічого в нашій`() {
        val log = listOf(pin("anna", "me", 10, "a", true), pin("me", "anna", 11, "b", true))
        assertTrue(PeerMark.pinned(log, "me", "oleh").isEmpty())
        assertEquals(setOf("b", "a"), PeerMark.pinned(log, "me", "anna"))
    }
}
