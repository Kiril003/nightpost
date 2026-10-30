package local.phantom.companion.core.solana

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Після збереження людина бачить, де копія і чи вона від цього гаманця; перевірка гаманця не міняє. */
@RunWith(RobolectricTestRunner::class)
class WalletBackupStatusTest {

    private val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("backup-status-test", Context.MODE_PRIVATE)
    private val bip39 = Bip39(File("src/main/assets/bip39_english.txt").readLines().filter { it.isNotBlank() })
    private val mine = bip39.fromEntropy(ByteArray(32) { (it * 31 + 7).toByte() })
    private val other = bip39.fromEntropy(ByteArray(32) { (it * 17 + 3).toByte() })
    private val pass = "river-table-9"

    @Test fun nothingSavedYetIsNone() {
        prefs.edit().clear().commit()
        assertEquals(BackupStatus.None, BackupStatus.of(prefs))
    }

    @Test fun wordsWithoutFileIsWordsOnly() {
        prefs.edit().clear().putBoolean("backed_up", true).commit()
        assertEquals(BackupStatus.WordsOnly, BackupStatus.of(prefs))
    }

    @Test fun savedCopyRemembersWhenAndWhere() {
        prefs.edit().clear().commit()
        BackupStatus.record(prefs.edit(), 1_760_000_000_000L, "nightpost-wallet.backup", "Диск").commit()
        assertEquals(BackupStatus.Copy(1_760_000_000_000L, "nightpost-wallet.backup", "Диск"), BackupStatus.of(prefs))
        assertEquals(setOf("backed_up", "copy_at", "copy_name", "copy_place"), prefs.all.keys)
    }

    @Test fun restoringForgetsTheOldCopyButStaysBackedUp() {
        prefs.edit().clear().commit()
        BackupStatus.record(prefs.edit(), 1L, "a", "b").commit()
        BackupStatus.forget(prefs.edit()).commit()
        assertEquals(BackupStatus.WordsOnly, BackupStatus.of(prefs))
    }

    @Test fun placeNamesKnownStoresAndFallsBackToTheApp() {
        assertEquals(CopyPlace.DOWNLOADS, CopyPlace.of("com.android.providers.downloads.documents", "msf:12") { "x" })
        assertEquals(CopyPlace.DOWNLOADS, CopyPlace.of("com.android.externalstorage.documents", "primary:Download/n.backup") { "x" })
        assertEquals(CopyPlace.PHONE, CopyPlace.of("com.android.externalstorage.documents", "primary:Documents/n.backup") { "x" })
        assertEquals("Диск", CopyPlace.of("com.google.android.apps.docs.storage", "doc=1") { " Диск " })
        assertEquals(null, CopyPlace.of("com.example.box", "1") { "" })
    }

    @Test fun statusLineIsDateAndPlace() {
        val at = java.time.LocalDate.of(2026, 10, 18).atTime(12, 0).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()
        assertEquals("18 Oct · Drive", CopyPlace.line(at, "Drive", Locale.UK, ZoneId.of("UTC")))
        assertEquals("18 Oct", CopyPlace.line(at, null, Locale.UK, ZoneId.of("UTC")))
    }

    @Test fun checkTellsSameOtherOrBadPassword() = runBlocking {
        val file = WalletBackupFile.seal(mine, pass.toCharArray())
        assertEquals(CopyCheck.Result.SAME, CopyCheck.of(file, pass.toCharArray()) { mine })
        assertEquals(CopyCheck.Result.OTHER_WALLET, CopyCheck.of(file, pass.toCharArray()) { other })
        assertEquals(CopyCheck.Result.LOCKED, CopyCheck.of(file, pass.toCharArray()) { null })
    }

    @Test fun wrongPasswordNeverAsksThePhoneForWords() = runBlocking {
        val file = WalletBackupFile.seal(mine, pass.toCharArray())
        var asked = false
        assertEquals(CopyCheck.Result.BAD_PASSWORD, CopyCheck.of(file, "river-table-8".toCharArray()) { asked = true; mine })
        assertFalse(asked)
    }
}
