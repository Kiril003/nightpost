package local.phantom.companion.core.voice.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Pcm16Test {

    @Test
    fun `stereo 48k becomes mono 16k with a third of the frames`() {
        val frames48k = 60
        val buf = ByteBuffer.allocate(frames48k * 2 * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames48k) {
            val l = (i * 100).toShort()
            val r = (i * 300).toShort()
            buf.putShort(l)
            buf.putShort(r)
        }
        val out = Pcm16.toMono16k(buf.array(), rate = 48_000, channels = 2)
        assertEquals(20 * 2, out.size)
        val outBuf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0, outBuf.getShort(0).toInt())
        assertEquals(600, outBuf.getShort(2).toInt())
    }

    @Test
    fun `mono 16k passes through unchanged`() {
        val samples = shortArrayOf(0, 1200, -1200, 32000, -32000, 42)
        val buf = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { buf.putShort(it) }
        val raw = buf.array()
        val out = Pcm16.toMono16k(raw, rate = 16_000, channels = 1)
        assertArrayEquals(raw, out)
    }
}
