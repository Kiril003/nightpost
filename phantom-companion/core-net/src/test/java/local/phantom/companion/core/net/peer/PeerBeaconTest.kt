package local.phantom.companion.core.net.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Discovery finds addresses; the roster decides who is anybody. Conflating
 * the two is how a mesh hands the operator whoever happens to be on the Wi-Fi.
 */
class PeerBeaconTest {

    private fun found(id: String, host: String = "192.168.0.7") =
        PeerBeacon.Found(peerId = id, host = host, port = PeerWire.PORT)

    private fun peer(id: String) = PeerIdentity(
        id = id, name = "Марта", kind = PeerIdentity.Kind.PERSON, pubKey = "pk",
    )

    @Test
    fun `a stranger on the same network is not a contact`() {
        val out = PeerBeacon.known(
            listOf(found("known"), found("stranger")),
            listOf(peer("known")),
        )
        assertEquals(listOf("known"), out.map { it.peerId })
    }

    @Test
    fun `an empty roster trusts nobody, however many are broadcasting`() {
        assertTrue(PeerBeacon.known(listOf(found("a"), found("b")), emptyList()).isEmpty())
    }

    @Test
    fun `a known peer that moved address is still known`() {
        val out = PeerBeacon.known(listOf(found("a", host = "10.0.0.3")), listOf(peer("a")))
        assertEquals("10.0.0.3", out.single().host)
    }

    @Test
    fun `nothing on the network is not an error`() {
        assertTrue(PeerBeacon.known(emptyList(), listOf(peer("a"))).isEmpty())
    }

    @Test
    fun `the advertised type is the peer link, not the pairing service`() {
        // Sharing a type with the PC pairing scan would make every phone look
        // like an operator node to the pairing flow.
        assertTrue(PeerBeacon.SERVICE_TYPE != "_phantom._tcp.")
        assertTrue(PeerBeacon.SERVICE_TYPE.endsWith("._tcp."))
    }

    @Test
    fun `a service that left names the peer by the prefix it advertised`() {
        val id = "0123456789abcdef0123456789abcdef"
        val lost = PeerBeacon.lostOf("${PeerBeacon.NAME_PREFIX}${id.take(16)}")!!
        assertTrue(lost.lost)
        assertTrue(lost.names(id))
        assertFalse(lost.names("ffff" + id))
        assertEquals(null, PeerBeacon.lostOf("printer-1"))
    }
}
