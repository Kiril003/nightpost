package local.phantom.companion.data

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import local.phantom.companion.core.net.crypto.Ed25519
import local.phantom.companion.core.net.crypto.X25519
import java.security.MessageDigest

/**
 * Who this phone is, owing nothing to pairing — otherwise the whole social
 * layer is gated behind owning a server. Pairing keeps its own separate key.
 *
 * Two keypairs, deliberately not one. Ed25519 says who wrote a thing; X25519
 * is what makes it unreadable to the rest of the Wi-Fi. Keeping them apart
 * means a future ratchet can rotate the encryption key without changing this
 * device's identity, its fingerprint or a single roster row.
 */
class DeviceIdentityStore(context: Context) {

    private val prefs = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    data class Identity(
        /** Fingerprint of the signing public key — what the roster and beacon use. */
        val id: String,
        val publicKeyB64: String,
        val privateKey: ByteArray,
        val dhPublicKeyB64: String,
        val dhPrivateKey: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean = other is Identity && other.id == id
        override fun hashCode(): Int = id.hashCode()
    }

    /** Mints on first call. Blocking (Keystore) — never from composition. */
    @Synchronized
    fun get(): Identity {
        val dh = dhKeys()
        val storedPriv = prefs.getString(KEY_PRIV, null)
        val storedPub = prefs.getString(KEY_PUB, null)
        if (storedPriv != null && storedPub != null) {
            val priv = decode(storedPriv)
            if (priv != null) {
                return Identity(fingerprint(storedPub), storedPub, priv, dh.first, dh.second)
            }
        }
        val fresh = Ed25519.generateKeypair()
        val pub = encode(fresh.publicKey)
        prefs.edit()
            .putString(KEY_PRIV, encode(fresh.privateKey))
            .putString(KEY_PUB, pub)
            .apply()
        return Identity(fingerprint(pub), pub, fresh.privateKey, dh.first, dh.second)
    }

    /** Встановлює ключ особи з відновленої резервної копії. */
    @Synchronized
    fun adopt(identity: Identity) {
        prefs.edit()
            .putString(KEY_PRIV, encode(identity.privateKey))
            .putString(KEY_PUB, identity.publicKeyB64)
            .putString(KEY_DH_PRIV, encode(identity.dhPrivateKey))
            .putString(KEY_DH_PUB, identity.dhPublicKeyB64)
            .apply()
    }

    /**
     * Minted beside the signing keys on the first run of this build, and never
     * again. Deliberately does NOT touch the Ed25519 pair: rotating that would
     * change the fingerprint, which is this device's id in every roster that
     * already holds it — every existing contact would silently become a
     * stranger.
     */
    private fun dhKeys(): Pair<String, ByteArray> {
        val storedPriv = prefs.getString(KEY_DH_PRIV, null)?.let(::decode)
        val storedPub = prefs.getString(KEY_DH_PUB, null)
        if (storedPriv != null && storedPub != null &&
            storedPriv.size == X25519.PRIVATE_KEY_SIZE
        ) {
            return storedPub to storedPriv
        }
        val fresh = X25519.generateKeypair()
        val pub = encode(fresh.publicKey)
        prefs.edit()
            .putString(KEY_DH_PRIV, encode(fresh.privateKey))
            .putString(KEY_DH_PUB, pub)
            .apply()
        return pub to fresh.privateKey
    }

    /** A fingerprint is what people compare aloud; the raw key need not travel. */
    private fun fingerprint(publicKeyB64: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(publicKeyB64.toByteArray(Charsets.UTF_8))
        return digest.take(FINGERPRINT_BYTES).joinToString("") { "%02x".format(it) }
    }

    private fun encode(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.NO_PADDING)

    private fun decode(text: String): ByteArray? =
        runCatching { Base64.decode(text, Base64.NO_WRAP or Base64.NO_PADDING) }.getOrNull()

    private companion object {
        const val FILE_NAME = "phantom_identity_v1"
        const val KEY_PRIV = "ed25519_priv"
        const val KEY_PUB = "ed25519_pub"
        const val KEY_DH_PRIV = "x25519_priv"
        const val KEY_DH_PUB = "x25519_pub"

        /** 8 bytes → 16 hex chars: long enough to be unique, short enough to read. */
        const val FINGERPRINT_BYTES = 8
    }
}
