package local.phantom.companion.core.net.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PeerHandleClaimTest {

    private fun peer(id: String, kind: PeerIdentity.Kind = PeerIdentity.Kind.PERSON) = PeerIdentity(
        id = id, name = "Кирило", kind = kind, pubKey = "pk-$id",
    )

    @Test
    fun `handle rides the profile frame in canonical form`() {
        val frame = PeerProfile.encode(PeerProfile.Body(rev = 3, handle = PeerProfile.Field.Set("@Kyrylo_M1")))!!
        assertEquals(PeerProfile.Field.Set("kyrylo_m1"), PeerProfile.decode(frame)!!.handle)
    }

    @Test
    fun `a malformed handle never reaches the roster`() {
        val frame = PeerProfile.encode(PeerProfile.Body(rev = 3, handle = PeerProfile.Field.Set("кирило")))!!
        assertEquals(PeerProfile.Field.Absent, PeerProfile.decode(frame)!!.handle)
    }

    @Test
    fun `claim is stored, kept by frames without it, and cleared on request`() {
        val roster = listOf(peer("a"))
        val claimed = PeerRoster.absorbProfile(
            roster, "a",
            PeerProfile.encode(PeerProfile.Body(rev = 1, handle = PeerProfile.Field.Set("kyrylo_m1")))!!, 10L,
        )
        assertEquals("kyrylo_m1", claimed[0].claimedHandle)
        val renamed = PeerRoster.absorbProfile(
            claimed, "a",
            PeerProfile.encode(PeerProfile.Body(rev = 2, name = PeerProfile.Field.Set("Кіра")))!!, 20L,
        )
        assertEquals("kyrylo_m1", renamed[0].claimedHandle)
        val cleared = PeerRoster.absorbProfile(
            renamed, "a",
            PeerProfile.encode(PeerProfile.Body(rev = 3, handle = PeerProfile.Field.Cleared))!!, 30L,
        )
        assertEquals("", cleared[0].claimedHandle)
    }

    @Test
    fun `an old frame without the tag claims nothing`() {
        val frame = PeerProfile.encode(PeerProfile.Body(rev = 1, bio = PeerProfile.Field.Set("x")))!!
        assertNull(PeerRoster.absorbProfile(listOf(peer("a")), "a", frame, 1L)[0].claimedHandle)
    }

    @Test
    fun `a paired device stays a device when the same key sends an invite`() {
        val paired = PeerRoster.upsert(emptyList(), peer("pc", PeerIdentity.Kind.DEVICE))
        val again = PeerRoster.upsert(paired, peer("pc", PeerIdentity.Kind.PERSON))
        assertEquals(PeerIdentity.Kind.DEVICE, again.single().kind)
    }

    @Test
    fun `only the owner moves a row between people and devices`() {
        val roster = listOf(peer("laptop"))
        val mine = PeerRoster.setKind(roster, "laptop", PeerIdentity.Kind.DEVICE)
        assertEquals(PeerIdentity.Kind.DEVICE, mine.single().kind)
        assertEquals(PeerIdentity.Kind.PERSON, PeerRoster.setKind(mine, "laptop", PeerIdentity.Kind.PERSON).single().kind)
    }
}
