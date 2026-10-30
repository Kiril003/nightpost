package local.phantom.companion.data

import local.phantom.companion.core.net.peer.PeerIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerHandleBookTest {

    private var disk: String? = null
    private fun book() = PeerHandleBook({ disk }, { disk = it })

    private fun peer(id: String, claim: String?) = PeerIdentity(
        id = id, name = "Кирило", kind = PeerIdentity.Kind.PERSON, pubKey = "pk-$id", claimedHandle = claim,
    )

    @Test
    fun `a claim is not shown until the directory says the same key owns it`() {
        val b = book()
        val liar = peer("mallory", "kyrylo_m1")
        assertEquals("kyrylo_m1", b.due(liar, 0L))
        assertNull(b.shown(liar))
        b.settle(liar, "kyrylo_m1", PeerHandleBook.Owner.Key("kyrylo-real"), 1L)
        assertNull(b.shown(liar))

        val real = peer("kyrylo-real", "kyrylo_m1")
        b.settle(real, "kyrylo_m1", PeerHandleBook.Owner.Key("kyrylo-real"), 1L)
        assertEquals("kyrylo_m1", b.shown(real))
        assertNull(b.due(real, 2L))
    }

    @Test
    fun `a handle proven for the old key is not shown after the key changed`() {
        val b = book()
        val p = peer("olena-fixture", "olena_test1")
        b.settle(p, "olena_test1", PeerHandleBook.Owner.Key("olena-fixture"), 1L)
        assertEquals("olena_test1", b.shown(p))
        assertNull(b.shown(p.copy(verifiedAtMs = 5L, keyChangedAtMs = 10L)))
    }

    @Test
    fun `a handle belongs to one key only and survives a restart`() {
        val b = book()
        b.remember("old-laptop", "kyrylo_m1", 1L)
        b.remember("phone", "kyrylo_m1", 2L)
        val again = book()
        assertNull(again.shown(peer("old-laptop", null)))
        assertEquals("kyrylo_m1", again.shown(peer("phone", null)))
    }

    @Test
    fun `a changed or withdrawn claim hides the old handle`() {
        val b = book()
        b.remember("a", "kyrylo_m1", 1L)
        assertNull(b.shown(peer("a", "other_handle")))
        assertNull(b.due(peer("a", ""), 0L))
        assertNull(b.shown(peer("a", null)))
    }

    @Test
    fun `no network keeps what was known and a stale proof is rechecked`() {
        val b = book()
        val p = peer("a", "kyrylo_m1")
        b.settle(p, "kyrylo_m1", PeerHandleBook.Owner.Key("a"), 0L)
        b.settle(p, "kyrylo_m1", PeerHandleBook.Owner.Unknown, 5L)
        assertEquals("kyrylo_m1", b.shown(p))
        assertEquals("kyrylo_m1", b.due(p, PeerHandleBook.RECHECK_MS + 1))
        b.settle(p, "kyrylo_m1", PeerHandleBook.Owner.Nobody, PeerHandleBook.RECHECK_MS + 2)
        assertNull(b.shown(p))
    }

    @Test
    fun `my handle is renewed a week before the server lets it go`() {
        val now = 1_000_000_000_000L
        assertFalse(PeerDirectoryStore.dueForRenewal(0L, now))
        assertFalse(PeerDirectoryStore.dueForRenewal(now / 1000 + 20 * 86_400, now))
        assertTrue(PeerDirectoryStore.dueForRenewal(now / 1000 + 3 * 86_400, now))
        assertTrue(PeerDirectoryStore.dueForRenewal(now / 1000 - 1, now))
    }
}
