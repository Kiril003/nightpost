package local.phantom.companion.ui.messenger

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import local.phantom.companion.core.net.peer.PeerFile

/**
 * Зберегти отримане фото чи файл у памʼять телефона.
 *
 * У WebView немає DownloadListener, тож `<a download>` на blob: не робив
 * нічого. Веб віддає вже розшифровані байти сюди, а сюди — лише MediaStore:
 * minSdk 29, дозволу на запис не треба, дублікати імен MediaStore нумерує сам.
 */
object WebMessengerSave {

    enum class Shelf(val where: String) {
        PICTURES("Pictures/Nightpost"),
        MOVIES("Movies/Nightpost"),
        DOWNLOADS("Download/Nightpost"),
    }

    sealed interface Verdict {
        data class Ready(val name: String, val mime: String, val shelf: Shelf, val bytes: ByteArray) : Verdict
        data class Refused(val status: Int, val code: String) : Verdict
    }

    const val MAX_NAME_CHARS = 120

    private val DANGEROUS_EXT = setOf(
        "apk", "apks", "xapk", "aab", "dex", "so", "jar", "exe", "dll", "msi", "com", "scr",
        "bat", "cmd", "ps1", "vbs", "sh",
    )

    private val DANGEROUS_MIME = setOf(
        "application/vnd.android.package-archive",
        "application/x-msdownload",
        "application/x-msdos-program",
        "application/x-executable",
        "application/x-sh",
        "application/java-archive",
    )

    private val EXT_OF_MIME = mapOf(
        "image/jpeg" to "jpg", "image/png" to "png", "image/webp" to "webp", "image/gif" to "gif",
        "image/heic" to "heic", "image/heif" to "heif", "image/bmp" to "bmp",
        "video/mp4" to "mp4", "video/webm" to "webm", "video/quicktime" to "mov", "video/3gpp" to "3gp",
        "audio/mpeg" to "mp3", "audio/ogg" to "ogg", "audio/mp4" to "m4a", "audio/webm" to "weba",
        "application/pdf" to "pdf", "text/plain" to "txt", "application/zip" to "zip",
    )

    private val MIME_OF_EXT: Map<String, String> =
        EXT_OF_MIME.entries.associate { (m, e) -> e to m } + mapOf("jpeg" to "image/jpeg", "m4v" to "video/mp4")

    fun shelfFor(mime: String): Shelf = when {
        mime.startsWith("image/") -> Shelf.PICTURES
        mime.startsWith("video/") -> Shelf.MOVIES
        else -> Shelf.DOWNLOADS
    }

    /**
     * Імʼя, яке не виходить за теку й не ховається: без роздільників шляху,
     * без крапок попереду, з розширенням — з імені або з [mime].
     */
    fun cleanName(raw: String, mime: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
            .filterNot { it.isISOControl() || it in "<>:\"|?*" }
            .trim().trimStart('.').trim()
        var stem = base.substringBeforeLast('.', base).trim()
        var ext = if (base.contains('.')) base.substringAfterLast('.').lowercase().trim() else ""
        if (ext.isEmpty() || ext.length > 8 || !ext.all { it.isLetterOrDigit() }) {
            if (ext.isNotEmpty()) stem = base
            ext = EXT_OF_MIME[mime].orEmpty()
        }
        if (stem.isEmpty()) stem = "Nightpost"
        val tail = if (ext.isEmpty()) "" else ".$ext"
        return stem.take(MAX_NAME_CHARS - tail.length).trimEnd() + tail
    }

    /** Усе, що можна вирішити без Android: розмір, рід, імʼя, полиця. */
    fun judge(rawName: String, rawMime: String, b64: String): Verdict {
        if (b64.isEmpty()) return Verdict.Refused(400, "bad_body")
        // До розкодування: 43 МБ base64 не варто перетворювати на 32 МБ байтів, щоб відмовити.
        if (b64.length / 4L * 3 > PeerFile.MAX_BYTES + 3L) return Verdict.Refused(413, "too_big")
        val mime = rawMime.trim().lowercase().substringBefore(';').trim()
            .ifEmpty { "application/octet-stream" }
        if (!Regex("[a-z0-9.+-]+/[a-z0-9.+-]+").matches(mime)) return Verdict.Refused(415, "bad_mime")
        val name = cleanName(rawName, mime)
        val ext = name.substringAfterLast('.', "").lowercase()
        if (mime in DANGEROUS_MIME || ext in DANGEROUS_EXT) return Verdict.Refused(415, "bad_mime")
        val shelf = shelfFor(mime)
        // Фото з розширенням не-фото — це не фото: полиця Pictures не прийме
        // «.pdf», а галерея показала б порожній кадр.
        val claimed = MIME_OF_EXT[ext]
        if (shelf != Shelf.DOWNLOADS && claimed != null && claimed.substringBefore('/') != mime.substringBefore('/')) {
            return Verdict.Refused(415, "bad_mime")
        }
        val bytes = runCatching { java.util.Base64.getDecoder().decode(b64.trim()) }.getOrNull()
            ?: return Verdict.Refused(400, "bad_body")
        if (bytes.isEmpty()) return Verdict.Refused(400, "bad_body")
        if (bytes.size > PeerFile.MAX_BYTES) return Verdict.Refused(413, "too_big")
        return Verdict.Ready(name, mime, shelf, bytes)
    }

    /** Остаточне імʼя, яке дав MediaStore, або null — і тоді нічого не лишилось. */
    fun write(resolver: ContentResolver, file: Verdict.Ready): String? {
        val collection: Uri = when (file.shelf) {
            Shelf.PICTURES -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            Shelf.MOVIES -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            Shelf.DOWNLOADS -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, file.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, file.shelf.where)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = runCatching { resolver.insert(collection, values) }.getOrNull() ?: return null
        return runCatching {
            resolver.openOutputStream(uri)?.use { it.write(file.bytes) } ?: error("no stream")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: file.name
        }.getOrElse {
            // Напівзаписаний файл із IS_PENDING висів би в галереї невидимим тижнями.
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }
}
