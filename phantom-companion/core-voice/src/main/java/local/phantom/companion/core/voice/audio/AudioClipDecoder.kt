package local.phantom.companion.core.voice.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

object AudioClipDecoder {

    private const val TIMEOUT_US = 10_000L
    private const val MAX_CLIP_SECONDS = 60L
    private const val DEADLINE_MS = 8_000L

    internal fun rawByteBudget(rate: Int, channels: Int): Long =
        MAX_CLIP_SECONDS * rate.coerceAtLeast(1) * channels.coerceAtLeast(1) * 2

    suspend fun decode(bytes: ByteArray, mime: String): ByteArray? = withContext(Dispatchers.Default) {
        if (bytes.isEmpty()) return@withContext null
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(object : MediaDataSource() {
                override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                    if (position < 0 || position >= bytes.size) return -1
                    val available = (bytes.size - position.toInt()).coerceAtMost(size)
                    if (available <= 0) return -1
                    System.arraycopy(bytes, position.toInt(), buffer, offset, available)
                    return available
                }

                override fun getSize(): Long = bytes.size.toLong()

                override fun close() {}
            })

            var trackIndex = -1
            var format: MediaFormat? = null
            var trackMime: String? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val m = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (m.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    trackMime = m
                    break
                }
            }
            if (trackIndex < 0 || format == null || trackMime == null) return@withContext null

            extractor.selectTrack(trackIndex)
            var sampleRate = runCatching { format.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrDefault(16_000)
            var channels = runCatching { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(1)

            codec = MediaCodec.createDecoderByType(trackMime)
            codec.configure(format, null, null, 0)
            codec.start()

            val out = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            val deadline = System.nanoTime() + DEADLINE_MS * 1_000_000L

            while (!outputDone) {
                ensureActive()
                if (System.nanoTime() > deadline) return@withContext null
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)
                        if (inBuf == null) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val read = extractor.readSampleData(inBuf, 0)
                            if (read < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                val pts = extractor.sampleTime.coerceAtLeast(0L)
                                codec.queueInputBuffer(inIdx, 0, read, pts, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = codec.outputFormat
                        sampleRate = runCatching { of.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrDefault(sampleRate)
                        channels = runCatching { of.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(channels)
                        val enc = runCatching { of.getInteger(MediaFormat.KEY_PCM_ENCODING) }
                            .getOrDefault(AudioFormat.ENCODING_PCM_16BIT)
                        if (enc != AudioFormat.ENCODING_PCM_16BIT) return@withContext null
                    }
                    outIdx >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && info.size > 0) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            val chunk = ByteArray(info.size)
                            outBuf.get(chunk)
                            out.write(chunk)
                            if (out.size() > rawByteBudget(sampleRate, channels)) return@withContext null
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                }
            }

            val raw = out.toByteArray()
            if (raw.isEmpty()) return@withContext null
            val mono16k = Pcm16.toMono16k(raw, sampleRate, channels)
            mono16k.takeIf { it.isNotEmpty() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor?.release() }
        }
    }
}
