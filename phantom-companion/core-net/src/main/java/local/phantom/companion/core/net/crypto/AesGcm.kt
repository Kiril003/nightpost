package local.phantom.companion.core.net.crypto

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM authenticated encryption.
 *
 * Output layout matches the WebCrypto `AES-GCM` algorithm (and therefore
 * `phantom-companion/src/services/cryptoUtils.ts`'s `aesGcmEncrypt`): the
 * 16-byte tag is appended to the ciphertext, so callers see one
 * `ByteArray` and pass it back unchanged on decrypt.
 *
 * Why GCM over CTR + HMAC: GCM is the WebCrypto canonical AEAD, has a
 * single hardware-accelerated path on AArch64 via ARMv8 Crypto Extensions
 * (which the Radxa Q6A and every shipping Android phone since 2018 expose),
 * and the standard library implementation has FIPS-140-2 review. Hand-
 * rolled CTR+HMAC would buy us nothing.
 *
 * IV discipline: GCM requires a unique 12-byte IV per (key, plaintext)
 * pair. [encrypt] does NOT generate IVs internally — callers supply one
 * from [randomBytes] (12 bytes) so the IV can be persisted alongside the
 * ciphertext. This mirrors the JS reference and avoids the foot-gun of an
 * implicit IV that callers might not realise they need to store.
 */
object AesGcm {

    /** AES-256 key size in bytes. */
    const val KEY_SIZE_BYTES: Int = 32

    /** Recommended GCM nonce length (per NIST SP 800-38D §5.2.1.1). */
    const val IV_SIZE_BYTES: Int = 12

    /** GCM authentication tag size in bits. 128 = full strength. */
    const val TAG_SIZE_BITS: Int = 128

    /**
     * Encrypt [plaintext] with AES-256-GCM under [key], authenticating
     * any [aad] supplied. Output is `ciphertext || tag` (one buffer).
     */
    fun encrypt(
        key: ByteArray,
        plaintext: ByteArray,
        iv: ByteArray,
        aad: ByteArray? = null,
    ): ByteArray {
        require(key.size == KEY_SIZE_BYTES) {
            "AES-GCM key must be $KEY_SIZE_BYTES bytes (got ${key.size})"
        }
        require(iv.size == IV_SIZE_BYTES) {
            "AES-GCM iv must be $IV_SIZE_BYTES bytes (got ${iv.size})"
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").also {
            it.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_SIZE_BITS, iv))
            if (aad != null) it.updateAAD(aad)
        }
        return cipher.doFinal(plaintext)
    }

    /**
     * Decrypt [ciphertext] (which must be the `ciphertext || tag` buffer
     * produced by [encrypt]). Throws [javax.crypto.AEADBadTagException]
     * on tamper / wrong key — callers should treat any exception from
     * this function as authentication failure, not a bug.
     */
    fun decrypt(
        key: ByteArray,
        ciphertext: ByteArray,
        iv: ByteArray,
        aad: ByteArray? = null,
    ): ByteArray {
        require(key.size == KEY_SIZE_BYTES) {
            "AES-GCM key must be $KEY_SIZE_BYTES bytes (got ${key.size})"
        }
        require(iv.size == IV_SIZE_BYTES) {
            "AES-GCM iv must be $IV_SIZE_BYTES bytes (got ${iv.size})"
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").also {
            it.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_SIZE_BITS, iv))
            if (aad != null) it.updateAAD(aad)
        }
        return cipher.doFinal(ciphertext)
    }
}
