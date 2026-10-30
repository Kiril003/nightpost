package local.phantom.companion.core.net.crypto

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom

/**
 * Ed25519 long-term device key.
 *
 * Each paired host (`PairedHost`) gets its own Ed25519 keypair so revoking
 * a host on the desktop only invalidates that one signing identity. The
 * private key is stored as `device_priv_b64` inside the SQLCipher-encrypted
 * `paired_hosts` row — [PairedHost.deviceEd25519PrivBase64].
 *
 * The 32-byte raw seed format (BC's `Ed25519PrivateKeyParameters` constructor)
 * is what the desktop expects on `/api/v1/pair/claim`'s `device_pub` /
 * `challenge_sig` fields, mirroring the WebCrypto raw export the PWA already
 * speaks.
 */
object Ed25519 {

    /** Raw key sizes per RFC 8032. */
    const val PRIVATE_KEY_SIZE: Int = Ed25519PrivateKeyParameters.KEY_SIZE
    const val PUBLIC_KEY_SIZE: Int = Ed25519PublicKeyParameters.KEY_SIZE
    const val SIGNATURE_SIZE: Int = Ed25519PrivateKeyParameters.SIGNATURE_SIZE

    data class Keypair(val privateKey: ByteArray, val publicKey: ByteArray) {
        init {
            require(privateKey.size == PRIVATE_KEY_SIZE) {
                "Ed25519 private key must be $PRIVATE_KEY_SIZE bytes (got ${privateKey.size})"
            }
            require(publicKey.size == PUBLIC_KEY_SIZE) {
                "Ed25519 public key must be $PUBLIC_KEY_SIZE bytes (got ${publicKey.size})"
            }
        }

        // Data-class equality on a ByteArray is identity-based by default;
        // override so the test suite can do straightforward `assertEquals`
        // round-trips after re-importing a serialised keypair.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Keypair) return false
            return privateKey.contentEquals(other.privateKey) &&
                publicKey.contentEquals(other.publicKey)
        }

        override fun hashCode(): Int =
            31 * privateKey.contentHashCode() + publicKey.contentHashCode()
    }

    /** Generate a fresh keypair using the platform CSPRNG. */
    fun generateKeypair(random: SecureRandom = SecureRandom()): Keypair {
        val priv = Ed25519PrivateKeyParameters(random)
        val pub = priv.generatePublicKey()
        return Keypair(privateKey = priv.encoded, publicKey = pub.encoded)
    }

    /** Re-import a keypair from its raw private + public components. */
    fun keypairFromRaw(privateKey: ByteArray, publicKey: ByteArray): Keypair =
        Keypair(privateKey, publicKey)

    /** Derive the public key from a private seed (used after import). */
    fun publicKeyFromPrivate(privateKey: ByteArray): ByteArray {
        require(privateKey.size == PRIVATE_KEY_SIZE) {
            "Ed25519 private key must be $PRIVATE_KEY_SIZE bytes (got ${privateKey.size})"
        }
        return Ed25519PrivateKeyParameters(privateKey, 0).generatePublicKey().encoded
    }

    /**
     * Sign [message] with the raw 32-byte private key. Output is the
     * canonical 64-byte (R || S) Ed25519 signature.
     */
    fun sign(privateKey: ByteArray, message: ByteArray): ByteArray {
        require(privateKey.size == PRIVATE_KEY_SIZE) {
            "Ed25519 private key must be $PRIVATE_KEY_SIZE bytes (got ${privateKey.size})"
        }
        val signer = Ed25519Signer().also {
            it.init(true, Ed25519PrivateKeyParameters(privateKey, 0))
            it.update(message, 0, message.size)
        }
        return signer.generateSignature()
    }

    /**
     * Verify [signature] over [message]. Returns `false` on any structural
     * error (wrong key length, wrong signature length, BC verifier reject)
     * — never throws so callers can use the result in straight `if`
     * branches without try/catch noise.
     */
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != PUBLIC_KEY_SIZE) return false
        if (signature.size != SIGNATURE_SIZE) return false
        val verifier = Ed25519Signer().also {
            it.init(false, Ed25519PublicKeyParameters(publicKey, 0))
            it.update(message, 0, message.size)
        }
        return verifier.verifySignature(signature)
    }
}
