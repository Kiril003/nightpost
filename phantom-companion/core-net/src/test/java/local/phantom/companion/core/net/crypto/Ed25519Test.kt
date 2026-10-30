package local.phantom.companion.core.net.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class Ed25519Test {

    @Test fun `generated keypair has the correct sizes`() {
        val kp = Ed25519.generateKeypair()
        assertEquals(Ed25519.PRIVATE_KEY_SIZE, kp.privateKey.size)
        assertEquals(Ed25519.PUBLIC_KEY_SIZE, kp.publicKey.size)
    }

    @Test fun `sign then verify with matching keypair returns true`() {
        val kp = Ed25519.generateKeypair()
        val msg = "phantom-pair-challenge".toByteArray(Charsets.UTF_8)
        val sig = Ed25519.sign(kp.privateKey, msg)
        assertEquals(Ed25519.SIGNATURE_SIZE, sig.size)
        assertTrue(Ed25519.verify(kp.publicKey, msg, sig))
    }

    @Test fun `verify rejects a tampered message`() {
        val kp = Ed25519.generateKeypair()
        val msg = "approve transfer 100".toByteArray(Charsets.UTF_8)
        val sig = Ed25519.sign(kp.privateKey, msg)
        val tampered = "approve transfer 999".toByteArray(Charsets.UTF_8)
        assertFalse(Ed25519.verify(kp.publicKey, tampered, sig))
    }

    @Test fun `verify rejects a signature from a different key`() {
        val a = Ed25519.generateKeypair()
        val b = Ed25519.generateKeypair()
        assertFalse("freshly-generated keys must not collide", a.publicKey.contentEquals(b.publicKey))
        val msg = byteArrayOf(1, 2, 3, 4, 5)
        val sig = Ed25519.sign(a.privateKey, msg)
        assertFalse(Ed25519.verify(b.publicKey, msg, sig))
    }

    @Test fun `verify returns false for wrong-length keys and signatures`() {
        val kp = Ed25519.generateKeypair()
        val msg = byteArrayOf(7, 7, 7)
        val sig = Ed25519.sign(kp.privateKey, msg)
        assertFalse(Ed25519.verify(ByteArray(0), msg, sig))
        assertFalse(Ed25519.verify(kp.publicKey, msg, ByteArray(0)))
        assertFalse(Ed25519.verify(kp.publicKey, msg, ByteArray(Ed25519.SIGNATURE_SIZE - 1)))
    }

    @Test fun `publicKeyFromPrivate matches generated public`() {
        val kp = Ed25519.generateKeypair()
        val derived = Ed25519.publicKeyFromPrivate(kp.privateKey)
        assertArrayEquals(kp.publicKey, derived)
    }

    @Test fun `signature is deterministic for the same key and message`() {
        // Ed25519 is RFC 8032 deterministic — same key + same message = same sig.
        val kp = Ed25519.generateKeypair()
        val msg = "deterministic".toByteArray(Charsets.UTF_8)
        val a = Ed25519.sign(kp.privateKey, msg)
        val b = Ed25519.sign(kp.privateKey, msg)
        assertArrayEquals(a, b)
    }

    @Test fun `keypairFromRaw enforces sizes`() {
        try {
            Ed25519.keypairFromRaw(ByteArray(31), ByteArray(Ed25519.PUBLIC_KEY_SIZE))
            fail("expected IllegalArgumentException for short private key")
        } catch (_: IllegalArgumentException) { /* expected */ }

        try {
            Ed25519.keypairFromRaw(ByteArray(Ed25519.PRIVATE_KEY_SIZE), ByteArray(33))
            fail("expected IllegalArgumentException for long public key")
        } catch (_: IllegalArgumentException) { /* expected */ }
    }

    @Test fun `Keypair equality is value-based for ByteArray fields`() {
        val priv = ByteArray(Ed25519.PRIVATE_KEY_SIZE) { (it * 3).toByte() }
        val pub = Ed25519.publicKeyFromPrivate(priv)
        val a = Ed25519.keypairFromRaw(priv, pub)
        val b = Ed25519.keypairFromRaw(priv.copyOf(), pub.copyOf())
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())

        val other = Ed25519.generateKeypair()
        assertNotEquals(a, other)
    }
}
