package local.phantom.companion.core.net.peer

import local.phantom.companion.core.net.crypto.Ed25519
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The signature is the only thing standing between a peer message and anybody
 * on the same network. Every way of forging one is a test here.
 */
class PeerSealingTest {

    private val me = Ed25519.generateKeypair()
    private val other = Ed25519.generateKeypair()

    private fun msg(
        id: String = "m1",
        from: String = "me",
        to: String = "marta",
        body: String = "виходь, я біля метро",
        at: Long = 1_000L,
    ) = PeerMessage(id = id, fromId = from, toId = to, body = body, sentAtMs = at)

    @Test
    fun `a sealed message verifies with the sender's key`() {
        val sealed = PeerSealing.seal(msg(), me.privateKey)
        assertTrue(PeerSealing.verify(sealed, me.publicKey))
    }

    @Test
    fun `another key does not open it`() {
        val sealed = PeerSealing.seal(msg(), me.privateKey)
        assertFalse(PeerSealing.verify(sealed, other.publicKey))
    }

    @Test
    fun `changing a single word breaks the seal`() {
        val sealed = PeerSealing.seal(msg(), me.privateKey)
        assertFalse(PeerSealing.verify(sealed.copy(body = "не виходь"), me.publicKey))
    }

    @Test
    fun `a message cannot be re-addressed to someone else`() {
        // Signing only the words would let anyone forward this at a stranger
        // as if it had been written to them.
        val sealed = PeerSealing.seal(msg(), me.privateKey)
        assertFalse(PeerSealing.verify(sealed.copy(toId = "хтось"), me.publicKey))
    }

    @Test
    fun `authorship cannot be re-attributed`() {
        val sealed = PeerSealing.seal(msg(), me.privateKey)
        assertFalse(PeerSealing.verify(sealed.copy(fromId = "marta"), me.publicKey))
    }

    @Test
    fun `the time cannot be moved`() {
        val sealed = PeerSealing.seal(msg(), me.privateKey)
        assertFalse(PeerSealing.verify(sealed.copy(sentAtMs = 9_999L), me.publicKey))
    }

    @Test
    fun `a valid signature from the wrong peer is refused`() {
        // The key proves the words; the roster proves whose key it is. Both.
        val sealed = PeerSealing.seal(msg(from = "marta"), other.privateKey)
        assertTrue(PeerSealing.verify(sealed, other.publicKey, expectedFromId = "marta"))
        assertFalse(PeerSealing.verify(sealed, other.publicKey, expectedFromId = "oleh"))
    }

    @Test
    fun `an unsigned message is never believed`() {
        assertFalse(PeerSealing.verify(msg(), me.publicKey))
    }

    @Test
    fun `garbage in the signature field is refused, not crashed on`() {
        val sealed = PeerSealing.seal(msg(), me.privateKey).copy(signature = "не base64!!")
        assertFalse(PeerSealing.verify(sealed, me.publicKey))
    }

    @Test
    fun `field boundaries cannot be smuggled across`() {
        // «ab» + «c» and «a» + «bc» must not sign the same bytes.
        val a = PeerSealing.canonical(msg(from = "ab", to = "c"))
        val b = PeerSealing.canonical(msg(from = "a", to = "bc"))
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `the id is part of what is signed, so a message cannot be replayed as a new one`() {
        val sealed = PeerSealing.seal(msg(id = "m1"), me.privateKey)
        assertFalse(PeerSealing.verify(sealed.copy(id = "m2"), me.publicKey))
    }

    @Test
    fun `лист без відповіді підписує ті самі байти, що й до появи відповідей`() {
        val plain = msg(id = "m1")
        val expected = listOf(plain.id, plain.fromId, plain.toId, "${plain.sentAtMs}", plain.body)
            .joinToString("")
        assertTrue(PeerSealing.canonical(plain).contentEquals(expected.toByteArray()))
    }

    @Test
    fun `перецілену відповідь не приймають`() {
        val sealed = PeerSealing.seal(msg(id = "m2").copy(replyToId = "m1"), me.privateKey)
        assertTrue(PeerSealing.verify(sealed, me.publicKey))
        assertFalse(PeerSealing.verify(sealed.copy(replyToId = "інший"), me.publicKey))
        assertFalse(PeerSealing.verify(sealed.copy(replyToId = ""), me.publicKey))
    }
}
