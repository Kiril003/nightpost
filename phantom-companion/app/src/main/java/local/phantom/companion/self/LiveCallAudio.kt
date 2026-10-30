package local.phantom.companion.self

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import local.phantom.companion.core.voice.live.CallSpeaker
import local.phantom.companion.core.voice.live.LiveProtocol
import local.phantom.companion.core.voice.live.SpeechEdge

/**
 * Звук живого дзвінка.
 *
 * Мікрофон — 16 кГц з джерела `VOICE_COMMUNICATION`: це вмикає платформне
 * придушення луни (AEC) і шуму, тож PHANTOM не чує сам себе з динаміка і
 * людина може перебити його голосом. Динамік — 24 кГц як
 * `USAGE_VOICE_COMMUNICATION`: саме від цього потоку AEC і віднімає луну.
 * Режим `MODE_IN_COMMUNICATION` на час дзвінка, після — повертається як був.
 */
class LiveCallAudio(context: Context) : CallSpeaker {

    private val am = context.getSystemService(AudioManager::class.java)
    private var record: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var micThread: Thread? = null

    @Volatile private var micOn = false
    private var track: AudioTrack? = null
    private val queue = LinkedBlockingQueue<ByteArray>()
    private var playThread: Thread? = null

    @Volatile private var playing = false
    private var savedMode = AudioManager.MODE_NORMAL
    private var opened = false

    private val _level = MutableStateFlow(0f)

    /** Рівень голосу PHANTOM 0..1 — кільце аватара дихає, коли він говорить. */
    val level: StateFlow<Float> = _level.asStateFlow()

    /** Чи справді увімкнулось платформне придушення луни (є не на кожному апараті). */
    @Volatile var echoCancelled = false
        private set

    fun open(speakerOn: Boolean) {
        if (opened) return
        opened = true
        am?.let {
            savedMode = it.mode
            it.mode = AudioManager.MODE_IN_COMMUNICATION
        }
        route(speakerOn)
        val minBuf = AudioTrack.getMinBufferSize(
            LiveProtocol.OUT_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val t = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(LiveProtocol.OUT_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBuf, LiveProtocol.OUT_RATE * 2 / 10))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        }.getOrElse {
            Log.w(TAG, "динамік дзвінка не відкрився", it)
            return
        }
        track = t
        t.play()
        playing = true
        playThread = thread(name = "phantom-call-out", isDaemon = true) {
            while (playing) {
                val chunk = queue.poll(50, TimeUnit.MILLISECONDS)
                if (chunk == null) {
                    _level.value = 0f
                    continue
                }
                _level.value = (SpeechEdge.rms(chunk, chunk.size) / 6000.0).coerceIn(0.0, 1.0).toFloat()
                runCatching { t.write(chunk, 0, chunk.size) }
            }
        }
    }

    /** RECORD_AUDIO питає екран дзвінка до старту; без дозволу AudioRecord просто не підніметься. */
    @SuppressLint("MissingPermission")
    fun startMic(onPcm: (ByteArray, Int) -> Unit): Boolean {
        if (micOn) return true
        val min = AudioRecord.getMinBufferSize(
            LiveProtocol.IN_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val rec = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                LiveProtocol.IN_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(min, FRAME_BYTES * 4),
            )
        }.getOrNull() ?: return false
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }
        if (AcousticEchoCanceler.isAvailable()) {
            aec = runCatching { AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled = true } }.getOrNull()
        }
        if (NoiseSuppressor.isAvailable()) {
            ns = runCatching { NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true } }.getOrNull()
        }
        echoCancelled = aec?.enabled == true
        runCatching { rec.startRecording() }.onFailure {
            rec.release()
            return false
        }
        record = rec
        micOn = true
        micThread = thread(name = "phantom-call-in", isDaemon = true) {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buf = ByteArray(FRAME_BYTES)
            while (micOn) {
                val n = rec.read(buf, 0, FRAME_BYTES)
                if (n > 0) onPcm(buf, n)
            }
        }
        return true
    }

    override fun play(pcm24k: ByteArray) {
        if (playing) queue.offer(pcm24k)
    }

    override fun flush() {
        queue.clear()
        track?.let { t ->
            runCatching {
                t.pause()
                t.flush()
                t.play()
            }
        }
        _level.value = 0f
    }

    override fun duck(on: Boolean) {
        runCatching { track?.setVolume(if (on) DUCK else 1f) }
    }

    fun route(speakerOn: Boolean) {
        val m = am ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (speakerOn) {
                    m.availableCommunicationDevices
                        .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                        ?.let { m.setCommunicationDevice(it) }
                } else {
                    m.clearCommunicationDevice()
                }
            } else {
                @Suppress("DEPRECATION")
                m.isSpeakerphoneOn = speakerOn
            }
        }
    }

    fun stopMic() {
        micOn = false
        micThread?.join(300)
        micThread = null
        record?.let { r ->
            runCatching { r.stop() }
            r.release()
        }
        record = null
        aec?.release()
        aec = null
        ns?.release()
        ns = null
    }

    fun close() {
        stopMic()
        playing = false
        playThread?.join(300)
        playThread = null
        queue.clear()
        track?.let { t ->
            runCatching { t.stop() }
            t.release()
        }
        track = null
        if (opened) {
            am?.let { m ->
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        m.clearCommunicationDevice()
                    } else {
                        @Suppress("DEPRECATION")
                        m.isSpeakerphoneOn = false
                    }
                    m.mode = savedMode
                }
            }
        }
        opened = false
        _level.value = 0f
    }

    companion object {
        private const val TAG = "LiveCallAudio"

        /** 20 мс при 16 кГц, 16 біт. */
        const val FRAME_BYTES = LiveProtocol.IN_RATE / 50 * 2
        private const val DUCK = 0.25f
    }
}
