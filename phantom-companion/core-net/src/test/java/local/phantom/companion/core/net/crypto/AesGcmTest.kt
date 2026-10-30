package local.phantom.companion.core.net.crypto

import javax.crypto.AEADBadTagException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.fail
import org.junit.Test

class AesGcmTest {

    @Test fun `encrypt then decrypt round-trips arbitrary plaintext`() {
        val key = randomBytes(AesGcm.KEY_SIZE_BYTES)
        val iv = randomBytes(AesGcm.IV_SIZE_BYTES)
        val plaintext = "vault payload — Кирило, Ukrainian text, emoji included".toByteArray(Charsets.UTF_8)
        val ciphertext = AesGcm.encrypt(key, plaintext, iv)
        // GCM ciphertext = plaintext.size + tag (16 bytes)
        assertEquals(plaintext.size + AesGcm.TAG_SIZE_BITS / 8, ciphertext.size)
        val recovered = AesGcm.decrypt(key, ciphertext, iv)
        assertArrayEquals(plaintext, recovered)
    }

    @Test fun `encrypt with the same key + iv + plaintext is deterministic`() {
        // Documents the foot-gun: never reuse an (iv, key) pair across two
        // distinct plaintexts. The test is here so an accidental refactor that
        // randomises IV inside `encrypt` is caught immediately — the API
        // contract is "caller supplies IV".
        val key = ByteArray(AesGcm.KEY_SIZE_BYTES) { it.toByte() }
        val iv = ByteArray(AesGcm.IV_SIZE_BYTES) { (it + 0x40).toByte() }
        val plaintext = "deterministic".toByteArray(Charsets.UTF_8)
        val a = AesGcm.encrypt(key, plaintext, iv)
        val b = AesGcm.encrypt(key, plaintext, iv)
        assertArrayEquals(a, b)
    }

    @Test fun `aad is honoured (mismatched aad fails authentication)`() {
        val key = randomBytes(AesGcm.KEY_SIZE_BYTES)
        val iv = randomBytes(AesGcm.IV_SIZE_BYTES)
        val plaintext = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val aad = "header-v1".toByteArray(Charsets.UTF_8)
        val ciphertext = AesGcm.encrypt(key, plaintext, iv, aad = aad)

        // Correct AAD → success.
        assertArrayEquals(plaintext, AesGcm.decrypt(key, ciphertext, iv, aad = aad))

        // Different AAD → tag check fails.
        try {
            AesGcm.decrypt(key, ciphertext, iv, aad = "header-v2".toByteArray(Charsets.UTF_8))
            fail("expected AEADBadTagException on AAD mismatch")
        } catch (_: AEADBadTagException) { /* expected */ }
    }

    @Test fun `tampered ciphertext fails authentication`() {
        val key = randomBytes(AesGcm.KEY_SIZE_BYTES)
        val iv = randomBytes(AesGcm.IV_SIZE_BYTES)
        val ciphertext = AesGcm.encrypt(key, "payload".toByteArray(), iv)
        ciphertext[0] = (ciphertext[0].toInt() xor 0x01).toByte() // flip a single bit
        try {
            AesGcm.decrypt(key, ciphertext, iv)
            fail("expected AEADBadTagException on bit-flip tamper")
        } catch (_: AEADBadTagException) { /* expected */ }
    }

    @Test fun `wrong key fails authentication`() {
        val keyA = randomBytes(AesGcm.KEY_SIZE_BYTES)
        val keyB = randomBytes(AesGcm.KEY_SIZE_BYTES)
        assertNotEquals(bytesToHex(keyA), bytesToHex(keyB))
        val iv = randomBytes(AesGcm.IV_SIZE_BYTES)
        val ciphertext = AesGcm.encrypt(keyA, "payload".toByteArray(), iv)
        try {
            AesGcm.decrypt(keyB, ciphertext, iv)
            fail("expected AEADBadTagException on wrong key")
        } catch (_: AEADBadTagException) { /* expected */ }
    }

    @Test fun `encrypt rejects wrong-size key`() {
        try {
            AesGcm.encrypt(ByteArray(16), byteArrayOf(1), randomBytes(AesGcm.IV_SIZE_BYTES))
            fail("expected IllegalArgumentException for AES-128 key in AES-256-only API")
        } catch (_: IllegalArgumentException) { /* expected */ }
    }

    @Test fun `encrypt rejects wrong-size iv`() {
        try {
            AesGcm.encrypt(randomBytes(AesGcm.KEY_SIZE_BYTES), byteArrayOf(1), ByteArray(8))
            fail("expected IllegalArgumentException for short iv")
        } catch (_: IllegalArgumentException) { /* expected */ }
    }
}
