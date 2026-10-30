package local.phantom.companion.feature.stream.ui.scan

import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatReader
import com.google.zxing.qrcode.QRCodeWriter
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QrFrameTest {

    @Test
    fun `luma reads a short last row and honours rowStride and pixelStride`() {
        val raw = byteArrayOf(1, 2, 3, 4, 0, 0, 5, 6, 7, 8)
        val out = QrFrame.luma(ByteBuffer.wrap(raw), width = 4, height = 2, rowStride = 6, pixelStride = 1)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), out)

        val interleaved = byteArrayOf(10, 99, 20, 99, 30, 99, 40, 99)
        val sub = QrFrame.luma(ByteBuffer.wrap(interleaved), width = 2, height = 2, rowStride = 4, pixelStride = 2)
        assertArrayEquals(byteArrayOf(10, 20, 30, 40), sub)
    }

    @Test
    fun `decode round-trips a padded QR frame`() {
        val text = "PH2:abc:123456"
        val side = 240
        val stride = 256
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, side, side)
        val padded = ByteArray(stride * side) { 0xFF.toByte() }
        for (y in 0 until side) {
            for (x in 0 until side) {
                padded[y * stride + x] = if (matrix.get(x, y)) 0 else 0xFF.toByte()
            }
        }
        val luma = QrFrame.luma(ByteBuffer.wrap(padded), width = side, height = side, rowStride = stride, pixelStride = 1)
        assertEquals(text, QrFrame.decode(luma, side, side, MultiFormatReader()))
    }

    @Test
    fun `ScanLatch stays open on false, shuts on true, and re-arms on reset`() {
        val latch = ScanLatch()
        var calls = 0
        assertFalse(latch.offer("noise") { calls++; false })
        assertFalse(latch.closed)
        assertTrue(latch.offer("hit") { calls++; true })
        assertTrue(latch.closed)
        assertFalse(latch.offer("after") { calls++; true })
        assertEquals(2, calls)

        latch.reset()
        assertFalse(latch.closed)
        assertTrue(latch.offer("again") { calls++; true })
        assertEquals(3, calls)
    }

    @Test
    fun `CameraTurn tracks the latest holder`() {
        val a = CameraTurn.take()
        val b = CameraTurn.take()
        assertFalse(CameraTurn.isLatest(a))
        assertTrue(CameraTurn.isLatest(b))
    }
}
