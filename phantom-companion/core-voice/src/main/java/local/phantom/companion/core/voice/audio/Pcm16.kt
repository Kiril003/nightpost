package local.phantom.companion.core.voice.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

object Pcm16 {

    private const val TARGET_RATE = 16_000

    fun toMono16k(pcm: ByteArray, rate: Int, channels: Int): ByteArray {
        if (rate <= 0 || channels <= 0) return ByteArray(0)
        if (rate == TARGET_RATE && channels == 1) return pcm.copyOf()
        val totalSamples = pcm.size / 2
        val inFrames = totalSamples / channels
        if (inFrames <= 0) return ByteArray(0)

        val src = ByteBuffer.wrap(pcm, 0, inFrames * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        val mono = ShortArray(inFrames)
        if (channels == 1) {
            for (i in 0 until inFrames) {
                mono[i] = src.getShort()
            }
        } else {
            for (i in 0 until inFrames) {
                var sum = 0
                for (ch in 0 until channels) {
                    sum += src.getShort().toInt()
                }
                mono[i] = (sum / channels).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        }

        if (rate == TARGET_RATE) {
            val out = ByteBuffer.allocate(inFrames * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (s in mono) out.putShort(s)
            return out.array()
        }

        val outFrames = ((inFrames.toLong() * TARGET_RATE) / rate).toInt()
        if (outFrames <= 0) return ByteArray(0)
        val out = ByteBuffer.allocate(outFrames * 2).order(ByteOrder.LITTLE_ENDIAN)
        val ratio = rate.toDouble() / TARGET_RATE.toDouble()
        val last = inFrames - 1
        for (i in 0 until outFrames) {
            val pos = i * ratio
            val idx = pos.toInt().coerceIn(0, last)
            val next = (idx + 1).coerceAtMost(last)
            val frac = pos - idx
            val a = mono[idx].toInt()
            val b = mono[next].toInt()
            val v = (a + (b - a) * frac).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out.putShort(v.toShort())
        }
        return out.array()
    }
}
