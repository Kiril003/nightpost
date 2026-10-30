package local.phantom.companion.core.solana

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

sealed interface BackupStatus {
    data object None : BackupStatus
    data object WordsOnly : BackupStatus
    data class Copy(val atMs: Long, val name: String?, val place: String?) : BackupStatus

    companion object {
        internal const val K_BACKED_UP = "backed_up"
        private const val K_AT = "copy_at"
        private const val K_NAME = "copy_name"
        private const val K_PLACE = "copy_place"

        fun of(prefs: SharedPreferences): BackupStatus {
            val at = prefs.getLong(K_AT, 0L)
            return when {
                at > 0 -> Copy(at, prefs.getString(K_NAME, null), prefs.getString(K_PLACE, null))
                prefs.getBoolean(K_BACKED_UP, false) -> WordsOnly
                else -> None
            }
        }

        fun record(edit: SharedPreferences.Editor, atMs: Long, name: String?, place: String?): SharedPreferences.Editor = edit
            .putBoolean(K_BACKED_UP, true)
            .putLong(K_AT, atMs)
            .apply { if (name != null) putString(K_NAME, name) else remove(K_NAME) }
            .apply { if (place != null) putString(K_PLACE, place) else remove(K_PLACE) }

        fun forget(edit: SharedPreferences.Editor): SharedPreferences.Editor = edit.remove(K_AT).remove(K_NAME).remove(K_PLACE)
    }
}

internal val WalletState.backupShown: BackupStatus
    get() = when {
        !backedUp -> BackupStatus.None
        backup == BackupStatus.None -> BackupStatus.WordsOnly
        else -> backup
    }

/** Де лежить копія: знайомі сховища — власними словами, решта — назвою застосунку, що дав місце. */
internal object CopyPlace {
    const val DOWNLOADS = "@downloads"
    const val PHONE = "@phone"

    fun of(authority: String?, documentId: String?, appLabel: () -> String?): String? = when (authority) {
        null -> null
        "com.android.providers.downloads.documents" -> DOWNLOADS
        "com.android.externalstorage.documents" ->
            if (documentId?.substringAfter(':')?.startsWith("Download", ignoreCase = true) == true) DOWNLOADS else PHONE
        else -> appLabel()?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun read(ctx: Context, uri: Uri): Pair<String?, String?> {
        val name = runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
        val id = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
        val place = of(uri.authority, id) {
            runCatching {
                val pm = ctx.packageManager
                pm.resolveContentProvider(uri.authority!!, 0)?.applicationInfo?.loadLabel(pm)?.toString()
            }.getOrNull()
        }
        return name to place
    }

    fun label(ctx: Context, place: String?): String? = when (place) {
        DOWNLOADS -> ctx.getString(R.string.sol_place_downloads)
        PHONE -> ctx.getString(R.string.sol_place_phone)
        else -> place
    }

    fun line(atMs: Long, place: String?, locale: Locale, zone: ZoneId = ZoneId.systemDefault()): String {
        val day = DateTimeFormatter.ofPattern("d MMM", locale).format(Instant.ofEpochMilli(atMs).atZone(zone))
        return if (place.isNullOrEmpty()) day else "$day · $place"
    }
}

/** Звірити файл-копію з гаманцем на телефоні. Гаманець не міняє — лише відповідає, чи це той самий. */
internal object CopyCheck {
    enum class Result { SAME, OTHER_WALLET, BAD_PASSWORD, LOCKED }

    // Слова з телефона просимо лише після того, як файл відкрився: інакше відбиток питали б даремно.
    suspend fun of(file: ByteArray, password: CharArray, current: suspend () -> List<String>?): Result {
        val inFile = WalletBackupFile.open(file, password) ?: return Result.BAD_PASSWORD
        val mine = runCatching { current() }.getOrNull() ?: return Result.LOCKED
        return if (norm(inFile) == norm(mine)) Result.SAME else Result.OTHER_WALLET
    }

    private fun norm(words: List<String>) = words.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
}
