package local.phantom.companion.core.net.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PeerTypingTest {

    @Test
    fun `кадр читається тим, чим складений`() {
        for (t in listOf(
            PeerTyping.Typing(true),
            PeerTyping.Typing(false),
            PeerTyping.Typing(true, "a1b2c3d4e5f6"),
            PeerTyping.Typing(false, "a1b2c3d4e5f6"),
        )) {
            assertEquals(t, PeerTyping.decode(PeerTyping.encode(t)!!))
        }
        assertNull(PeerTyping.encode(PeerTyping.Typing(true, "a;b")))
        assertNull(PeerTyping.decode("PH-TYP:так"))
        assertNull(PeerTyping.decode("PH-TYP:1;g;x"))
        assertNull(PeerTyping.decode("PH-TYP:1;"))
        assertNull(PeerTyping.decode("друкую"))
    }

    @Test
    fun `кадр друку не лист і набраним не їде`() {
        val body = PeerTyping.encode(PeerTyping.Typing(true))!!
        val frame = PeerMessage("t", "them", "me", body, 1L)
        assertFalse(PeerOutbox.isSpoken(frame))
        assertEquals(0, PeerOutbox.unread(listOf(frame), "them", 0L))
        assertEquals(PeerFrames.Kind.TYPING, PeerFrames.refuseTyped(" $body"))
    }
}
