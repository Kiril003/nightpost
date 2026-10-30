package local.phantom.companion.core.data.vault

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.sqlite.db.SupportSQLiteDatabase
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val KEY_ALIAS = "phantom_vault_value_v1"
private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val TRANSFORMATION = "AES/GCM/NoPadding"
private const val WIRE_PREFIX = "PV1:"
private const val IV_BYTES = 12
private const val TAG_BITS = 128

/**
 * Per-value encryption for [VaultEntity.value].
 *
 * The database file is already SQLCipher-encrypted, but that made the vault's
 * promise exactly as strong as one key: anything that reads the opened DB —
 * a debuggable build, a `adb shell run-as` on the decrypted handle, a future
 * export/backup path, a Room inspector — read `secrets.alerts_in_ua_token`
 * and every saved credential in the clear. Values are now sealed a second
 * time under a key that never leaves the Keystore, so a DB-level leak yields
 * ciphertext and the plaintext requires code execution as this app.
 *
 * Wire format is `PV1:` + base64(iv ‖ ciphertext‖tag). The IV is per value —
 * `setRandomizedEncryptionRequired` makes the provider mint a fresh one on
 * every `ENCRYPT_MODE` init and refuse a caller-supplied one, so two entries
 * holding the same secret never produce the same bytes.
 */
interface VaultCipher {

    fun encrypt(plaintext: String): String

    fun decrypt(stored: String): String

    companion object {

        /**
         * The real thing. Deliberately has no non-Keystore fallback: if the
         * Keystore is unreachable the vault fails closed rather than quietly
         * writing plaintext under a name that claims otherwise.
         */
        fun deviceDefault(): VaultCipher = KeystoreVaultCipher

        fun isEncrypted(stored: String): Boolean = stored.startsWith(WIRE_PREFIX)
    }
}

internal object KeystoreVaultCipher : VaultCipher {

    @Volatile
    private var cached: SecretKey? = null

    override fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val sealed = cipher.iv + cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return WIRE_PREFIX + Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    override fun decrypt(stored: String): String {
        // Un-prefixed rows are pre-MIGRATION_20_21 plaintext and pass through:
        // the ladder converts them, but a read must not garble one it meets.
        if (!VaultCipher.isEncrypted(stored)) return stored
        val sealed = Base64.decode(stored.removePrefix(WIRE_PREFIX), Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES),
        )
        return String(cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES), Charsets.UTF_8)
    }

    // Synchronized because two racing generateKey() calls on one alias leave
    // the loser's ciphertext undecryptable for good.
    @Synchronized
    private fun secretKey(): SecretKey {
        cached?.let { return it }
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = (store.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
        val key = existing ?: generate()
        cached = key
        return key
    }

    private fun generate(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                // No setUserAuthenticationRequired: background readers (the
                // alerts token fetch) must work with the screen off, and the
                // operator-facing gate is VaultStore.locked, not the key.
                .build(),
        )
        return generator.generateKey()
    }
}

/**
 * Seals every legacy plaintext row in place. Runs inside Room's migration
 * transaction, so a Keystore failure rolls the whole thing back and leaves the
 * rows readable rather than half-converted.
 */
internal fun reencryptLegacyValues(db: SupportSQLiteDatabase, cipher: VaultCipher) {
    val legacy = mutableListOf<Pair<String, String>>()
    db.query("SELECT `id`, `value` FROM `vault_entries`").use { cursor ->
        while (cursor.moveToNext()) {
            val value = cursor.getString(1) ?: continue
            if (!VaultCipher.isEncrypted(value)) legacy += cursor.getString(0) to value
        }
    }
    legacy.forEach { (id, value) ->
        db.execSQL(
            "UPDATE `vault_entries` SET `value` = ? WHERE `id` = ?",
            arrayOf(cipher.encrypt(value), id),
        )
    }
}
