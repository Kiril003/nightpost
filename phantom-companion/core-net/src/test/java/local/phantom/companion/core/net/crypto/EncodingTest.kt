package local.phantom.companion.core.net.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Round-trip coverage for the hex / base64 / random helpers. These are
 * the boundary primitives every other crypto file leans on — a regression
 * here would silently corrupt every pair-flow payload, so the tests are
 * blunt and exhaustive on the small alphabet.
 */
class EncodingTest {

    @Test fun `hex round-trip preserves every byte value`() {
        val all = ByteArray(256) { it.toByte() }
        val hex = bytesToHex(all)
        assertEquals(512, hex.length)
        val back = hexToBytes(hex)
        assertArrayEquals(all, back)
    }

    @Test fun `hex output is lower-case`() {
        assertEquals("deadbeef", bytesToHex(byteArrayOf(0xde.toByte(), 0xad.toByte(), 0xbe.toByte(), 0xef.toByte())))
    }

    @Test fun `hexToBytes accepts upper-case input (be tolerant)`() {
        assertArrayEquals(
            byteArrayOf(0xde.toByte(), 0xad.toByte()),
            hexToBytes("DEAD"),
        )
    }

    @Test fun `hexToBytes rejects odd length`() {
        try {
            hexToBytes("abc")
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) { /* expected */ }
    }

    @Test fun `hexToBytes rejects non-hex character`() {
        try {
            hexToBytes("zz")
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) { /* expected */ }
    }

    @Test fun `base64 round-trip preserves arbitrary bytes`() {
        val data = ByteArray(73) { (it * 17 - 1).toByte() }
        val s = bytesToB64(data)
        assertArrayEquals(data, b64ToBytes(s))
    }

    @Test fun `base64 emits padded output (matches btoa)`() {
        val s = bytesToB64(byteArrayOf(1, 2, 3, 4, 5))
        // 5 bytes → 8 chars including one '=' pad
        assertEquals(8, s.length)
        assertTrue("expected '=' padding, got: $s", s.endsWith("="))
    }

    @Test fun `random bytes have the requested length`() {
        assertEquals(0, randomBytes(0).size)
        assertEquals(12, randomBytes(12).size)
        assertEquals(32, randomBytes(32).size)
    }

    @Test fun `two consecutive random buffers should differ (sanity check)`() {
        // Statistically certain — collision on 32 bytes from a CSPRNG would
        // be a lottery-winning event. If this ever fails, the platform PRNG
        // is misconfigured and we want to know loudly.
        val a = randomBytes(32)
        val b = randomBytes(32)
        assertNotEquals(bytesToHex(a), bytesToHex(b))
    }

    @Test fun `randomBytes rejects negative length`() {
        try {
            randomBytes(-1)
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) { /* expected */ }
    }
}
