package local.phantom.companion.core.net.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerProfileSeenTest {

    private val held = PeerIdentity(id = "p1", name = "Ана", kind = PeerIdentity.Kind.PERSON, pubKey = "pk", dhPubKey = "dh")

    @Test
    fun `заборона показувати час у мережі їде кадром і читається назад`() {
        val frame = PeerProfile.encode(PeerProfile.Body(rev = 3, hidesSeen = true))!!
        assertTrue(PeerProfile.decode(frame)!!.hidesSeen)
        assertFalse(PeerProfile.decode(PeerProfile.encode(PeerProfile.Body(rev = 3))!!)!!.hidesSeen)
    }

    @Test
    fun `кадр без заборони — той самий дріт, що й до неї`() {
        val plain = PeerProfile.Body(rev = 7, name = PeerProfile.Field.Set("Ана"))
        assertEquals(PeerProfile.encode(plain), PeerProfile.encode(plain.copy(hidesSeen = false)))
        assertTrue(PeerProfile.encode(plain)!! != PeerProfile.encode(plain.copy(hidesSeen = true)))
    }

    @Test
    fun `реєстр запамʼятовує заборону, а новіший кадр без неї знімає її`() {
        val hidden = PeerRoster.absorbProfile(listOf(held), "p1", PeerProfile.encode(PeerProfile.Body(rev = 1, hidesSeen = true))!!, 10L)
        assertTrue(hidden.single().hidesSeen)
        val open = PeerRoster.absorbProfile(hidden, "p1", PeerProfile.encode(PeerProfile.Body(rev = 2))!!, 11L)
        assertFalse(open.single().hidesSeen)
    }
}
