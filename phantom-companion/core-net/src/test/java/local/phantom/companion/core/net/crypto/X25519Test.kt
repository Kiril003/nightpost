package local.phantom.companion.core.net.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.fail
import org.junit.Test

class X25519Test {

    @Test fun `generated keypair has the correct sizes`() {
        val kp = X25519.generateKeypair()
        assertEquals(X25519.PRIVATE_KEY_SIZE, kp.privateKey.size)
        assertEquals(X25519.PUBLIC_KEY_SIZE, kp.publicKey.size)
    }

    @Test fun `ecdh derives the same shared secret on both sides`() {
        val phone = X25519.generateKeypair()
        val server = X25519.generateKeypair()
        val phoneSide = X25519.ecdh(phone.privateKey, server.publicKey)
        val serverSide = X25519.ecdh(server.privateKey, phone.publicKey)
        assertArrayEquals(phoneSide, serverSide)
        assertEquals(X25519.SHARED_SECRET_SIZE, phoneSide.size)
    }

    @Test fun `different keypairs produce different shared secrets`() {
        val phone = X25519.generateKeypair()
        val serverA = X25519.generateKeypair()
        val serverB = X25519.generateKeypair()
        val secretA = X25519.ecdh(phone.privateKey, serverA.publicKey)
        val secretB = X25519.ecdh(phone.privateKey, serverB.publicKey)
        assertNotEquals(bytesToHex(secretA), bytesToHex(secretB))
    }

    @Test fun `RFC 7748 §6_1 test vector matches`() {
        // Alice's private + Bob's public from the canonical X25519 vector.
        val alicePriv = hexToBytes(
            "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a",
        )
        val bobPub = hexToBytes(
            "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f",
        )
        val expected = hexToBytes(
            "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742",
        )
        assertArrayEquals(expected, X25519.ecdh(alicePriv, bobPub))
    }

    @Test fun `ecdh rejects wrong-length keys`() {
        val kp = X25519.generateKeypair()
        try {
            X25519.ecdh(ByteArray(31), kp.publicKey)
            fail("expected IllegalArgumentException for short private key")
        } catch (_: IllegalArgumentException) { /* expected */ }

        try {
            X25519.ecdh(kp.privateKey, ByteArray(31))
            fail("expected IllegalArgumentException for short peer public key")
        } catch (_: IllegalArgumentException) { /* expected */ }
    }
}
