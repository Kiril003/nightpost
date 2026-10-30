package local.phantom.companion.core.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Native Android AudioRecord wrapper for PCM-16 16kHz Mono.
 * Provides a hot flow of audio chunks.
 *
 * ## OMNIPRESENT VOICE — why the audio source changed
 *
 * This used to open [MediaRecorder.AudioSource.MIC]: the raw, unprocessed
 * capture path. That is the correct choice for a half-duplex push-to-talk
 * recorder — nothing else is making noise while the operator holds the
 * button — and it is *fatal* for full duplex.
 *
 * Full duplex means the mic is open while PHANTOM's own voice is coming out
 * of the loudspeaker a few centimetres away. On the raw MIC path, that voice
 * comes straight back in: the VAD sees speech, concludes the operator is
 * talking, and interrupts the TTS — which is PHANTOM interrupting himself,
 * on a loop, forever. There is no threshold that fixes this, because his own
 * voice is by far the loudest thing in the room.
 *
 * [MediaRecorder.AudioSource.VOICE_COMMUNICATION] is the path Android
 * reserves for exactly this situation (it is what every voice-call and
 * conferencing app uses). It gives the platform a chance to attach hardware
 * echo cancellation, and it lets us attach [AcousticEchoCanceler] on top,
 * which cancels the *far-end* signal — our own playback — out of the capture
 * stream by referencing what the device is rendering.
 *
 * ## Але це не єдиний запис у застосунку
 *
 * Той самий тракт телефонії потім рік стояв і під натисни-і-говори, де
 * придушувати нема чого, — а його AGC ламає оцінку каналу, на якій тримається
 * акустична модель. Тому джерело більше не константа: його обирає
 * [CapturePolicy], і там же написано, чому.
 *
 * ## Гарантій немає
 *
 * AEC availability is per-device and the platform is allowed to simply not
 * have it. So [echoCancelled] reports what we actually got, rather than what
 * we asked for, and the barge-in logic downstream
 * ([local.phantom.companion.core.voice.duplex.BargeInGate]) tightens itself
 * when the answer is false. A device without AEC still gets duplex — it just
 * demands louder, more sustained speech to believe an interruption is real,
 * because it is grading the mic against our own known output envelope
 * instead of trusting the hardware to have removed it.
 *
 * We never silently degrade to a self-interrupting loop, and we never claim
 * cancellation we did not get.
 */
class AudioRecorder {

    companion object {
        const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private val MIN_BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        private const val TAG = "AudioRecorder"
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false

    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null

    private val _audioFlow = MutableSharedFlow<ByteArray>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val audioFlow: SharedFlow<ByteArray> = _audioFlow.asSharedFlow()

    private val _levelFlow = MutableStateFlow(0f)
    val levelFlow: StateFlow<Float> = _levelFlow.asStateFlow()

    private val _echoCancelled = MutableStateFlow(false)

    /**
     * True only when an [AcousticEchoCanceler] is genuinely attached and
     * enabled on the live capture session. False on devices that don't
     * support it, and false whenever the mic is closed.
     */
    val echoCancelled: StateFlow<Boolean> = _echoCancelled.asStateFlow()

    @SuppressLint("MissingPermission")
    suspend fun startRecording(duplex: Boolean = false) = withContext(Dispatchers.IO) {
        if (isRecording) return@withContext

        val bufferSize = (MIN_BUFFER_SIZE * 2).coerceAtLeast(SAMPLE_RATE / 10) // ~100ms chunks

        // Порядок джерел і те, чому він саме такий, живе в [CapturePolicy].
        // Якщо пристрій відмовляє в усіх (деякі OEM таки відмовляють),
        // лишається MIC: напівдуплексний мікрофон незрівнянно кращий за
        // жоден, а дуплексному шару правду скаже [echoCancelled].
        val sources = CapturePolicy.sources(duplex)
        val record = sources.firstNotNullOfOrNull { openRecord(androidSource(it), bufferSize) }

        if (record == null) {
            Log.e(TAG, "AudioRecord failed to initialise on any of $sources")
            audioRecord = null
            return@withContext
        }
        audioRecord = record
        attachEffects(record, duplex)

        record.startRecording()
        isRecording = true

        val buffer = ByteArray(bufferSize)
        while (isRecording) {
            val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
            if (read > 0) {
                val chunk = buffer.copyOf(read)
                _audioFlow.emit(chunk)
                calculateLevel(chunk)
            } else if (read < 0) {
                isRecording = false
            }
        }
    }

    private fun androidSource(source: CaptureSource): Int = when (source) {
        CaptureSource.VoiceRecognition -> MediaRecorder.AudioSource.VOICE_RECOGNITION
        CaptureSource.VoiceCommunication -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
        CaptureSource.Mic -> MediaRecorder.AudioSource.MIC
    }

    @SuppressLint("MissingPermission")
    private fun openRecord(source: Int, bufferSize: Int): AudioRecord? = runCatching {
        val record = AudioRecord(source, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize)
        if (record.state == AudioRecord.STATE_INITIALIZED) {
            record
        } else {
            // A record that built but failed to initialise still holds a
            // native handle; release it so it does not block the next attempt.
            Log.w(TAG, "AudioRecord(source=$source) did not initialise")
            record.release()
            null
        }
    }.getOrElse {
        Log.w(TAG, "AudioRecord(source=$source) threw", it)
        null
    }

    /**
     * Attach echo cancellation (load-bearing for duplex) and noise
     * suppression to the live session — both only where [CapturePolicy] says
     * they earn their place. Both are best-effort even then:
     * [AcousticEchoCanceler.isAvailable] is a per-device fact we do not get
     * to argue with.
     */
    private fun attachEffects(record: AudioRecord, duplex: Boolean) {
        val sessionId = record.audioSessionId

        echoCanceler = if (CapturePolicy.cancelEcho(duplex) && AcousticEchoCanceler.isAvailable()) {
            runCatching {
                AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
            }.getOrElse {
                Log.w(TAG, "AcousticEchoCanceler.create failed", it); null
            }
        } else {
            if (duplex) {
                Log.i(TAG, "AcousticEchoCanceler unavailable on this device — duplex will run in strict barge-in mode")
            }
            null
        }

        // Trust `enabled`, not `isAvailable()`: a canceller can be created and
        // then refuse to enable, and the duplex layer must not be told it has
        // protection it does not have.
        _echoCancelled.value = echoCanceler?.enabled == true

        noiseSuppressor = if (CapturePolicy.suppressNoise(duplex) && NoiseSuppressor.isAvailable()) {
            runCatching {
                NoiseSuppressor.create(sessionId)?.apply { enabled = true }
            }.getOrElse { null }
        } else {
            null
        }

        Log.i(
            TAG,
            "capture up · echoCancelled=${_echoCancelled.value} · noiseSuppressed=${noiseSuppressor?.enabled == true}",
        )
    }

    private fun calculateLevel(chunk: ByteArray) {
        var sum = 0.0
        for (i in 0 until chunk.size / 2) {
            val low = chunk[i * 2].toInt() and 0xFF
            val high = chunk[i * 2 + 1].toInt()
            val sample = ((high shl 8) or low).toShort()
            sum += sample * sample
        }
        val rms = kotlin.math.sqrt(sum / (chunk.size / 2))
        // Normalize to 0..1 range (rough approximation for 16-bit PCM)
        val normalized = (rms / 32768.0).coerceIn(0.0, 1.0).toFloat()
        _levelFlow.value = normalized
        // MIC-1 — also publish to the process-global hub so passive
        // consumers (orb, SystemPulse VOICE dot, sensor MicRmsSource)
        // can read live amplitude without opening their own AudioRecord.
        AudioLevelHub.publish(normalized)
    }

    fun stopRecording() {
        isRecording = false
        _levelFlow.value = 0f
        // MIC-1 — clear the hub so the system mic indicator and every
        // passive consumer reflect "mic is no longer live" within one
        // sensor-batch tick.
        AudioLevelHub.reset()

        runCatching { echoCanceler?.release() }
        runCatching { noiseSuppressor?.release() }
        echoCanceler = null
        noiseSuppressor = null
        _echoCancelled.value = false

        audioRecord?.apply {
            if (state == AudioRecord.STATE_INITIALIZED && recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                stop()
            }
            release()
        }
        audioRecord = null
    }
}
