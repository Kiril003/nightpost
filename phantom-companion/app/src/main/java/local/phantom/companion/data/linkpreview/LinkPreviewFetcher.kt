package local.phantom.companion.data.linkpreview

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import local.phantom.companion.core.net.peer.PeerPreview

/**
 * Читає сторінку за посиланням ВІДПРАВНИКА: лише https, без куків, з короткими
 * строками й межею байтів. Мініатюра — JPEG до [THUMB_PX] по більшій стороні,
 * не більша за [PeerPreview.MAX_IMAGE_BYTES]; не вміщається — картка без неї.
 */
object LinkPreviewFetcher {

    private const val TIMEOUT_MS = 5_000
    private const val MAX_REDIRECTS = 3
    private const val MAX_IMAGE_SOURCE_BYTES = 2 * 1024 * 1024
    private const val THUMB_PX = 240
    private const val AGENT = "Mozilla/5.0 (Linux; Android) PhantomLinkPreview"

    fun fetch(url: String): PeerPreview.Preview? {
        val (finalUrl, html) = get(url, PeerPreview.MAX_HTML_BYTES, wantHtml = true) ?: return null
        val meta = PeerPreview.parse(String(html, Charsets.UTF_8), finalUrl) ?: return null
        val image = meta.imageUrl?.let { thumb(it) }
        return PeerPreview.Preview(url = url, title = meta.title, description = meta.description, site = meta.site, image = image)
    }

    private fun thumb(url: String): ByteArray? {
        val (_, bytes) = get(url, MAX_IMAGE_SOURCE_BYTES, wantHtml = false) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= THUMB_PX) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val scale = THUMB_PX.toFloat() / maxOf(decoded.width, decoded.height)
        val bmp = if (scale < 1f) {
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1), true)
        } else {
            decoded
        }
        for (quality in intArrayOf(70, 55, 40)) {
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (out.size() <= PeerPreview.MAX_IMAGE_BYTES) return out.toByteArray()
        }
        return null
    }

    private fun get(start: String, limit: Int, wantHtml: Boolean): Pair<String, ByteArray>? {
        var url = start
        repeat(MAX_REDIRECTS + 1) {
            if (!url.startsWith("https://", ignoreCase = true)) return null
            val c = (URL(url).openConnection() as? HttpsURLConnection) ?: return null
            try {
                c.instanceFollowRedirects = false
                c.connectTimeout = TIMEOUT_MS
                c.readTimeout = TIMEOUT_MS
                c.useCaches = false
                c.setRequestProperty("User-Agent", AGENT)
                c.setRequestProperty("Accept", if (wantHtml) "text/html" else "image/*")
                val code = c.responseCode
                if (code in 300..399) {
                    url = c.getHeaderField("Location")?.let { URL(URL(url), it).toString() } ?: return null
                    return@repeat
                }
                if (code != HttpURLConnection.HTTP_OK) return null
                val type = c.contentType.orEmpty().lowercase()
                if (wantHtml && !type.contains("html")) return null
                if (!wantHtml && !type.startsWith("image/")) return null
                return url to c.inputStream.use { read(it, limit) }
            } finally {
                c.disconnect()
            }
        }
        return null
    }

    private fun read(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        while (out.size() < limit) {
            val n = input.read(buf, 0, minOf(buf.size, limit - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
