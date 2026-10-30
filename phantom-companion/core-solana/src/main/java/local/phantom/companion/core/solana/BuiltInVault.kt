package local.phantom.companion.core.solana

import android.app.KeyguardManager
import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Вбудований гаманець PHANTOM: власне зерно, окреме від ключа особи в месенджері, щоб розмови
 * й рух коштів не можна було звʼязати. Слова зашифровано ключем Android Keystore, який не
 * покидає апарата й відчиняється лише після відбитка/PIN; без блокування екрана гаманця немає
 * ([SeedBox], [VaultKeys]).
 */
internal class BuiltInVault(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("phantom_solana_vault", Context.MODE_PRIVATE)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val box = SeedBox(
        shelf = PrefsShelf(prefs),
        legacy = KeystoreLock(ALIAS, spec = null),
        bound = KeystoreLock(ALIAS_BOUND) { VaultKeys.spec(ALIAS_BOUND) },
        canBind = { keyguard?.isDeviceSecure == true },
    )
    private val bip39 by lazy { Bip39(context.assets.open("bip39_english.txt").bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() }) }

    val exists: Boolean get() = box.exists
    val address: SolanaKey? get() = prefs.getString(K_MAIN, null)?.let(SolanaKey::parse)
    val pocket: SolanaKey? get() = prefs.getString(K_CASH, null)?.let(SolanaKey::parse)
    val backedUp: Boolean get() = prefs.getBoolean(K_BACKED_UP, false)
    val backup: BackupStatus get() = BackupStatus.of(prefs)

    @Synchronized
    fun ensure(): SolanaKey {
        address?.takeIf { exists }?.let { return it }
        return store(bip39.generate())
    }

    @Synchronized
    fun restore(words: List<String>): SolanaKey? {
        val clean = words.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (!bip39.isValid(clean)) return null
        val key = store(clean)
        BackupStatus.forget(prefs.edit()).putBoolean(K_BACKED_UP, true).apply()
        return key
    }

    /** Кидає [VaultLocked] без свіжого розблокування і [NoScreenLock] без блокування екрана. */
    fun words(): List<String> = box.open().split(' ').also(WalletEscrow::saw)

    fun markBackedUp() = prefs.edit().putBoolean(K_BACKED_UP, true).apply()

    fun markCopied(atMs: Long, name: String?, place: String?) = BackupStatus.record(prefs.edit(), atMs, name, place).apply()

    fun keypair(path: String = Slip10.SOLANA_MAIN): Ed25519Keypair {
        val seed = Bip39.seed(words())
        try {
            val key = Slip10.derive(seed, path)
            return Ed25519Keypair(key).also { key.fill(0) }
        } finally {
            seed.fill(0)
        }
    }

    private fun store(words: List<String>): SolanaKey {
        val seed = Bip39.seed(words)
        val main = Ed25519Keypair(Slip10.derive(seed, Slip10.SOLANA_MAIN)).publicKey
        val cash = Ed25519Keypair(Slip10.derive(seed, Slip10.SOLANA_CASH)).publicKey
        seed.fill(0)
        box.seal(words.joinToString(" "))
        WalletEscrow.saw(words)
        prefs.edit()
            .putString(K_MAIN, main.base58)
            .putString(K_CASH, cash.base58)
            .apply()
        return main
    }

    private companion object {
        const val ALIAS = "phantom_solana_seed"
        const val ALIAS_BOUND = "phantom_solana_seed_bound"
        const val K_MAIN = "main"
        const val K_CASH = "cash"
        const val K_BACKED_UP = BackupStatus.K_BACKED_UP
    }
}

private class PrefsShelf(private val prefs: SharedPreferences) : SeedShelf {
    override fun get(name: String): ByteArray? = prefs.getString(name, null)?.let { Base64.getDecoder().decode(it) }

    override fun write(put: Map<String, ByteArray>, remove: Set<String>): Boolean = prefs.edit().apply {
        remove.forEach { remove(it) }
        put.forEach { (k, v) -> putString(k, Base64.getEncoder().encodeToString(v)) }
    }.commit()
}

/** [spec] null — ключ лише читається: старий ключ без автентифікації вже ніколи не створюється. */
private class KeystoreLock(private val alias: String, private val spec: (() -> KeyGenParameterSpec)?) : SeedLock {

    private fun keystore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun existing(): SecretKey? = keystore().getKey(alias, null) as? SecretKey

    private fun create(): SecretKey {
        val spec = checkNotNull(spec) { "read-only key $alias" }
        return KeyGenerator.getInstance("AES", "AndroidKeyStore").apply { init(spec()) }.generateKey()
    }

    override fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(AES)
        try {
            cipher.init(Cipher.ENCRYPT_MODE, existing() ?: create())
        } catch (e: KeyPermanentlyInvalidatedException) {
            // Блокування екрана знімали: ключ мертвий, і те, що під ним лежало, вже не прочитати.
            drop()
            cipher.init(Cipher.ENCRYPT_MODE, create())
        }
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun open(blob: ByteArray): ByteArray {
        val key = existing() ?: throw IllegalStateException("no key $alias")
        val cipher = Cipher.getInstance(AES).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob, 0, 12)) }
        return cipher.doFinal(blob, 12, blob.size - 12)
    }

    override fun drop() {
        runCatching { keystore().deleteEntry(alias) }
    }

    private companion object {
        const val AES = "AES/GCM/NoPadding"
    }
}
