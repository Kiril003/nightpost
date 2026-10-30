package local.phantom.companion.core.net.crypto

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters

/**
 * HKDF-SHA256 key derivation.
 *
 * Pair-flow uses HKDF in extract-and-expand mode to turn the X25519 shared
 * secret + the QR nonce + a constant info string ("phantom-pair") into the
 * 32-byte session key the desktop expects. BouncyCastle's
 * [HKDFBytesGenerator] is the same RFC 5869 implementation Chromium uses
 * under the WebCrypto `HKDF` algorithm — outputs match bit-for-bit, so the
 * existing TS test vectors in `phantom-companion/` translate verbatim.
 */
object Hkdf {

    /** Default output length: 32 bytes — matches AES-256 + the WebCrypto path. */
    const val DEFAULT_LENGTH: Int = 32

    /**
     * Standard HKDF: extract-and-expand in one call.
     *
     * @param ikm    Input keying material (typically the X25519 shared secret).
     * @param salt   Salt for the extract step. May be empty; per RFC 5869 the
     *               extractor still produces a usable PRK by treating `null`
     *               salt as a zero buffer of HashLen bytes.
     * @param info   Application-specific context string. Mirrors the JS call
     *               site's `new TextEncoder().encode("phantom-pair")`.
     * @param length Desired output length in bytes (default 32).
     */
    fun derive(
        ikm: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        length: Int = DEFAULT_LENGTH,
    ): ByteArray {
        require(length in 1..(255 * 32)) {
            "HKDF length must be in [1, 8160] (got $length)"
        }
        val gen = HKDFBytesGenerator(SHA256Digest()).also {
            it.init(HKDFParameters(ikm, salt, info))
        }
        val out = ByteArray(length)
        gen.generateBytes(out, 0, length)
        return out
    }
}
