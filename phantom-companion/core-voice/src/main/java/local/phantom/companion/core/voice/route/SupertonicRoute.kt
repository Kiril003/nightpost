package local.phantom.companion.core.voice.route

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import local.phantom.companion.core.voice.TtsEngine
import local.phantom.companion.core.voice.audio.PcmStreamPlayer
import local.phantom.companion.core.voice.stream.SpeechSpan
import local.phantom.companion.core.voice.supertonic.PhraseChunker
import local.phantom.companion.core.voice.supertonic.SupertonicSynth
import java.util.concurrent.Executors

/**
 * Premium [TtsEngine] over Supertonic 3 — the streaming voice.
 *
 * The pipeline is the point: [PhraseChunker] cuts the utterance into
 * segments (short opener first), and while segment k is *playing*
 * through the [PcmStreamPlayer] session, segment k+1 is already inside
 * ONNX Runtime. Time-to-first-audio is one short-segment synthesis; the
 * rest of the utterance hides behind playback.
 *
 * Synthesis runs on a dedicated single-thread executor — never a shared
 * dispatcher — so ORT sessions stay thread-confined and a long
 * flow-matching loop can't starve Default-pool coroutines. Playback
 * lives on the player's own feeder thread. Neither knows the UI exists:
 * no Compose, no Lifecycle, no recomposition can touch this path — the
 * voice keeps speaking while the operator dives from Stratum to Stratum.
 *
 * [speak] returns when the utterance has finished *playing* (the
 * [TtsEngine] contract) or failed; failure before any audio is emitted
 * returns failure so RoutedTts can fall back to System TTS mid-turn.
 */
class SupertonicRoute(
    private val synth: SupertonicSynth,
    private val player: PcmStreamPlayer,
    private val chunker: PhraseChunker = PhraseChunker(),
    /**
     * Пауза між сегментами, читається на КОЖНУ фразу.
     *
     * До 31.08 це було просте число зі стандартом, якого ніхто ніколи не
     * передавав інакше — тобто проводка була, ручки не існувало. Лямбда, а не
     * значення, бо людина може змінити паузу посеред мовлення, і наступна
     * фраза мусить слухатись, не чекаючи перезапуску.
     *
     * Обрізається [saneGapMs]: відʼємне дало б FloatArray негативного розміру
     * і падіння, а надто велике перетворює мову на набір розрізнених слів.
     */
    private val interSegmentSilenceMs: () -> Int = { DEFAULT_GAP_MS },
    private val idleReleaseMs: Long = IDLE_RELEASE_MS,
) : TtsEngine {

    override val displayName = "Supertonic 3 · стрім"

    private val synthDispatcher: CoroutineDispatcher =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "phantom-supertonic-synth").apply { isDaemon = true }
        }.asCoroutineDispatcher()

    private val reaperScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var reaper: Job? = null

    // Чотири сесії ORT тримають сотні МБ нативної памʼяті; між фразами вони не потрібні.
    private fun loadForUse() {
        reaper?.cancel()
        if (!synth.isLoaded) synth.load()
    }

    private fun scheduleRelease() {
        reaper?.cancel()
        reaper = reaperScope.launch {
            delay(idleReleaseMs)
            withContext(synthDispatcher) { releaseNow() }
        }
    }

    private fun releaseNow() {
        if (!synth.isLoaded) return
        synth.close()
        synchronized(prewarmed) { prewarmed.clear() }
        Log.i(TAG, "released ORT sessions after idle")
    }

    /** Звільнити рушій зараз (тиск памʼяті); наступна фраза завантажить його знову. */
    suspend fun release() {
        reaper?.cancel()
        withContext(synthDispatcher) { releaseNow() }
    }

    /**
     * ON-TIME VOICE — the last measured time-to-first-audio, ms, or null
     * before this engine has ever spoken. [RoutedTts.speakUrgent] reads it
     * to decide whether this voice is fast enough for a maneuver cue: a
     * beautiful turn instruction that starts two seconds late is a wrong
     * instruction. Null deliberately reads as «unproven» — an urgent cue
     * never waits for a cold ORT load to find out.
     */
    @Volatile
    var lastFirstAudioMs: Long? = null
        private set

    /** Сесії ORT зараз у памʼяті: лише тоді виміряна швидкість щось каже про наступну фразу. */
    val isWarm: Boolean get() = synth.isLoaded

    /** Why the last [isReady] said no; null once a load has succeeded. */
    @Volatile
    var lastLoadError: Throwable? = null
        private set

    /**
     * ON-TIME VOICE — pre-synthesized PCM, keyed by exact segment text.
     * Guidance knows the final turn cue's words the moment a maneuver
     * becomes «next», minutes before they are needed; synthesizing them
     * then means the premium voice can speak the turn with ~zero
     * time-to-first-audio instead of losing it to System TTS. Access-order
     * LRU, capped — a handful of short imperatives, not a library.
     */
    private val prewarmed = object : LinkedHashMap<String, FloatArray>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>): Boolean =
            size > PREWARM_CACHE_MAX
    }

    private fun langOf(locale: String) = locale.substringBefore('-').lowercase().ifBlank { "uk" }

    private fun cacheKey(segment: String, lang: String) = "$lang|$segment"

    /** Is [text]'s OPENING segment already synthesized? (TTFA is the opener.) */
    fun hasPrewarmed(text: String, locale: String): Boolean {
        val first = chunker.split(text).firstOrNull() ?: return false
        val key = cacheKey(first, langOf(locale))
        synchronized(prewarmed) { return prewarmed.containsKey(key) }
    }

    /**
     * Synthesize [text] ahead of need, off the urgent path. Queued on the
     * same single synth thread as [speak], so it can never race a live
     * utterance — it simply fills the quiet between them. Failures are the
     * caller's to ignore: a cue that was not prewarmed still speaks, just
     * through whichever engine is fastest then.
     */
    suspend fun prewarm(text: String, locale: String = "uk-UA"): Result<Unit> {
        val segments = chunker.split(text)
        if (segments.isEmpty()) return Result.success(Unit)
        val lang = langOf(locale)
        return withContext(synthDispatcher) {
            runCatching {
                loadForUse()
                segments.forEach { segment ->
                    val key = cacheKey(segment, lang)
                    val known = synchronized(prewarmed) { prewarmed.containsKey(key) }
                    if (!known) {
                        val pcm = runInterruptible { synth.synthesize(segment, lang) }
                        synchronized(prewarmed) { prewarmed[key] = pcm }
                    }
                }
            }.onFailure { if (!synth.isLoaded) lastLoadError = it }
                .also { scheduleRelease() }
        }
    }

    /**
     * Прогрів після першого кадру: завантажити сесії й синтезувати одну коротку
     * фразу. Час синтезу теплого рушія стає [lastFirstAudioMs] — живий голос
     * доведений, і термінова фраза більше не йде системному роботу лише тому,
     * що Supertonic ще жодного разу не говорив.
     */
    suspend fun warmUp(locale: String = "uk-UA"): Result<Long> = withContext(synthDispatcher) {
        runCatching {
            loadForUse()
            val startedAt = SystemClock.elapsedRealtime()
            runInterruptible { synth.synthesize(WARM_LINE, langOf(locale)) }
            (SystemClock.elapsedRealtime() - startedAt).also {
                lastFirstAudioMs = it
                Log.i(TAG, "warm-up: ${it}ms")
            }
        }.onFailure { if (!synth.isLoaded) lastLoadError = it }
            .also { scheduleRelease() }
    }

    /**
     * ПИТАННЯ, А НЕ ЗАВАНТАЖЕННЯ.
     *
     * Тут стояло `if (!synth.isLoaded) synth.load()` — тобто відповідь
     * коштувала чотирьох сесій ONNX над ~398 МБ ваг (vector_estimator 256 МБ,
     * vocoder 101 МБ, text_encoder 36 МБ, duration_predictor 3,7 МБ).
     * `ContainerBoot` кличе `RoutedTts.refreshPremiumStatus()` на старті, тож
     * ці 400 МБ нативної купи бралися ДО першого екрана — на апараті з 2-3 ГБ
     * це виклик lowmemorykiller ще на ПІНі.
     *
     * Ваги піднімає `speak`/`prewarm`, коли говорити справді просять: обидва
     * шляхи вже мають власний `if (!synth.isLoaded) synth.load()`.
     */
    override suspend fun isReady(): Boolean = withContext(synthDispatcher) {
        synth.canLoad
    }

    override suspend fun speak(text: String, locale: String): Result<Unit> =
        speak(text, locale, isNavigation = false)

    /**
     * HIGHWAY / voice — same pipeline as [speak], but the output track
     * carries USAGE_ASSISTANCE_NAVIGATION_GUIDANCE instead of
     * USAGE_ASSISTANT. Called only from [RoutedTts.speakUrgent] /
     * [RoutedTts.speakNavigation], both exclusively fed by
     * [local.phantom.companion.core.nav.NavCueSpeaker] — chat speech never
     * sets this.
     */
    suspend fun speak(text: String, locale: String, isNavigation: Boolean): Result<Unit> {
        val segments = chunker.split(text)
        if (segments.isEmpty()) return Result.success(Unit)
        val lang = locale.substringBefore('-').lowercase().ifBlank { "uk" }

        return withContext(synthDispatcher) {
            runCatching {
                // The number the operator actually feels: how long he stares
                // at silence before the first sample leaves the speaker.
                val spokeAt = SystemClock.elapsedRealtime()
                loadForUse()
                val session = player.startSession(synth.sampleRate, isNavigation)
                val silence = FloatArray(synth.sampleRate * saneGapMs(interSegmentSilenceMs()) / 1000)
                try {
                    segments.forEachIndexed { i, segment ->
                        if (session.isCancelled) return@runCatching
                        // Prewarmed PCM plays as-is; otherwise a blocking ORT
                        // call, interruptible so stop() → coroutine
                        // cancellation doesn't wait out a multi-second
                        // segment.
                        val startedAt = SystemClock.elapsedRealtime()
                        val cached = synchronized(prewarmed) { prewarmed[cacheKey(segment, lang)] }
                        val pcm = cached ?: runInterruptible { synth.synthesize(segment, lang) }
                        if (session.isCancelled) return@runCatching
                        if (i > 0 && silence.isNotEmpty()) session.enqueue(silence.copyOf())
                        session.enqueue(pcm)
                        if (i == 0) {
                            val ttfa = SystemClock.elapsedRealtime() - spokeAt
                            // A cache hit says nothing about SYNTHESIS speed —
                            // it must not talk the urgent router into trusting
                            // a cold engine with an unprewarmed cue.
                            if (cached == null) lastFirstAudioMs = ttfa
                            Log.i(TAG, "time-to-first-audio: ${ttfa}ms" + if (cached != null) " (prewarmed)" else "")
                        }

                        // The number that decides whether this voice can
                        // stream at all: synthesis wall-time ÷ audio
                        // produced. RTF < 1 means segment k+1 is ready
                        // before segment k finishes playing (gapless);
                        // RTF > 1 means playback drains faster than we
                        // can fill it and the operator hears seams. The
                        // fix when it exceeds 1 is fewer flow-matching
                        // steps, so log it rather than guess in a lab.
                        val elapsed = SystemClock.elapsedRealtime() - startedAt
                        val audioMs = pcm.size * 1000L / synth.sampleRate
                        if (audioMs > 0) {
                            Log.i(
                                TAG,
                                "seg $i/${segments.lastIndex}: ${segment.length}ch → " +
                                    "${audioMs}ms audio in ${elapsed}ms · RTF ${"%.2f".format(elapsed.toFloat() / audioMs)}",
                            )
                        }
                    }
                } finally {
                    session.finish()
                }
                // Utterance length / realtime + generous slack; a wedged
                // audio route must not hold the conversation hostage.
                val timeoutMs = 30_000L + text.length * 200L
                check(session.awaitCompletion(timeoutMs)) { "playback drain timed out" }
            }.also { scheduleRelease() }
        }
    }

    override fun stop() {
        player.cancelActive()
    }

    override fun setGain(gain: Float) {
        player.setGain(gain)
    }

    /**
     * ЖИВА РОЗМОВА — репліка, текст якої ще пишеться.
     *
     * [speak] знає всю фразу наперед і тому може порізати її одним махом.
     * Жива відповідь так не приходить: модель видає її по шматку, і чекати
     * кінця — це і є ті 4–8 секунд мовчання, заради яких усе це робиться.
     *
     * Один сеанс плеєра на всю репліку — тому склейка беззвучна, скасування
     * одне на всіх, а конверт відтворення лишається суцільним (Orb не
     * блимає між реченнями, і воротар перехоплення не відкривається в
     * власне ехо на кожній комі).
     */
    fun openStream(locale: String = "uk-UA"): Stream = Stream(langOf(locale))

    inner class Stream internal constructor(private val lang: String) : SpeechSpan {

        private var session: PcmStreamPlayer.Session? = null
        private var framesEnqueued = 0L

        /** (кадр, на якому шматок закінчується) → текст, промовлений до нього. */
        private val marks = ArrayList<Pair<Long, String>>()
        private val saidSoFar = StringBuilder()

        @Volatile
        private var opened = false

        /** Промовити наступний шматок. false — синтез не впорався. */
        override suspend fun say(segment: String): Boolean {
            val text = segment.trim()
            if (text.isEmpty()) return true
            return withContext(synthDispatcher) {
                runCatching {
                    loadForUse()
                    val startedAt = SystemClock.elapsedRealtime()
                    val cached = synchronized(prewarmed) { prewarmed[cacheKey(text, lang)] }
                    val pcm = cached ?: runInterruptible { synth.synthesize(text, lang) }
                    val live = session ?: player.startSession(synth.sampleRate).also {
                        session = it
                        opened = true
                    }
                    if (live.isCancelled) return@runCatching false
                    val gapMs = saneGapMs(interSegmentSilenceMs())
                    if (framesEnqueued > 0L && gapMs > 0) {
                        val gap = FloatArray(synth.sampleRate * gapMs / 1000)
                        live.enqueue(gap)
                        framesEnqueued += gap.size
                    }
                    live.enqueue(pcm)
                    framesEnqueued += pcm.size
                    saidSoFar.append(if (saidSoFar.isEmpty()) "" else " ").append(text)
                    synchronized(marks) { marks += framesEnqueued to saidSoFar.toString() }
                    val elapsed = SystemClock.elapsedRealtime() - startedAt
                    val audioMs = pcm.size * 1000L / synth.sampleRate
                    if (audioMs > 0) {
                        Log.i(
                            TAG,
                            "stream ${text.length}ch → ${audioMs}ms audio in ${elapsed}ms · " +
                                "RTF ${"%.2f".format(elapsed.toFloat() / audioMs)}" +
                                if (cached != null) " (prewarmed)" else "",
                        )
                    }
                    true
                }.getOrElse {
                    Log.w(TAG, "stream segment failed: ${it.message}")
                    false
                }.also { scheduleRelease() }
            }
        }

        /**
         * Те, що людина СПРАВДІ почула, станом на зараз — за головкою
         * відтворення, а не за тим, що ми встигли насинтезувати. Саме це
         * йде в історію після перехоплення, інакше модель наступного ходу
         * посилатиметься на слова, яких ніхто не чув.
         */
        override fun heardSoFar(): String {
            val head = session?.framesRendered ?: return ""
            synchronized(marks) {
                var last = ""
                for ((endFrame, text) in marks) {
                    if (endFrame <= head) last = text else break
                }
                return last
            }
        }

        /** Увесь текст, відданий синтезу (почутий чи ні). */
        override fun enqueuedText(): String = saidSoFar.toString()

        val isOpen: Boolean get() = opened

        /** Більше тексту не буде — договорити те, що в черзі. */
        override suspend fun finish(timeoutMs: Long) {
            val live = session ?: return
            live.finish()
            withContext(Dispatchers.IO) { live.awaitCompletion(timeoutMs) }
        }

        override fun cancel() {
            session?.cancel()
        }
    }

    internal companion object {
        const val TAG = "SupertonicRoute"

        const val IDLE_RELEASE_MS = 90_000L

        const val WARM_LINE = "Так."

        /** A handful of imperatives (~350 KB each), never a library. */
        const val PREWARM_CACHE_MAX = 6

        /** Точно те, що стояло тут як літерал до 31.08 — звучання не змінюється. */
        const val DEFAULT_GAP_MS = 120

        /**
         * Межі паузи між сегментами.
         *
         * Нуль дозволений навмисно — це «без пауз», законний вибір для того,
         * хто хоче найшвидшу мову. А от **відʼємне значення не просто негарне:
         * `FloatArray(-N)` кидає виняток**, тобто одне зле число з налаштувань
         * поклало б увесь голос. Верхня межа — про сенс: пауза довша за пів
         * секунди перетворює речення на перелік окремих слів.
         */
        const val MIN_GAP_MS = 0
        const val MAX_GAP_MS = 500

        fun saneGapMs(v: Int): Int = when {
            v < MIN_GAP_MS -> MIN_GAP_MS
            v > MAX_GAP_MS -> MAX_GAP_MS
            else -> v
        }
    }
}
