package local.phantom.companion.ui.messenger.preview

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Base64
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt
import local.phantom.companion.ui.messenger.WebMessengerAssets

fun interface PdfProbe {
    @Throws(IOException::class, SecurityException::class)
    fun pageCount(file: File): Int
}

fun interface HeicDecoder {
    @Throws(IOException::class)
    fun decodeToJpeg(sourceFile: File, outFile: File, maxLongSide: Int, quality: Int): Boolean
}

object AndroidPdfProbe : PdfProbe {
    override fun pageCount(file: File): Int {
        val envelopePages = checkPdfEnvelope(file)
        return try {
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                ?: return envelopePages
            pfd.use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    val count = renderer.pageCount
                    if (count > 0) count else envelopePages
                }
            }
        } catch (e: SecurityException) {
            throw e
        } catch (e: IOException) {
            throw e
        }
    }
}

object AndroidHeicDecoder : HeicDecoder {
    override fun decodeToJpeg(sourceFile: File, outFile: File, maxLongSide: Int, quality: Int): Boolean {
        if (!looksLikeHeic(sourceFile)) return false
        return try {
            val source = ImageDecoder.createSource(sourceFile) ?: return false
            val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                if (heicTooLarge(info.size.width, info.size.height)) throw IOException("heic too large")
                val (targetW, targetH) = targetSize(info.size.width, info.size.height, maxLongSide)
                decoder.setTargetSize(targetW, targetH)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            } ?: return false
            try {
                val ok = FileOutputStream(outFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                }
                ok && outFile.isFile && outFile.length() > 0L
            } finally {
                bitmap.recycle()
            }
        } catch (e: IOException) {
            outFile.delete()
            false
        } catch (e: SecurityException) {
            outFile.delete()
            false
        } catch (_: Exception) {
            outFile.delete()
            false
        }
    }
}

internal fun heicTooLarge(width: Int, height: Int): Boolean =
    width <= 0 || height <= 0 || width.toLong() * height.toLong() > PreviewStore.MAX_HEIC_PIXELS

internal fun targetSize(width: Int, height: Int, maxLongSide: Int = PreviewStore.MAX_IMAGE_SIDE): Pair<Int, Int> {
    if (width <= 0 || height <= 0) return 1 to 1
    val longSide = maxOf(width, height)
    if (longSide <= maxLongSide) return width to height
    val scale = maxLongSide.toDouble() / longSide.toDouble()
    val w = (width * scale).roundToInt().coerceIn(1, maxLongSide)
    val h = (height * scale).roundToInt().coerceIn(1, maxLongSide)
    return w to h
}

private val ENCRYPT_TOKEN = Regex("""/Encrypt\b""")
private val PAGE_TOKEN = Regex("""/Type\s*/Page\b""")
private val HEIC_BRANDS = setOf(
    "heic", "heix", "hevc", "hevx", "heim", "heis", "hevm", "hevs", "mif1", "msf1", "avif",
)

@Throws(IOException::class, SecurityException::class)
internal fun checkPdfEnvelope(file: File): Int {
    if (!file.isFile || file.length() < 16L) {
        throw IOException("PDF file is empty or too short")
    }
    val headLen = minOf(file.length(), 65_536L).toInt()
    val head = ByteArray(headLen)
    FileInputStream(file).use { input ->
        var off = 0
        while (off < headLen) {
            val n = input.read(head, off, headLen - off)
            if (n <= 0) break
            off += n
        }
    }
    val headText = String(head, Charsets.ISO_8859_1)
    if (!headText.startsWith("%PDF-")) {
        throw IOException("Missing PDF header")
    }
    if (ENCRYPT_TOKEN.containsMatchIn(headText)) {
        throw SecurityException("Encrypted PDF")
    }
    val tailLen = minOf(file.length(), 4096L).toInt()
    val tail = ByteArray(tailLen)
    RandomAccessFile(file, "r").use { raf ->
        raf.seek(file.length() - tailLen)
        raf.readFully(tail)
    }
    val tailText = String(tail, Charsets.ISO_8859_1)
    if (ENCRYPT_TOKEN.containsMatchIn(tailText)) {
        throw SecurityException("Encrypted PDF")
    }
    if (!tailText.contains("%%EOF")) {
        throw IOException("Truncated PDF: missing EOF marker")
    }
    val pageMatches = PAGE_TOKEN.findAll(headText).count()
    if (file.length() <= headLen && pageMatches == 0) {
        throw IOException("PDF contains no pages")
    }
    return pageMatches.coerceAtLeast(1)
}

internal fun looksLikeHeic(file: File): Boolean {
    if (!file.isFile || file.length() < 12L) return false
    val header = ByteArray(12)
    val read = try {
        FileInputStream(file).use { it.read(header) }
    } catch (_: IOException) {
        return false
    }
    if (read < 12) return false
    val boxType = String(header, 4, 4, Charsets.ISO_8859_1)
    if (boxType != "ftyp") return false
    val brand = String(header, 8, 4, Charsets.ISO_8859_1).lowercase(Locale.US)
    return brand in HEIC_BRANDS
}

class PreviewStore(
    private val dir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pdfProbe: PdfProbe = AndroidPdfProbe,
    private val heicDecoder: HeicDecoder = AndroidHeicDecoder,
    private val openViewer: (Entry) -> Boolean = { true },
) {
    data class Entry(
        val id: String,
        val kind: String,
        val name: String,
        val mime: String,
        val size: Long,
        var nextSeq: Int = 0,
        var written: Long = 0L,
        var updatedAtMs: Long = 0L,
    )

    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>()

    fun begin(kind: String, name: String, mime: String, size: Long): String = synchronized(lock) {
        sweepLocked()
        if (size <= 0L) return ""
        val cap = when (kind) {
            KIND_PDF -> MAX_PDF_BYTES
            KIND_HEIC -> MAX_HEIC_BYTES
            else -> return ""
        }
        if (size > cap) return ""

        val id = UUID.randomUUID().toString().lowercase(Locale.US)
        if (!ID_REGEX.matches(id)) return ""
        dir.mkdirs()
        val target = safeFile(id) ?: return ""
        val now = clock()
        return try {
            FileOutputStream(target, false).use { }
            target.setLastModified(now.coerceAtLeast(0L))
            entries[id] = Entry(
                id = id,
                kind = kind,
                name = name,
                mime = mime,
                size = size,
                nextSeq = 0,
                written = 0L,
                updatedAtMs = now,
            )
            id
        } catch (_: IOException) {
            ""
        }
    }

    fun chunk(id: String, seq: Int, b64: String): Boolean = synchronized(lock) {
        if (!ID_REGEX.matches(id)) return false
        val entry = entries[id] ?: return false
        if (seq != entry.nextSeq) return false
        if (b64.isEmpty() || b64.length > MAX_B64_CHARS) return false

        val decoded = try {
            Base64.getDecoder().decode(b64.trim())
        } catch (_: IllegalArgumentException) {
            return false
        }
        if (decoded.isEmpty() || decoded.size > MAX_CHUNK_BYTES) return false
        if (entry.written + decoded.size > entry.size) return false

        val target = safeFile(entry.id) ?: return false
        return try {
            dir.mkdirs()
            FileOutputStream(target, true).use { it.write(decoded) }
            val now = clock()
            entry.written += decoded.size
            entry.nextSeq += 1
            entry.updatedAtMs = now
            target.setLastModified(now.coerceAtLeast(0L))
            true
        } catch (_: IOException) {
            false
        }
    }

    fun complete(id: String): Boolean = synchronized(lock) {
        if (!ID_REGEX.matches(id)) return false
        val entry = entries[id] ?: return false
        if (entry.written != entry.size) return false
        val file = safeFile(entry.id) ?: return false
        file.isFile && file.length() == entry.size
    }

    fun open(id: String): Boolean {
        val (entry, file) = synchronized(lock) {
            if (!ID_REGEX.matches(id)) return false
            val e = entries[id] ?: return false
            if (e.written != e.size) return false
            val f = safeFile(e.id) ?: return false
            if (!f.isFile || f.length() != e.size) return false
            val now = clock()
            e.updatedAtMs = now
            f.setLastModified(now.coerceAtLeast(0L))
            e.copy() to f
        }
        val valid = try {
            when (entry.kind) {
                KIND_PDF -> pdfProbe.pageCount(file) > 0
                KIND_HEIC -> synchronized(lock) { ensureHeicJpegLocked(entry.id, file) != null }
                else -> false
            }
        } catch (e: IOException) {
            false
        } catch (e: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
        if (!valid) return false
        return try {
            openViewer(entry)
        } catch (_: Exception) {
            false
        }
    }

    fun imageUrl(id: String): String = synchronized(lock) {
        if (!ID_REGEX.matches(id)) return ""
        val entry = entries[id] ?: return ""
        if (entry.kind != KIND_HEIC) return ""
        if (entry.written != entry.size) return ""
        val file = safeFile(entry.id) ?: return ""
        if (!file.isFile || file.length() != entry.size) return ""
        val jpg = ensureHeicJpegLocked(entry.id, file) ?: return ""
        WebMessengerAssets.MOUNT + "preview/${jpg.name}"
    }

    fun readPreviewJpeg(fileName: String): ByteArray? = synchronized(lock) {
        if (!PREVIEW_JPG_REGEX.matches(fileName)) return null
        val file = safeFile(fileName) ?: return null
        if (!file.isFile) return null
        try {
            file.readBytes().takeIf { it.isNotEmpty() }
        } catch (_: IOException) {
            null
        }
    }

    fun delete(id: String): Boolean = synchronized(lock) {
        if (!ID_REGEX.matches(id)) return false
        entries.remove(id)
        deleteFiles(dir, id)
    }

    fun sweep(): Int = synchronized(lock) { sweepLocked() }

    private fun sweepLocked(): Int {
        if (!dir.isDirectory) return 0
        val now = clock()
        var deleted = 0
        for (file in dir.listFiles().orEmpty()) {
            val entryId = file.name.removeSuffix(".jpg")
            val entry = entries[entryId]
            val lm = file.lastModified()
            val touchedAt = when {
                entry != null && abs(lm - entry.updatedAtMs) < 2_000L -> entry.updatedAtMs
                entry != null -> minOf(entry.updatedAtMs, lm)
                else -> lm
            }
            if (now - touchedAt > MAX_AGE_MS) {
                if (file.delete()) deleted++
                entries.remove(entryId)
            }
        }
        val expired = entries.values.filter { now - it.updatedAtMs > MAX_AGE_MS }.map { it.id }
        for (id in expired) {
            entries.remove(id)
        }
        return deleted
    }

    private fun ensureHeicJpegLocked(id: String, sourceFile: File): File? {
        if (!ID_REGEX.matches(id)) return null
        val jpgFile = safeFile("$id.jpg") ?: return null
        if (jpgFile.isFile && jpgFile.length() > 0L) return jpgFile
        return try {
            if (heicDecoder.decodeToJpeg(sourceFile, jpgFile, MAX_IMAGE_SIDE, JPEG_QUALITY) &&
                jpgFile.isFile && jpgFile.length() > 0L
            ) {
                val now = clock()
                jpgFile.setLastModified(now.coerceAtLeast(0L))
                jpgFile
            } else {
                jpgFile.delete()
                null
            }
        } catch (e: IOException) {
            jpgFile.delete()
            null
        } catch (e: SecurityException) {
            jpgFile.delete()
            null
        } catch (_: Exception) {
            jpgFile.delete()
            null
        }
    }

    internal fun safeFile(name: String): File? {
        if (!ID_REGEX.matches(name) && !PREVIEW_JPG_REGEX.matches(name)) return null
        if (name.contains('/') || name.contains('\\') || name.contains("..")) return null
        return try {
            val base = dir.canonicalFile
            val candidate = File(base, name).canonicalFile
            if (candidate.parentFile == base) candidate else null
        } catch (_: IOException) {
            null
        }
    }

    companion object {
        const val KIND_PDF = "pdf"
        const val KIND_HEIC = "heic"
        const val MAX_PDF_BYTES = 200L * 1024L * 1024L
        const val MAX_HEIC_BYTES = 50L * 1024L * 1024L
        const val MAX_CHUNK_BYTES = 1024 * 1024
        private const val MAX_B64_CHARS = (MAX_CHUNK_BYTES / 3 + 4) * 4 + 64
        const val MAX_AGE_MS = 10 * 60_000L
        const val MAX_IMAGE_SIDE = 2048
        const val MAX_HEIC_PIXELS = 100_000_000L
        const val JPEG_QUALITY = 85

        val ID_REGEX = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        val PREVIEW_JPG_REGEX = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.jpg$")

        fun deleteFiles(dir: File, id: String): Boolean {
            if (!ID_REGEX.matches(id)) return false
            return try {
                val base = dir.canonicalFile
                val raw = File(base, id).canonicalFile
                val jpg = File(base, "$id.jpg").canonicalFile
                var removed = false
                if (raw.parentFile == base && raw.exists()) removed = raw.delete() || removed
                if (jpg.parentFile == base && jpg.exists()) removed = jpg.delete() || removed
                removed
            } catch (_: IOException) {
                false
            }
        }
    }
}
