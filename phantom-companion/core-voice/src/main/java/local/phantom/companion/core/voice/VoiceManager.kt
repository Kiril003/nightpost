package local.phantom.companion.core.voice

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.vosk.Model
import kotlinx.coroutines.delay
import java.io.File

/**
 * QH3 — operator-facing label for the STT pipeline currently active.
 *
 * - [Hybrid]: Vosk live partials + Moonshine final accuracy pass.
 * - [VoskOnly]: only Vosk loaded (no Moonshine files present).
 * - [MoonshineOnly]: only Moonshine loaded (Vosk path missing/broken).
 * - [None]: neither real engine loaded — using FakeSttEngine which
 *   returns canned answers; operator hears no actual transcription.
 *
 * Settings → STT reads [VoiceManager.activeEngine] so a freshly
 * downloaded Moonshine / Vosk model flips the «слух: ...» indicator
 * the moment refreshModels picks the file up.
 */
enum class SttRouteKind { Hybrid, VoskOnly, MoonshineOnly, None }

/**
 * High-level manager for voice operations (STT/TTS coordination).
 * Phase 2-A: STT implementation.
 */
open class VoiceManager(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    /**
     * Fired the moment the mic is opened by any surface (PTT, live-dialogue,
     * Stream, Settings). The single funnel every capture passes through, so
     * the container can lazily self-provision the STT model here — the hearing
     * twin of AiBootstrapper.ensureBrain. Non-blocking; default no-op keeps
     * existing constructions and test fakes valid.
     */
    private val onCaptureStart: () -> Unit = {},
) : VoiceController {
    private val recorder = AudioRecorder()
    private var hybridEngine: HybridSttEngine? = null
    private var classifier: AudioClassifier? = null
    private var recordingJob: Job? = null
    private var classifierJob: Job? = null
    private var sttPartialJob: Job? = null
    private var sttFinalJob: Job? = null
    private var sttNoticeJob: Job? = null
    private var idleUnloadJob: Job? = null

    private val _sttResults = MutableSharedFlow<String>(extraBufferCapacity = 16)
    override val sttResults: SharedFlow<String> = _sttResults.asSharedFlow()

    private val _partialResults = MutableSharedFlow<String>(extraBufferCapacity = 16)
    override val partialResults: SharedFlow<String> = _partialResults.asSharedFlow()

    private val _sttNotices = MutableSharedFlow<SttNotice>(extraBufferCapacity = 8)
    override val sttNotices: SharedFlow<SttNotice> = _sttNotices.asSharedFlow()

    private val _classificationResults = MutableSharedFlow<ClassificationResult>(extraBufferCapacity = 16)
    val classificationResults: SharedFlow<ClassificationResult> = _classificationResults.asSharedFlow()

    override val audioLevel: kotlinx.coroutines.flow.StateFlow<Float> = recorder.levelFlow

    /** OMNIPRESENT VOICE — is the platform actually cancelling our own echo? */
    override val echoCancelled: StateFlow<Boolean> = recorder.echoCancelled

    /**
     * OMNIPRESENT VOICE — when false, the recorder keeps running (levels flow,
     * barge-in can watch) but chunks never reach the STT engines. This is how
     * the mic stays *open* while PHANTOM speaks without transcribing his own
     * voice back into the conversation. See [VoiceController.setTranscriptionEnabled].
     */
    @Volatile
    private var transcribing: Boolean = true

    /**
     * Чи відкривається мікрофон під дуплекс. Читається один раз, у мить
     * старту сесії AudioRecord, — джерело потім уже не змінити.
     */
    @Volatile
    private var duplex: Boolean = false

    override fun setDuplex(enabled: Boolean) {
        duplex = enabled
    }

    override fun setTranscriptionEnabled(enabled: Boolean) {
        if (transcribing == enabled) return
        transcribing = enabled
        // Re-enabling means a new utterance begins here; anything the engines
        // half-heard before the gate opened is echo and must not prefix it.
        if (enabled) runCatching { hybridEngine?.reset() }
    }

    // QH3 — exposes which STT pipeline is actually wired right now so
    // Settings can render «слух: Moonshine + Vosk-UA» instead of
    // requiring operator to read logcat.
    private val _activeEngine = MutableStateFlow(SttRouteKind.None)
    override val activeEngine: StateFlow<SttRouteKind> = _activeEngine.asStateFlow()

    private var lastVosk: String? = null
    private var lastYamnet: String? = null
    private var lastMoonshine: String? = null
    private var everInitialised = false

    /**
     * Re-initializes STT engines if models have changed or were newly downloaded.
     *
     * Робота йде у власному scope, а не на потоці того, хто викликав.
     * org.vosk.Model() читає сотні мегабайтів графа й тримає потік кілька
     * секунд; один із трьох викликів приходив із viewModelScope, тобто з
     * головного потоку, і застосунок завмирав (803 пропущені кадри в
     * Choreographer — кнопки просто не встигали реагувати).
     *
     * Ті самі шляхи не переініціалізовують нічого: три джерела оновлення
     * (AppContainer, ContainerBoot, PhantomViewModel) вантажили одну й ту
     * саму модель тричі поспіль.
     */
    fun refreshModels(
        voskModelPath: String?,
        yamnetModelPath: String? = null,
        moonshineDir: File? = null,
    ) {
        if (everInitialised &&
            voskModelPath == lastVosk &&
            yamnetModelPath == lastYamnet &&
            moonshineDir?.absolutePath == lastMoonshine
        ) return
        lastVosk = voskModelPath
        lastYamnet = yamnetModelPath
        lastMoonshine = moonshineDir?.absolutePath
        everInitialised = true
        // Побудова рушіїв коштує ~795 МБ нативної купи: libvosk тримає
        // українську модель РОЗПАКОВАНОЮ, поки жива. «Очі-2» зміряли це
        // бектрейсами malloc — 795 МБ із 823 на свіжому старті, ще до того,
        // як хтось торкнувся мікрофона. На апараті з 2-3 ГБ це смерть.
        //
        // Тому тут лише запамʼятовуємо шляхи. Маршрут публікуємо з НАЯВНОСТІ
        // файлів — питання «чим слухатимемо» не мусить коштувати моделі, так
        // само як питання про готовність голосу (див. SupertonicRoute.isReady).
        releaseStt()
        _activeEngine.value = routeFromFiles()
        scope.launch {
            runCatching { initClassifier(yamnetModelPath) }
                .onFailure { android.util.Log.e("VoiceManager", "Classifier init failed", it) }
        }
    }

    /** Який маршрут ПОСЛУЖИВ БИ — з файлів на диску, без жодного завантаження. */
    private fun routeFromFiles(): SttRouteKind {
        val vosk = VoskRuntime.shipped && lastVosk?.let { File(it).exists() } == true
        val accurate = lastMoonshine?.let {
            local.phantom.companion.core.voice.moonshine.MoonshineSttEngine.allPresent(File(it))
        } == true
        return when {
            vosk && accurate -> SttRouteKind.Hybrid
            vosk -> SttRouteKind.VoskOnly
            accurate -> SttRouteKind.MoonshineOnly
            else -> SttRouteKind.None
        }
    }

    /**
     * Побудувати рушії на ПЕРШИЙ дотик до мікрофона, не раніше.
     */
    private fun ensureSttBuilt() {
        if (hybridEngine != null) return
        runCatching { initStt(lastVosk, lastMoonshine?.let { File(it) }) }
            .onFailure { android.util.Log.e("VoiceManager", "STT init failed", it) }
    }

    /** Віддати модель системі: 795 МБ не мусять лежати між репліками. */
    private fun releaseStt() {
        sttPartialJob?.cancel()
        sttFinalJob?.cancel()
        sttNoticeJob?.cancel()
        sttPartialJob = null
        sttFinalJob = null
        sttNoticeJob = null
        runCatching { hybridEngine?.release() }
        hybridEngine = null
    }

    /**
     * Після простою слух вивантажується. Поки людина мовчить, тримати
     * розпаковану модель — це тримати телефон за горло.
     */
    private fun armIdleUnload() {
        idleUnloadJob?.cancel()
        idleUnloadJob = scope.launch {
            delay(STT_IDLE_UNLOAD_MS)
            releaseStt()
            _activeEngine.value = routeFromFiles()
            android.util.Log.i("VoiceManager", "STT вивантажено після простою")
        }
    }

    private fun initClassifier(yamnetModelPath: String?) {
        classifierJob?.cancel()
        classifierJob = null
        classifier?.release()
        classifier = yamnetModelPath?.takeIf { File(it).exists() }?.let {
            YamnetAudioClassifier(it)
        }
        classifier?.let { c ->
            classifierJob = scope.launch {
                c.results.collect { _classificationResults.emit(it) }
            }
        }
    }

    /**
     * Initializes STT engines.
     * @param voskModelPath Path to Vosk model directory.
     * @param moonshineDir Path to Moonshine Tiny UK model directory.
     */
    fun initStt(
        voskModelPath: String?,
        moonshineDir: File? = null,
    ) {
        // Release old engines and cancel previous stream collector jobs
        sttPartialJob?.cancel()
        sttFinalJob?.cancel()
        sttNoticeJob?.cancel()
        sttPartialJob = null
        sttFinalJob = null
        sttNoticeJob = null
        hybridEngine?.release()

        var voskReal = false
        val voskEngine = if (VoskRuntime.shipped && voskModelPath != null && File(voskModelPath).exists()) {
            try {
                VoskSttEngine(Model(voskModelPath)).also { voskReal = true }
            } catch (e: Exception) {
                android.util.Log.e("VoiceManager", "Failed to init Vosk", e)
                FakeSttEngine()
            }
        } else {
            FakeSttEngine()
        }

        val moonshineEngine = moonshineDir?.takeIf {
            local.phantom.companion.core.voice.moonshine.MoonshineSttEngine.allPresent(it)
        }?.let {
            try {
                local.phantom.companion.core.voice.moonshine.MoonshineSttEngine(it)
            } catch (e: Exception) {
                android.util.Log.e("VoiceManager", "Failed to init Moonshine", e)
                null
            }
        }


        val accurateEngine = moonshineEngine
        hybridEngine = HybridSttEngine(voskEngine, accurateEngine, scope)
        // QH3 — publish the resolved route so operator-visible
        // indicators flip the moment a refresh re-initialises STT.
        _activeEngine.value = when {
            voskReal && accurateEngine != null -> SttRouteKind.Hybrid
            voskReal -> SttRouteKind.VoskOnly
            accurateEngine != null -> SttRouteKind.MoonshineOnly
            else -> SttRouteKind.None
        }
        
        sttPartialJob = scope.launch {
            hybridEngine?.partialResults?.collect { 
                if (it.isNotBlank()) _partialResults.emit(it) 
            }
        }
        sttNoticeJob = scope.launch {
            hybridEngine?.notices?.collect { _sttNotices.emit(it) }
        }
        sttFinalJob = scope.launch {
            hybridEngine?.finalResults?.collect {
                if (it.isNotBlank()) {
                    // Порожній зал — не подія в логах, а втрачена репліка.
                    if (!TranscriptAudience.anyone()) TranscriptAudience.noteUnheard()
                    _sttResults.emit(it)
                }
            }
        }
    }

    override fun startCapturing() {
        // First voice use is the licence to fetch the ear. Deduped downstream,
        // so calling it on every capture is safe and cheap.
        runCatching { onCaptureStart() }
        if (recordingJob != null) return
        idleUnloadJob?.cancel()
        ensureSttBuilt()

        // A fresh capture always transcribes; the duplex engine closes this
        // gate again only for the span of an utterance it is speaking. Without
        // the reset, a session torn down mid-barge-in would leave the next
        // push-to-talk deaf.
        transcribing = true
        hybridEngine?.reset()
        recordingJob = scope.launch {
            launch {
                recorder.startRecording(duplex)
            }
            launch {
                recorder.audioFlow.collect { chunk ->
                    // OMNIPRESENT VOICE — hearing without transcribing. While
                    // PHANTOM speaks, this chunk is mostly *his* voice coming
                    // back through the mic. The recorder must keep running (the
                    // barge-in gate reads its amplitude), but the STT engines
                    // must never see it, or he transcribes himself and dispatches
                    // his own words to the model as the operator's.
                    if (!transcribing) return@collect

                    // QH5 — STT engines can OOM on long recordings
                    // (a long utterance balloons memory holding the
                    // mel-spectrogram in RAM). Catching at the feed
                    // boundary lets us terminate the session cleanly
                    // instead of taking the whole process down.
                    try {
                        hybridEngine?.feed(chunk)
                        classifier?.let { c ->
                            val shorts = ShortArray(chunk.size / 2)
                            for (i in shorts.indices) {
                                val low = chunk[i * 2].toInt() and 0xFF
                                val high = chunk[i * 2 + 1].toInt()
                                shorts[i] = ((high shl 8) or low).toShort()
                            }
                            c.feed(shorts)
                        }
                    } catch (oom: OutOfMemoryError) {
                        android.util.Log.e("VoiceManager", "STT feed OOM — releasing", oom)
                        runCatching { hybridEngine?.release() }
                        hybridEngine = null
                        _activeEngine.value = SttRouteKind.None
                        return@collect
                    } catch (e: IllegalStateException) {
                        // Vosk's native side throws ISE when the model
                        // got into a bad state; release + bubble up.
                        android.util.Log.e("VoiceManager", "STT feed ISE — releasing", e)
                        runCatching { hybridEngine?.release() }
                        hybridEngine = null
                        _activeEngine.value = SttRouteKind.None
                        return@collect
                    }
                }
            }
        }
    }

    suspend fun transcribeClip(pcm16k: ByteArray): String? {
        if (recordingJob != null) return null
        runCatching { onCaptureStart() }
        if (_activeEngine.value == SttRouteKind.None) return null
        idleUnloadJob?.cancel()
        ensureSttBuilt()
        if (_activeEngine.value == SttRouteKind.None) return null
        return try {
            hybridEngine?.transcribe(pcm16k)?.trim()?.ifBlank { null }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            android.util.Log.e("VoiceManager", "STT transcribeClip failed", e)
            null
        } finally {
            if (recordingJob == null) armIdleUnload()
        }
    }

    /**
     * Закрити репліку, лишивши мікрофон відкритим. Сесія AudioRecord — а з
     * нею й [android.media.audiofx.AcousticEchoCanceler] — живе всю розмову,
     * тож придушувач сходиться раз, а не заново перед кожною відповіддю.
     */
    override suspend fun finalizeUtterance(): String? = try {
        hybridEngine?.finalize()
    } catch (e: Throwable) {
        android.util.Log.e("VoiceManager", "STT finalize crashed — returning null", e)
        null
    }

    override suspend fun stopCapturing(): String? {
        // Мікрофоном володіє дуплексний канал, і натисни-і-говори поверх нього
        // — гість. Раніше гість ішов і забирав вухо з собою: одне утримання
        // орба серед живої розмови глушило AudioRecord, AudioLevelHub падав у
        // нуль назавжди, а DuplexVoiceEngine далі вважав, що слухає. Живий
        // діалог помирав мовчки, зі сповіщенням «слухаю» у шторці.
        if (duplex) return finalizeUtterance()

        recordingJob?.cancel()
        recordingJob = null
        recorder.stopRecording()
        armIdleUnload()

        return try {
            hybridEngine?.finalize()
        } catch (oom: OutOfMemoryError) {
            android.util.Log.e("VoiceManager", "STT finalize OOM — returning null", oom)
            runCatching { hybridEngine?.release() }
            hybridEngine = null
            _activeEngine.value = SttRouteKind.None
            null
        } catch (e: Throwable) {
            android.util.Log.e("VoiceManager", "STT finalize crashed — returning null", e)
            null
        }
    }

    fun release() {
        recordingJob?.cancel()
        recordingJob = null
        classifierJob?.cancel()
        classifierJob = null
        sttPartialJob?.cancel()
        sttPartialJob = null
        sttFinalJob?.cancel()
        sttFinalJob = null
        recorder.stopRecording()
        hybridEngine?.release()
        classifier?.release()
        classifier = null
    }

    private companion object {
        /**
         * Дві хвилини тиші — і модель віддається системі.
         *
         * Досить довго, щоб пауза в розмові не коштувала перезавантаження,
         * і досить коротко, щоб телефон не тримав 795 МБ, поки людина читає.
         */
        const val STT_IDLE_UNLOAD_MS = 120_000L
    }
}
