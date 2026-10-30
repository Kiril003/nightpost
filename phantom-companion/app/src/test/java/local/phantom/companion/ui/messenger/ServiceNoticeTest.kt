package local.phantom.companion.ui.messenger

import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.net.peer.PeerOutbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NAV_CONTRACT «Службові листи»: the owner's list read «Ви: Цей застосунок ще не вміє
 * показати цей …» on half the chats. A frame nobody wrote is no bubble, no preview, no unread.
 */
class ServiceNoticeTest {

    private fun letter(body: String, at: Long) =
        PeerMessage(id = "m$at", fromId = "them", toId = "me", body = body, sentAtMs = at, state = PeerMessage.State.DELIVERED)

    private val machine = "PH-WALLETSWAP:9f3a"

    @Test
    fun `an unknown machine frame is a notice, not words`() {
        assertTrue(PeerOutbox.isSpoken(letter(machine, 1)))
        assertEquals(WebMessengerCards.SAY_UNKNOWN, WebMessengerKinds.fields(machine)[WebMessengerKinds.NOTICE])
        assertFalse(WebMessengerKinds.isVisible(machine))
    }

    @Test
    fun `the preview stays on the last human line and the badge does not count the frame`() {
        val log = listOf(letter("до зустрічі о сьомій", 1), letter(machine, 2))
        assertEquals("до зустрічі о сьомій", WebMessengerKinds.lastVisible(log, "them")?.body)
        assertEquals(1, WebMessengerKinds.unread(log, "them", 0L))
        assertEquals(1, WebMessengerKinds.feed(log).size)
    }

    @Test
    fun `a chat of only frames has no preview at all`() {
        assertNull(WebMessengerKinds.lastVisible(listOf(letter(machine, 3)), "them"))
    }
}
