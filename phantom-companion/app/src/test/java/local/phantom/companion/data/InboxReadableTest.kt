package local.phantom.companion.data

import local.phantom.companion.core.net.peer.PeerAnnounce
import local.phantom.companion.core.net.peer.PeerIdentity
import local.phantom.companion.core.net.peer.PeerMessage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxReadableTest {

    private val friend = PeerIdentity(id = "friend", name = "Друг", kind = PeerIdentity.Kind.PERSON, pubKey = "k", addedAtMs = 1L)
    private val line = PeerMessage(id = "m-1", fromId = "friend", toId = "me", body = "привіт", sentAtMs = 5L)
    private fun notice(from: String = "friend", group: String? = null) =
        PeerAnnounce.Notice(key = from, fromId = from, groupId = group, groupName = null, text = "привіт")

    @Test
    fun `a line in the log from a known person is shown`() {
        assertTrue(InboxReadable.shows(listOf(line), listOf(friend), notice(), "m-1"))
    }

    @Test
    fun `a line the log no longer holds rings nothing`() {
        assertFalse(InboxReadable.shows(emptyList(), listOf(friend), notice(), "m-1"))
    }

    @Test
    fun `a sender the bridge cannot list rings nothing`() {
        assertFalse(InboxReadable.shows(listOf(line), emptyList(), notice(), "m-1"))
    }

    @Test
    fun `a group the bridge does not know rings nothing`() {
        assertFalse(InboxReadable.shows(listOf(line), listOf(friend), notice(group = "g-nowhere"), "m-1"))
    }
}
