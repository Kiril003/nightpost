package local.phantom.companion.core.net.crypto

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.SecureRandom

/**
 * X25519 ephemeral keypair + ECDH for the pair-flow.
 *
 * Phase 1 onboarding — the phone generates a fresh ephemeral keypair per
 * pairing attempt, performs ECDH against the server's static X25519 pubkey
 * (carried in the QR's `server_pub` field), and feeds the 32-byte shared
 * secret into [Hkdf.derive] together with the QR nonce to obtain the
 * session key the desktop derives independently. Mirrors the WebCrypto
 * round-trip in `phantom-companion/src/services/cryptoUtils.ts`.
 */
object X25519 {

    /** Raw key sizes per RFC 7748. */
    const val PRIVATE_KEY_SIZE: Int = X25519PrivateKeyParameters.KEY_SIZE
    const val PUBLIC_KEY_SIZE: Int = X25519PublicKeyParameters.KEY_SIZE

    /**
     * X25519 shared-secret size in bytes (32). Not a `const val` because
     * BC's `X25519Agreement.agreementSize` is an instance property — the
     * literal is named here so call sites do not have to bake "32" in.
     */
    val SHARED_SECRET_SIZE: Int = X25519Agreement().agreementSize

    data class Keypair(val privateKey: ByteArray, val publicKey: ByteArray) {
        init {
            require(privateKey.size == PRIVATE_KEY_SIZE) {
                "X25519 private key must be $PRIVATE_KEY_SIZE bytes (got ${privateKey.size})"
            }
            require(publicKey.size == PUBLIC_KEY_SIZE) {
                "X25519 public key must be $PUBLIC_KEY_SIZE bytes (got ${publicKey.size})"
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Keypair) return false
            return privateKey.contentEquals(other.privateKey) &&
                publicKey.contentEquals(other.publicKey)
        }

        override fun hashCode(): Int =
            31 * privateKey.contentHashCode() + publicKey.contentHashCode()
    }

    /** Generate a fresh ephemeral keypair using the platform CSPRNG. */
    fun generateKeypair(random: SecureRandom = SecureRandom()): Keypair {
        val priv = X25519PrivateKeyParameters(random)
        val pub = priv.generatePublicKey()
        return Keypair(privateKey = priv.encoded, publicKey = pub.encoded)
    }

    /** Public key of a stored private key (the identity is kept as the private half only). */
    fun publicKeyFromPrivate(privateKey: ByteArray): ByteArray {
        require(privateKey.size == PRIVATE_KEY_SIZE) {
            "X25519 private key must be $PRIVATE_KEY_SIZE bytes (got ${privateKey.size})"
        }
        return X25519PrivateKeyParameters(privateKey, 0).generatePublicKey().encoded
    }

    /**
     * ECDH agreement.
     *
     * Returns the 32-byte X25519 shared secret. Callers must NOT use this
     * directly as a symmetric key — the WebCrypto sister code feeds it
     * through [Hkdf.derive] together with a salt + info string, which is
     * what binds the key to the pairing context. Returning the raw point
     * here keeps the API faithful to the PWA reference (`ecdhX25519`).
     */
    fun ecdh(privateKey: ByteArray, peerPublicKey: ByteArray): ByteArray {
        require(privateKey.size == PRIVATE_KEY_SIZE) {
            "X25519 private key must be $PRIVATE_KEY_SIZE bytes (got ${privateKey.size})"
        }
        require(peerPublicKey.size == PUBLIC_KEY_SIZE) {
            "X25519 peer public key must be $PUBLIC_KEY_SIZE bytes (got ${peerPublicKey.size})"
        }
        val agreement = X25519Agreement().also {
            it.init(X25519PrivateKeyParameters(privateKey, 0))
        }
        val shared = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(X25519PublicKeyParameters(peerPublicKey, 0), shared, 0)
        return shared
    }
}
