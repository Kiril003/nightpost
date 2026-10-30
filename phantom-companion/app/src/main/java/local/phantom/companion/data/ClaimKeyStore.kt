package local.phantom.companion.data

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import local.phantom.companion.core.net.crypto.Ed25519

/**
 * Ключ заяв (№6.4): власний файл і власний майстер-ключ Keystore, не поруч із ключем
 * месенджера. Приватна частина не виходить звідси нікуди, крім [ClaimSigner].
 */
class ClaimKeyStore(context: Context) {

    private val prefs = run {
        val masterKey = MasterKey.Builder(context, MASTER_ALIAS)
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

    /** Карбує при першому виклику. Блокує (Keystore) — не з композиції. */
    @Synchronized
    fun get(): ClaimKey {
        val priv = prefs.getString(KEY_PRIV, null)?.let(::decode)
        val pub = prefs.getString(KEY_PUB, null)
        if (priv != null && pub != null) return ClaimKey(pub, priv)
        val fresh = Ed25519.generateKeypair()
        val freshPub = encode(fresh.publicKey)
        prefs.edit()
            .putString(KEY_PRIV, encode(fresh.privateKey))
            .putString(KEY_PUB, freshPub)
            .commit()
        return ClaimKey(freshPub, fresh.privateKey)
    }

    /** Встановлює ключ заяв з відновленої резервної копії. */
    @Synchronized
    fun adopt(claimKey: ClaimKey) {
        prefs.edit()
            .putString(KEY_PRIV, encode(claimKey.privateKey))
            .putString(KEY_PUB, claimKey.publicKeyB64)
            .commit()
    }

    private fun encode(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.NO_PADDING)

    private fun decode(text: String): ByteArray? =
        runCatching { Base64.decode(text, Base64.NO_WRAP or Base64.NO_PADDING) }.getOrNull()

    private companion object {
        const val FILE_NAME = "phantom_claims_v1"
        const val MASTER_ALIAS = "phantom_claims_master"
        const val KEY_PRIV = "claims_ed25519_priv"
        const val KEY_PUB = "claims_ed25519_pub"
    }
}
