package local.phantom.companion.ui.messenger

import java.util.Base64
import local.phantom.companion.core.net.crypto.Ed25519
import local.phantom.companion.core.net.peer.PeerIdentity
import local.phantom.companion.core.net.peer.PeerIntro
import local.phantom.companion.core.net.peer.PeerInvite
import local.phantom.companion.core.net.peer.PeerRoster
import local.phantom.companion.data.PeerHandleBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WebMessengerTitleTest {

    private fun addressless(): PeerInvite.Invite {
        val keys = Ed25519.generateKeypair()
        val pub = Base64.getEncoder().withoutPadding().encodeToString(keys.publicKey)
        val dh = Base64.getEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })
        val code = PeerInvite.encode(PeerInvite.Invite(PeerIntro.fingerprint(pub), "", pub, dh, "", 0))
        return (PeerInvite.decode(code) as PeerInvite.Decoded.Ph2).invite
    }

    private fun accepted(invite: PeerInvite.Invite) =
        PeerIdentity(id = invite.peerId, name = invite.name, kind = PeerIdentity.Kind.PERSON, pubKey = invite.pubKey)

    @Test
    fun `contact started from a handle is titled by the handle, not the key code`() {
        val invite = addressless()
        val peer = accepted(invite)
        val book = PeerHandleBook({ null }, {})
        book.remember(peer.id, "olena_test1", 1L)

        val title = WebMessengerDirectory.title(peer.name, peer.name, peer.id, book.shown(peer), duplicate = false, untitled = "Без імені")
        assertEquals("@olena_test1", title)

        val twin = WebMessengerDirectory.title(peer.name, peer.name, peer.id, book.shown(peer), duplicate = true, untitled = "Без імені")
        assertEquals("@olena_test1", twin)
        assertFalse(twin.contains(PeerRoster.shortId(peer.id)))
    }

    @Test
    fun `nameless contact without a handle is untitled, never the key code`() {
        val peer = accepted(addressless())
        val title = WebMessengerDirectory.title(peer.name, peer.name, peer.id, null, duplicate = true, untitled = "Без імені")
        assertEquals("Без імені", title)
    }

    @Test
    fun `a real name stays the title, twins are told apart by the handle, never the key code`() {
        val peer = accepted(addressless()).copy(name = "Олена")
        assertEquals("Олена", WebMessengerDirectory.title("Олена", "Олена", peer.id, "olena_test1", duplicate = false, untitled = "Без імені"))
        assertEquals(
            "Олена · @olena_test1",
            WebMessengerDirectory.title("Олена", "Олена", peer.id, "olena_test1", duplicate = true, untitled = "Без імені"),
        )
        val bare = WebMessengerDirectory.title("Олена", "Олена", peer.id, null, duplicate = true, untitled = "Без імені")
        assertEquals("Олена", bare)
        assertFalse(bare.contains(PeerRoster.shortId(peer.id)))
    }
}
