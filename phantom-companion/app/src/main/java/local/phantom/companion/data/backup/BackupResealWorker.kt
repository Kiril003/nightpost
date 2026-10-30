package local.phantom.companion.data.backup

import android.content.Context
import android.net.Uri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import local.phantom.companion.PhantomApp

internal interface BackupDoc {
    fun openRead(): InputStream?
    fun openWrite(): OutputStream?
}

internal enum class ResealOutcome { DONE, NOTHING, RETRY }

internal fun resealGeneration(
    keys: BackupKeyStore,
    dir: File,
    doc: (String) -> BackupDoc?,
    seal: (OutputStream) -> BackupFormat.Header?,
    nowMs: Long = System.currentTimeMillis(),
): ResealOutcome {
    val uri = keys.backupUri() ?: return ResealOutcome.NOTHING
    val key = keys.load()
    if (key == null) {
        keys.setBackupUri(null)
        keys.setLastBackupEpochMs(0L)
        return ResealOutcome.NOTHING
    }
    val target = doc(uri) ?: return ResealOutcome.RETRY
    dir.mkdirs()
    val next = File(dir, NEXT)
    val prev = File(dir, PREV)
    try {
        next.outputStream().use(seal) ?: return ResealOutcome.RETRY
        if (!opens(next, key)) return ResealOutcome.RETRY

        val current = runCatching { target.openRead()?.use { it.readBytes() } }.getOrNull()
        if (current != null && current.isNotEmpty()) {
            val tmp = File(dir, "$PREV.tmp")
            tmp.writeBytes(current)
            tmp.renameTo(prev)
        }
        val written = runCatching {
            (target.openWrite() ?: error("no write")).use { out -> next.inputStream().use { it.copyTo(out) } }
        }.isSuccess
        if (!written) {
            if (prev.isFile) runCatching { target.openWrite()?.use { out -> prev.inputStream().use { it.copyTo(out) } } }
            return ResealOutcome.RETRY
        }
        keys.setLastBackupEpochMs(nowMs)
        return ResealOutcome.DONE
    } catch (e: Exception) {
        return ResealOutcome.RETRY
    } finally {
        next.delete()
    }
}

private fun opens(file: File, key: BackupFormat.SealedKey): Boolean {
    val raw = key.rawKey()
    return try {
        file.inputStream().use { BackupFormat.openWithKey(it, Sink, raw) }
    } finally {
        raw.fill(0)
    }
}

private object Sink : OutputStream() {
    override fun write(b: Int) = Unit
    override fun write(b: ByteArray, off: Int, len: Int) = Unit
}

private const val NEXT = "reseal_next.backup"
private const val PREV = "reseal_prev.backup"

class BackupResealWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = runCatching { PhantomApp.container() }.getOrNull() ?: return Result.retry()
        val resolver = applicationContext.contentResolver
        val outcome = resealGeneration(
            keys = container.backupKeys,
            dir = File(applicationContext.noBackupFilesDir, "backup"),
            doc = { uriStr ->
                runCatching { Uri.parse(uriStr) }.getOrNull()?.let { uri ->
                    object : BackupDoc {
                        override fun openRead() = resolver.openInputStream(uri)
                        override fun openWrite() = resolver.openOutputStream(uri, "wt")
                    }
                }
            },
            seal = { container.resealBackup(it) },
        )
        return if (outcome == ResealOutcome.RETRY) Result.retry() else Result.success()
    }

    companion object {
        const val NAME = "phantom_backup_reseal_weekly"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<BackupResealWorker>(7, TimeUnit.DAYS, 1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .addTag(NAME)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
