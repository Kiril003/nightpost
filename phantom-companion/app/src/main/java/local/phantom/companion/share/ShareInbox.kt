package local.phantom.companion.share

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import local.phantom.companion.PhantomApp
import local.phantom.companion.ui.home.HomeTab
import local.phantom.companion.ui.messenger.WebNav

object ShareInbox {

    data class Item(
        val index: Int,
        val kind: String,
        val text: String?,
        val path: String?,
        val mime: String?,
        val name: String?,
        val size: Long,
        val lat: Double? = null,
        val lon: Double? = null,
    )

    data class Batch(
        val id: String,
        val items: List<Item>,
        val skipped: Int,
    )

    private const val MAX_TEXT = 4_000
    private const val MAX_IMAGES = 10
    private const val MAX_IMAGE_BYTES = 10L * 1024L * 1024L
    private const val HANDLED = "local.phantom.companion.share.HANDLED"
    private val SAFE_EXT = Regex("[A-Za-z0-9]{1,5}")

    val pending = MutableStateFlow<Batch?>(null)

    fun accept(context: Context, intent: Intent?): Boolean {
        if (intent == null) return false
        val action = intent.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return false
        val type = intent.type?.lowercase() ?: return false
        val isText = type == "text/plain"
        val isImage = type.startsWith("image/")
        if (!isText && !isImage) return false
        if (intent.getBooleanExtra(HANDLED, false)) return false

        val streams: List<Uri> = runCatching {
            when (action) {
                Intent.ACTION_SEND -> singleStream(intent)?.let { listOf(it) } ?: emptyList()
                else -> multipleStreams(intent)
            }
        }.getOrDefault(emptyList())
        val rawText = runCatching {
            intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim()?.take(MAX_TEXT).orEmpty()
        }.getOrDefault("")

        val root = File(context.cacheDir, "share-in")
        val batchId = UUID.randomUUID().toString()
        val batchDir = File(root, batchId).apply { mkdirs() }

        val items = mutableListOf<Item>()
        var skipped = 0
        var imageCount = 0

        if (isImage) {
            for (uri in streams) {
                if (imageCount >= MAX_IMAGES) {
                    skipped++
                    continue
                }
                val copied = copyStream(context, uri, type, batchDir, imageCount)
                if (copied == null) {
                    skipped++
                } else {
                    items.add(copied.copy(index = items.size))
                    imageCount++
                }
            }
        }

        if (rawText.isNotEmpty()) {
            items.add(
                Item(
                    index = items.size,
                    kind = "text",
                    text = rawText,
                    path = null,
                    mime = "text/plain",
                    name = null,
                    size = rawText.toByteArray(Charsets.UTF_8).size.toLong(),
                ),
            )
        }

        if (items.isEmpty() && skipped == 0) {
            batchDir.deleteRecursively()
            return false
        }

        root.listFiles()?.forEach { if (it.name != batchId) it.deleteRecursively() }
        intent.putExtra(HANDLED, true)
        pending.value = Batch(id = batchId, items = items, skipped = skipped)
        openChats(context)
        return true
    }

    /** Місце з мапи йде в той самий аркуш «Надіслати в розмову»: там і групи, і «Збережене». */
    fun offerPlace(context: Context, lat: Double, lon: Double, name: String): Boolean {
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return false
        clear(context)
        val label = name.trim().take(MAX_TEXT).ifEmpty { null }
        pending.value = Batch(
            id = UUID.randomUUID().toString(),
            items = listOf(Item(index = 0, kind = "place", text = label, path = null, mime = null, name = null, size = 0, lat = lat, lon = lon)),
            skipped = 0,
        )
        openChats(context)
        return true
    }

    private fun openChats(context: Context) {
        runCatching {
            (context.applicationContext as PhantomApp).container.homeTabRequest.value = HomeTab.CHATS
        }
        WebNav.request(WebNav.Door.ShareIn)
    }

    fun clear(context: Context) {
        runCatching { File(context.cacheDir, "share-in").deleteRecursively() }
        pending.value = null
    }

    private fun singleStream(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }

    private fun multipleStreams(intent: Intent): List<Uri> =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: emptyList()
        }

    private fun isForeignContent(context: Context, uri: Uri): Boolean {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return false
        val authority = uri.authority?.takeIf { it.isNotEmpty() } ?: return false
        val own = context.packageName
        if (authority == own || authority.startsWith("$own.")) return false
        val owner = runCatching { context.packageManager.resolveContentProvider(authority, 0)?.packageName }.getOrNull()
        return owner != own
    }

    private fun copyStream(
        context: Context,
        uri: Uri,
        fallbackMime: String,
        batchDir: File,
        slot: Int,
    ): Item? {
        if (!isForeignContent(context, uri)) return null
        val resolver = context.contentResolver
        val resolvedMime = resolver.getType(uri)?.takeIf { it.startsWith("image/") }
            ?: fallbackMime.takeIf { it != "image/*" }
            ?: "image/jpeg"
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(resolvedMime)?.takeIf { SAFE_EXT.matches(it) }
            ?: uri.lastPathSegment?.substringAfterLast('.', "")?.takeIf { SAFE_EXT.matches(it) }
            ?: "jpg"
        val fileName = "$slot.$ext"
        val target = File(batchDir, fileName)
        return runCatching {
            val stream = resolver.openInputStream(uri) ?: return null
            var total = 0L
            stream.use { input ->
                FileOutputStream(target).use { output ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        total += read
                        if (total > MAX_IMAGE_BYTES) {
                            target.delete()
                            return null
                        }
                        output.write(buf, 0, read)
                    }
                }
            }
            if (total <= 0L) {
                target.delete()
                return null
            }
            Item(
                index = slot,
                kind = "image",
                text = null,
                path = target.absolutePath,
                mime = resolvedMime,
                name = fileName,
                size = total,
            )
        }.getOrElse {
            target.delete()
            null
        }
    }
}
