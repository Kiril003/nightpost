package local.phantom.companion.core.solana

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Файл-копія: слова повертаються лише з тим самим паролем, і в самому файлі їх не видно. */
class WalletBackupFileTest {

    private val bip39 = Bip39(File("src/main/assets/bip39_english.txt").readLines().filter { it.isNotBlank() })
    private val words = bip39.fromEntropy(ByteArray(32) { (it * 31 + 7).toByte() })
    private val pass = "river-table-9".toCharArray()

    @Test fun samePasswordOpensTheSameWords() {
        val file = WalletBackupFile.seal(words, pass.copyOf())
        assertEquals(words, WalletBackupFile.open(file, pass.copyOf()))
    }

    @Test fun wordsNeverLieInTheFileInTheClear() {
        val text = WalletBackupFile.seal(words, pass.copyOf()).decodeToString()
        assertFalse(words.any { text.contains(it) })
    }

    @Test fun wrongPasswordOrForeignFileOpensNothing() {
        val file = WalletBackupFile.seal(words, pass.copyOf())
        assertNull(WalletBackupFile.open(file, "river-table-8".toCharArray()))
        assertNull(WalletBackupFile.open("hello".toByteArray(), pass.copyOf()))
        val tampered = file.decodeToString().replace("\"data\":\"", "\"data\":\"AAAA").toByteArray()
        assertNull(WalletBackupFile.open(tampered, pass.copyOf()))
    }

    @Test fun twoCopiesOfOneWalletDiffer() {
        assertFalse(WalletBackupFile.seal(words, pass.copyOf()).contentEquals(WalletBackupFile.seal(words, pass.copyOf())))
    }

    @Test(expected = IllegalArgumentException::class)
    fun shortPasswordIsRefused() {
        WalletBackupFile.seal(words, "short".toCharArray())
    }

    @Test fun passwordAskedTwiceMustMatchAndBeLongEnough() {
        assertEquals(R.string.sol_copy_password, CopyPassword.problem("short", "short"))
        assertEquals(R.string.sol_copy_mismatch, CopyPassword.problem("river-table-9", "river-table-8"))
        assertNull(CopyPassword.problem("river-table-9", "river-table-9"))
        assertNull("відкриття питає пароль один раз", CopyPassword.problem("river-table-9", null))
    }
}
