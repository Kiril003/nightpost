package local.phantom.companion.core.net.crypto

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * NIST FIPS 180-4 Appendix A test vectors — if these break, every other
 * hash-dependent primitive (HKDF, Argon2id encoding, session_key_check
 * round-trip) breaks too, so we sanity-check first.
 */
class HashingTest {

    @Test fun `sha256 of empty input matches NIST vector`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            bytesToHex(sha256(ByteArray(0))),
        )
    }

    @Test fun `sha256 of "abc" matches NIST vector`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            bytesToHex(sha256("abc".toByteArray(Charsets.UTF_8))),
        )
    }

    @Test fun `sha256 of long block matches NIST vector`() {
        val msg = "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            bytesToHex(sha256(msg.toByteArray(Charsets.UTF_8))),
        )
    }
}
