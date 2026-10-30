package local.phantom.companion.data.backup

import java.io.File
import local.phantom.companion.core.data.vault.VaultCipher

// Both files sit in noBackupFilesDir under a Keystore key: the words reach Google only inside the sealed snapshot.
internal class WalletSlot(private val dir: File, private val cipher: VaultCipher) {
    private val live get() = File(dir, "wallet_slot")
    private val back get() = File(dir, "wallet_slot_back")

    fun keep(words: List<String>) = put(live, words)

    fun hold(words: List<String>) = put(back, words)

    fun held(): List<String>? = get(back)

    // Until the person restores or drops the old wallet, the copy keeps carrying it, not the one made on this phone.
    fun forCopy(): List<String>? = held() ?: get(live)

    fun has(): Boolean = back.isFile || live.isFile

    fun settle() {
        back.delete()
    }

    fun drop() {
        live.delete()
    }

    private fun put(f: File, words: List<String>) {
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, f.name + ".tmp")
            tmp.writeText(cipher.encrypt(words.joinToString(" ")))
            check(tmp.renameTo(f))
        }
    }

    private fun get(f: File): List<String>? = runCatching {
        if (!f.isFile) return null
        cipher.decrypt(f.readText()).split(' ').filter { it.isNotEmpty() }
    }.getOrNull()?.takeIf { it.isNotEmpty() }
}

internal enum class WalletInCopy { NONE, IN, NO_LOCK, NEEDS_WORDS }

internal fun walletInCopy(hasWallet: Boolean, on: Boolean, held: Boolean, secure: Boolean, slot: Boolean): WalletInCopy = when {
    !hasWallet || !on || held -> WalletInCopy.NONE
    !secure -> WalletInCopy.NO_LOCK
    !slot -> WalletInCopy.NEEDS_WORDS
    else -> WalletInCopy.IN
}
