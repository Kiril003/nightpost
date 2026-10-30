package local.phantom.companion.ui.messenger

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

/**
 * Вибрані для листа файли — копією в кеш застосунку, а не чужим URI.
 *
 * 25.09 на A52 (Android 14): фото з системного вибору не надсилалось зовсім.
 * Chromium знімає метадані файла в мить вибору, а провайдер фото віддає під
 * час читання інші — і WebView кидає NotReadableError («could not be read,
 * typically due to permission problems…»). Власна копія під FileProvider
 * стабільна: її ніхто не міняє між вибором і читанням.
 */
object WebPickedFiles {

    internal const val DIR = "picked"
    private const val MAX_AGE_MS = 24L * 60 * 60 * 1000

    /** Імʼя для копії: без шляху, службових символів і крапки на початку. */
    fun safeName(raw: String?, fallback: String = "file"): String {
        val base = raw.orEmpty().substringAfterLast('/').substringAfterLast('\\')
            .filter { it.code >= 0x20 && it !in "<>:\"|?*" }
            .trimStart('.', ' ')
            .take(120)
        return base.ifBlank { fallback }
    }

    /** Копії старші за добу вже нікому не потрібні: лист або поїхав, або ні. */
    fun sweep(root: File, nowMs: Long = System.currentTimeMillis()) {
        root.listFiles()?.forEach { dir ->
            if (nowMs - dir.lastModified() > MAX_AGE_MS) dir.deleteRecursively()
        }
    }

    /** Блокує: кликати поза головним потоком. Не вдалось скопіювати — лишає оригінал. */
    fun copyAll(context: Context, uris: Array<Uri>): Array<Uri> {
        val root = File(context.cacheDir, DIR).apply { mkdirs() }
        sweep(root)
        return uris.map { uri -> runCatching { copyOne(context, root, uri) }.getOrNull() ?: uri }.toTypedArray()
    }

    private fun copyOne(context: Context, root: File, uri: Uri): Uri {
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
        val dir = File(root, UUID.randomUUID().toString()).apply { mkdirs() }
        val out = File(dir, safeName(name))
        resolver.openInputStream(uri).use { input ->
            requireNotNull(input)
            out.outputStream().use { input.copyTo(it) }
        }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", out)
    }
}
