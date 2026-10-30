package local.phantom.companion.core.net.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

class HkdfTest {

    @Test fun `RFC 5869 test case 1 matches`() {
        // RFC 5869 Appendix A.1 — "Basic test case with SHA-256".
        val ikm = hexToBytes("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
        val salt = hexToBytes("000102030405060708090a0b0c")
        val info = hexToBytes("f0f1f2f3f4f5f6f7f8f9")
        val expected = hexToBytes(
            "3cb25f25faacd57a90434f64d0362f2a" +
                "2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
                "34007208d5b887185865",
        )
        assertArrayEquals(expected, Hkdf.derive(ikm, salt, info, length = 42))
    }

    @Test fun `RFC 5869 test case 2 matches (longer inputs)`() {
        // RFC 5869 Appendix A.2 — "Test with SHA-256 and longer inputs/outputs".
        val ikm = hexToBytes(
            "000102030405060708090a0b0c0d0e0f" +
                "101112131415161718191a1b1c1d1e1f" +
                "202122232425262728292a2b2c2d2e2f" +
                "303132333435363738393a3b3c3d3e3f" +
                "404142434445464748494a4b4c4d4e4f",
        )
        val salt = hexToBytes(
            "606162636465666768696a6b6c6d6e6f" +
                "707172737475767778797a7b7c7d7e7f" +
                "808182838485868788898a8b8c8d8e8f" +
                "909192939495969798999a9b9c9d9e9f" +
                "a0a1a2a3a4a5a6a7a8a9aaabacadaeaf",
        )
        val info = hexToBytes(
            "b0b1b2b3b4b5b6b7b8b9babbbcbdbebf" +
                "c0c1c2c3c4c5c6c7c8c9cacbcccdcecf" +
                "d0d1d2d3d4d5d6d7d8d9dadbdcdddedf" +
                "e0e1e2e3e4e5e6e7e8e9eaebecedeeef" +
                "f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff",
        )
        val expected = hexToBytes(
            "b11e398dc80327a1c8e7f78c596a4934" +
                "4f012eda2d4efad8a050cc4c19afa97c" +
                "59045a99cac7827271cb41c65e590e09" +
                "da3275600c2f09b8367793a9aca3db71" +
                "cc30c58179ec3e87c14c01d5c1f3434f" +
                "1d87",
        )
        assertArrayEquals(expected, Hkdf.derive(ikm, salt, info, length = 82))
    }

    @Test fun `default length is 32 bytes (matches WebCrypto sister code)`() {
        val out = Hkdf.derive(ikm = byteArrayOf(1, 2, 3), salt = byteArrayOf(4, 5, 6), info = byteArrayOf(7, 8))
        assertEquals(32, out.size)
    }

    @Test fun `pair-flow style derivation is deterministic for same inputs`() {
        val shared = ByteArray(32) { it.toByte() }
        val nonce = ByteArray(12) { (it + 0x40).toByte() }
        val info = "phantom-pair".toByteArray(Charsets.UTF_8)
        val a = Hkdf.derive(shared, nonce, info, length = 32)
        val b = Hkdf.derive(shared, nonce, info, length = 32)
        assertArrayEquals(a, b)
    }

    @Test fun `derive rejects out-of-range length`() {
        try {
            Hkdf.derive(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3), length = 0)
            fail("expected IllegalArgumentException for length 0")
        } catch (_: IllegalArgumentException) { /* expected */ }

        try {
            Hkdf.derive(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3), length = 255 * 32 + 1)
            fail("expected IllegalArgumentException for length above max")
        } catch (_: IllegalArgumentException) { /* expected */ }
    }
}
