package local.phantom.companion.feature.stream.ui.scan

import androidx.camera.core.ImageProxy
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Розбір кадру YUV у рядок QR без Android-залежностей. */
internal object QrFrame {

    fun luma(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
    ): ByteArray {
        val dup = buffer.duplicate().apply { rewind() }
        if (rowStride == width && pixelStride == 1 && dup.remaining() == width * height) {
            return ByteArray(width * height).also { dup.get(it) }
        }
        val out = ByteArray(width * height)
        val row = ByteArray(rowStride)
        var offset = 0
        for (y in 0 until height) {
            val n = minOf(rowStride, dup.remaining())
            if (n <= 0) break
            dup.get(row, 0, n)
            var x = 0
            var i = 0
            while (x < width && i < n) {
                out[offset + x] = row[i]
                x += 1
                i += pixelStride
            }
            offset += width
        }
        return out
    }

    fun decode(luma: ByteArray, width: Int, height: Int, reader: MultiFormatReader): String? {
        val source = PlanarYUVLuminanceSource(luma, width, height, 0, 0, width, height, false)
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        return (runCatching { reader.decodeWithState(bitmap).text }.getOrNull()
            ?: runCatching {
                reader.reset()
                reader.decode(bitmap).text
            }.getOrNull()).also { reader.reset() }
    }

    fun read(proxy: ImageProxy, reader: MultiFormatReader): String? {
        val plane = proxy.planes.firstOrNull() ?: return null
        val bytes = luma(plane.buffer, proxy.width, proxy.height, plane.rowStride, plane.pixelStride)
        return decode(bytes, proxy.width, proxy.height, reader)
    }
}

/** Одноразовий засув кадру, який скидається без перевʼязування камери. */
internal class ScanLatch {
    private val shut = AtomicBoolean(false)
    val closed: Boolean get() = shut.get()

    fun offer(text: String, take: (String) -> Boolean): Boolean {
        if (shut.get()) return false
        val ok = take(text)
        if (ok) shut.set(true)
        return ok
    }

    fun reset() {
        shut.set(false)
    }
}

/** Черга володіння камерою між екранами під час переходу. */
internal object CameraTurn {
    private val last = AtomicInteger(0)
    fun take(): Int = last.incrementAndGet()
    fun isLatest(turn: Int): Boolean = last.get() == turn
}
