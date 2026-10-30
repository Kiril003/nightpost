package local.phantom.companion.core.voice.route

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import local.phantom.companion.core.voice.TtsEngine
import kotlin.coroutines.resume

/**
 * Bridge to Android's built-in TextToSpeech engine. Voice quality varies by
 * OEM (Samsung TTS / Google TTS / Pico) but uk-UA support is universal on
 * Android 11+ (API 30+). Ships in Phase 2-B-1 as the immediate "operator
 * hears Familiar" path while Supertonic 3 ONNX (Phase 2-B-2) lands behind a
 * lazy model download.
 *
 * Initialisation is deferred to the first [isReady] or [speak] call via
 * [ensureEngine] so cold app launch is not blocked.
 */
class AndroidSystemTts(
    private val appContext: Context,
    /**
     * Read fresh on every utterance, never captured once.
     *
     * The operator can move the sliders while PHANTOM is mid-sentence, and the
     * next sentence must obey — a tuning snapshotted at construction would
     * take a restart to hear, which is how a working control feels broken.
     */
    private val tuning: () -> VoiceTuning? = { null },
) : TtsEngine {

    override val displayName = "Android System · uk-UA"

    private val initialised = CompletableDeferred<Boolean>()
    private val initRequested = AtomicBoolean(false)

    @Volatile
    private var engine: TextToSpeech? = null

    private fun ensureEngine(): TextToSpeech? {
        if (initRequested.compareAndSet(false, true)) {
            runCatching {
                engine = TextToSpeech(appContext) { status ->
                    runCatching {
                        if (status == TextToSpeech.SUCCESS) {
                            val supportSet = engine?.isLanguageAvailable(Locale("uk", "UA"))
                                ?: TextToSpeech.LANG_NOT_SUPPORTED
                            if (supportSet >= TextToSpeech.LANG_AVAILABLE) {
                                engine?.language = Locale("uk", "UA")
                                initialised.complete(true)
                            } else {
                                initialised.complete(false)
                            }
                        } else {
                            initialised.complete(false)
                        }
                    }.onFailure {
                        initialised.complete(false)
                    }
                }
            }.onFailure { t ->
                android.util.Log.e("AndroidSystemTts", "TextToSpeech construction failed", t)
                initialised.complete(false)
            }
        }
        return engine
    }

    override suspend fun isReady(): Boolean {
        ensureEngine()
        return initialised.await()
    }

    override suspend fun speak(text: String, locale: String): Result<Unit> =
        speak(text, locale, isNavigation = false)

    /**
     * HIGHWAY / voice — [isNavigation] switches this shared engine onto
     * USAGE_ASSISTANCE_NAVIGATION_GUIDANCE for the one utterance, then the
     * *next* call (nav or chat) sets attributes again explicitly — the
     * engine instance is stateful and remembers whatever was set last, so
     * every call must state its own channel rather than assume a default.
     * Called only from [RoutedTts.speakUrgent] / [RoutedTts.speakNavigation].
     */
    suspend fun speak(text: String, locale: String, isNavigation: Boolean): Result<Unit> {
        if (!isReady()) {
            return Result.failure(
                IllegalStateException("System TTS not ready or uk-UA not installed"),
            )
        }
        val tts = engine ?: return Result.failure(IllegalStateException("engine null"))
        tts.setAudioAttributes(if (isNavigation) NAV_ATTRIBUTES else DEFAULT_ATTRIBUTES)
        // Prosody is sticky engine state, exactly like the audio attributes
        // above — and for the same reason it must be restated on EVERY
        // utterance rather than set once at init. Anything else in the process
        // that touches this shared engine (or the engine's own defaults after
        // a vendor service restart) would otherwise decide how PHANTOM sounds.
        //
        // Until 31.08 neither of these was ever called anywhere in the tree,
        // so the voice ran on vendor defaults and the operator's settings did
        // not exist. The values are clamped in VoiceTuning, not here: the
        // engine accepts any float silently, so a bad number produces
        // gibberish with no error to trace back.
        // Замовчування — `null`, тобто «не чіпати нічого», а НЕ нейтральні 1.0.
        // Різниця не косметична: людина могла пришвидшити мовлення в системних
        // налаштуваннях доступності, і рушій досі це шанував, бо в цьому дереві
        // ніхто ніколи не кликав setSpeechRate. Виклик із 1.0 «за замовчуванням»
        // мовчки скасував би її вибір — саме той різновид вади, проти якого вся
        // ця хвиля: «ніхто не питав» перетворене на твердження. Поки повзунків
        // немає, ми не маємо думки про темп і не висловлюємо її.
        tuning()?.let { prosody ->
            tts.setPitch(prosody.pitch)
            tts.setSpeechRate(prosody.rate)
        }
        return suspendCancellableCoroutine { cont ->
            val utteranceId = "phantom-${System.nanoTime()}"
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    if (cont.isActive) cont.resume(Result.success(Unit))
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (cont.isActive) {
                        cont.resume(Result.failure(RuntimeException("TTS playback error")))
                    }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    if (cont.isActive) {
                        cont.resume(
                            Result.failure(RuntimeException("TTS playback error code=$errorCode")),
                        )
                    }
                }
            })
            val result = tts.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
            if (result == TextToSpeech.ERROR && cont.isActive) {
                cont.resume(Result.failure(RuntimeException("TTS speak() returned ERROR")))
            }
            cont.invokeOnCancellation { tts.stop() }
        }
    }

    override fun stop() {
        engine?.stop()
    }

    /**
     * Releases native TTS resources. Call when the hosting component is
     * destroyed (e.g. in [android.app.Application.onTerminate] or a
     * ViewModel's onCleared equivalent).
     */
    fun shutdown() {
        engine?.shutdown()
        engine = null
    }

    private companion object {
        val NAV_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        // Mirrors android.speech.tts.TextToSpeech's own built-in default —
        // restoring it explicitly is what keeps chat speech unchanged after
        // a nav cue has switched the shared engine to NAV_ATTRIBUTES.
        val DEFAULT_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}
