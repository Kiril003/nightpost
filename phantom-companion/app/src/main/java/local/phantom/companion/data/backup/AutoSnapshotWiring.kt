package local.phantom.companion.data.backup

import android.app.KeyguardManager
import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import local.phantom.companion.PhantomApp
import local.phantom.companion.core.data.vault.VaultCipher
import local.phantom.companion.core.solana.SolanaLayer
import local.phantom.companion.core.solana.WalletEscrow
import local.phantom.companion.core.solana.WalletReturn
import local.phantom.companion.data.AppContainer

private const val FRESH_MS = 15L * 60 * 1000

internal object AutoBackup {
    @Volatile private var setting: CloudBackupSetting? = null
    @Volatile var keysFor: (Context) -> SnapshotKeys = { DeviceSnapshotKeys(it) }

    fun setting(context: Context): CloudBackupSetting =
        setting ?: synchronized(this) { setting ?: CloudBackupSetting(context).also { setting = it } }

    fun held(context: Context): Boolean = AutoSnapshot.held(context.noBackupFilesDir)

    @Volatile var slotFor: (Context) -> WalletSlot = { WalletSlot(it.noBackupFilesDir, VaultCipher.deviceDefault()) }

    fun secure(context: Context): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
}

internal fun AppContainer.wireWalletEscrow() {
    val app = applicationContext
    WalletEscrow.keep = { words ->
        if (AutoBackup.setting(app).on.value && AutoBackup.secure(app)) {
            AutoBackup.slotFor(app).keep(words)
            scope.launch(Dispatchers.IO) { runCatching { writeAutoSnapshot() } }
        }
    }
    WalletEscrow.waiting = { AutoBackup.slotFor(app).held() }
    WalletEscrow.settle = { AutoBackup.slotFor(app).settle() }
}

internal fun AppContainer.setCloudBackup(on: Boolean) {
    val app = applicationContext
    AutoBackup.setting(app).set(on)
    AutoSnapshot.release(app.noBackupFilesDir)
    if (on) {
        writeAutoSnapshot()
    } else {
        AutoSnapshot.file(app.filesDir).delete()
        AutoBackup.keysFor(app).forget()
        AutoBackup.slotFor(app).drop()
    }
}

internal fun AppContainer.writeAutoSnapshot(nowMs: Long = System.currentTimeMillis()): AutoSnapshot.Written {
    val app = applicationContext
    if (AutoSnapshot.frozen || autoRestorePending(app) || AutoSnapshot.held(app.noBackupFilesDir)) return AutoSnapshot.Written.SKIPPED
    val enabled = AutoBackup.setting(app).on.value
    val words = runCatching {
        app.assets.open("bip39_english.txt").bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }.getOrDefault(emptySet())
    val wallet = WalletReturn.expected(app) ?: runCatching { SolanaLayer.state.value.builtIn?.base58 }.getOrNull()
    val secure = AutoBackup.secure(app)
    return AutoSnapshot.write(
        filesDir = app.filesDir,
        noBackupDir = app.noBackupFilesDir,
        snapshot = AutoSnapshot.Snapshot(
            atMs = nowMs,
            wallet = wallet,
            manifest = AutoSnapshot.fit(collectBackupManifest()).encode(),
            walletKey = if (enabled && secure) AutoBackup.slotFor(app).forCopy() else null,
        ),
        walletWords = words,
        key = if (enabled) AutoBackup.keysFor(app).pick().also { AutoBackup.setting(app).cloudKey(it.inCloud) }.key else null,
        enabled = enabled,
        deviceSecure = secure,
    )
}

internal fun AppContainer.refreshAutoSnapshot(nowMs: Long = System.currentTimeMillis()) {
    val age = AutoSnapshot.ageMs(applicationContext.filesDir, nowMs)
    if (age == null || age !in 0 until FRESH_MS) writeAutoSnapshot(nowMs)
}

internal fun AppContainer.autoRestore(): RestoreResult? {
    val app = applicationContext
    AutoSnapshot.frozen = true
    val keys = AutoBackup.keysFor(app)
    val key = runCatching { keys.local() ?: keys.cloud() }.getOrNull()
    return AutoSnapshot.restore(app.filesDir, app.noBackupFilesDir, key) { snap ->
        val manifest = BackupManifest.decode(snap.manifest) ?: return@restore RestoreResult.Error(RestoreResult.Reason.INVALID)
        restoreInto(manifest, ContainerRestoreTarget(this)).also { result ->
            if (result is RestoreResult.Success) {
                key?.let(keys::keepLocal)
                snap.wallet?.let { WalletReturn.expect(app, it) }
                snap.walletKey?.let { AutoBackup.slotFor(app).hold(it) }
            }
        }
    }
}

internal fun autoRestorePending(context: Context): Boolean =
    AutoSnapshot.pending(context.filesDir, context.noBackupFilesDir)

class AutoSnapshotWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = runCatching { PhantomApp.container() }.getOrNull() ?: return Result.retry()
        runCatching { container.writeAutoSnapshot() }
        return Result.success()
    }

    companion object {
        const val NAME = "phantom_autobackup_snapshot"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<AutoSnapshotWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .addTag(NAME)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
