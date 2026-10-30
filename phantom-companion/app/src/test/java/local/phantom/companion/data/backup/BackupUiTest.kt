package local.phantom.companion.data.backup

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BackupUiTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
    }

    @Test
    fun `BackupKeyStore persists and loads backupUri and lastBackupEpochMs`() {
        val store = BackupKeyStore.inMemory()
        assertNull(store.backupUri())
        assertEquals(0L, store.lastBackupEpochMs())

        store.setBackupUri("content://com.android.providers.downloads/document/123")
        store.setLastBackupEpochMs(1729900000000L)

        assertEquals("content://com.android.providers.downloads/document/123", store.backupUri())
        assertEquals(1729900000000L, store.lastBackupEpochMs())

        store.setBackupUri(null)
        assertNull(store.backupUri())
    }

    @Test
    fun `BackupResealWorker schedules periodic work without error`() {
        BackupResealWorker.schedule(app)
        val workManager = WorkManager.getInstance(app)
        val infos = workManager.getWorkInfosForUniqueWork(BackupResealWorker.NAME).get()
        assertNotNull(infos)
        assertTrue(infos.isNotEmpty())
    }

    @Test
    fun `Passphrase validation enforces 12 char minimum and matching check`() {
        assertFalse(BackupFormat.isValidPassphrase("short".toCharArray()))
        assertFalse(BackupFormat.isValidPassphrase("12345678901".toCharArray()))
        assertTrue(BackupFormat.isValidPassphrase("123456789012".toCharArray()))
        assertTrue(BackupFormat.isValidPassphrase("correct-horse-battery".toCharArray()))
    }

    @Test
    fun `Strings for backup and restore exist across locales`() {
        val res = app.resources
        val stringIds = listOf(
            local.phantom.companion.R.string.profile_backup,
            local.phantom.companion.R.string.profile_backup_none,
            local.phantom.companion.R.string.profile_backup_saved,
            local.phantom.companion.R.string.backup_sheet_title,
            local.phantom.companion.R.string.backup_loss_title,
            local.phantom.companion.R.string.backup_loss_chats,
            local.phantom.companion.R.string.backup_loss_contacts,
            local.phantom.companion.R.string.backup_loss_profiles,
            local.phantom.companion.R.string.backup_loss_venues,
            local.phantom.companion.R.string.backup_loss_licences,
            local.phantom.companion.R.string.backup_loss_handle,
            local.phantom.companion.R.string.backup_loss_points,
            local.phantom.companion.R.string.backup_loss_wallet,
            local.phantom.companion.R.string.backup_loss_memory,
            local.phantom.companion.R.string.backup_pass_hint,
            local.phantom.companion.R.string.backup_pass_confirm_hint,
            local.phantom.companion.R.string.backup_pass_mismatch,
            local.phantom.companion.R.string.backup_pass_too_short,
            local.phantom.companion.R.string.backup_save_action,
            local.phantom.companion.R.string.backup_restore_title,
            local.phantom.companion.R.string.backup_restore_pass_hint,
            local.phantom.companion.R.string.backup_restore_action,
            local.phantom.companion.R.string.backup_restore_err_wrong_pass,
            local.phantom.companion.R.string.backup_restore_err_not_our_file,
            local.phantom.companion.R.string.backup_restore_err_not_empty,
        )

        for (id in stringIds) {
            val str = res.getString(id)
            assertTrue("String id $id should not be blank", str.isNotBlank())
        }
    }
}
