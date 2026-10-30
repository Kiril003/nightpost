package local.phantom.companion.core.voice.route

import android.content.res.AssetManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import local.phantom.companion.core.voice.TtsEngine
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/**
 * Pre-rendered PHANTOM voice for navigation. Every bakeable guidance phrase
 * (all roadName-less maneuver × distance combinations) ships as an .ogg, so a
 * maneuver cue starts in milliseconds in PHANTOM's own voice instead of being
 * handed to Android System TTS because Supertonic would be too slow.
 *
 * A miss returns failure so [RoutedTts] falls through to the live engines.
 */
class BakedNavVoice(
    private val dir: File,
    private val assets: AssetManager? = null,
) : TtsEngine {

    private sealed interface Clip {
        data class OnDisk(val file: File) : Clip
        data class InApk(val path: String) : Clip
    }

    override val displayName = "Nightpost · навігаційний пак"

    private val json = Json { ignoreUnknownKeys = true }
    private val player = AtomicReference<MediaPlayer?>(null)

    @Volatile
    private var index: Map<String, Clip>? = null

    @Volatile
    private var loadFailed = false

    val clipCount: Int get() = index?.size ?: 0

    private fun load(): Map<String, Clip>? {
        index?.let { return it }
        if (loadFailed) return null
        synchronized(this) {
            index?.let { return it }
            val onDisk = MANIFESTS.map { File(dir, it) }.firstOrNull { it.isFile && it.length() > 0 }
            val source: String? = onDisk?.readText() ?: assets?.let { am ->
                MANIFESTS.firstNotNullOfOrNull { name ->
                    runCatching { am.open("$ASSET_DIR/$name").bufferedReader().readText() }.getOrNull()
                }
            }
            if (source == null) {
                loadFailed = true
                return null
            }
            val built = runCatching {
                json.parseToJsonElement(source).jsonArray.mapNotNull { el ->
                    val o = el.jsonObject
                    val key = o["key"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val file = o["file"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val clip = if (onDisk != null) {
                        File(dir, file).takeIf { it.isFile && it.length() > MIN_CLIP_BYTES }?.let(Clip::OnDisk)
                    } else {
                        Clip.InApk("$ASSET_DIR/$file")
                    }
                    clip?.let { normalizeKey(key) to it }
                }.toMap()
            }.getOrElse {
                Log.w(TAG, "manifest unreadable: ${it.message}")
                loadFailed = true
                return null
            }
            if (built.isEmpty()) {
                loadFailed = true
                return null
            }
            Log.i(TAG, "baked nav voice: ${built.size} clips (${if (onDisk != null) "pushed" else "shipped"})")
            index = built
            return built
        }
    }

    /** Drop the memoized index so a freshly installed pack is picked up. */
    fun invalidate() {
        synchronized(this) {
            index = null
            loadFailed = false
        }
    }

    // Parsing a ~800-entry manifest is file IO; it must not run on whatever
    // thread happened to ask the router to speak.
    override suspend fun isReady(): Boolean = withContext(Dispatchers.IO) { load() != null }

    fun has(text: String): Boolean = lookup(text) != null

    /**
     * Exact match first; failing that, the same maneuver without its street
     * suffix. Street names cannot be baked, but the direction is what the
     * wheel needs — PHANTOM saying «через 200 метрів поверніть праворуч»
     * beats System TTS saying it with the street attached.
     */
    private fun lookup(text: String): Clip? {
        val idx = load() ?: return null
        idx[normalizeKey(text)]?.let { return it }
        val stripped = ROAD_SUFFIX.replace(text.trim(), "")
        if (stripped.length != text.trim().length) return idx[normalizeKey(stripped)]
        return null
    }

    override suspend fun speak(text: String, locale: String): Result<Unit> {
        if (!locale.startsWith("uk", ignoreCase = true)) return MISS
        val clip = withContext(Dispatchers.IO) { lookup(text) } ?: return MISS
        return play(clip)
    }

    private suspend fun play(clip: Clip): Result<Unit> = runCatching {
        stop()
        suspendCancellableCoroutine { cont ->
            val mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                when (clip) {
                    is Clip.OnDisk -> setDataSource(clip.file.absolutePath)
                    is Clip.InApk -> {
                        val am = assets ?: throw IllegalStateException("no assets to read ${clip.path}")
                        am.openFd(clip.path).use { fd ->
                            setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                        }
                    }
                }
            }
            player.set(mp)

            fun finish(result: Result<Unit>) {
                if (player.compareAndSet(mp, null)) runCatching { mp.release() }
                if (cont.isActive) cont.resume(result)
            }

            mp.setOnCompletionListener { finish(Result.success(Unit)) }
            mp.setOnErrorListener { _, what, extra ->
                finish(Result.failure(IllegalStateException("MediaPlayer error $what/$extra")))
                true
            }
            cont.invokeOnCancellation {
                if (player.compareAndSet(mp, null)) runCatching { mp.stop(); mp.release() }
            }
            runCatching {
                mp.prepare()
                mp.start()
            }.onFailure { finish(Result.failure(it)) }
        }.getOrThrow()
    }

    override fun stop() {
        player.getAndSet(null)?.let { mp -> runCatching { mp.stop() }; runCatching { mp.release() } }
    }

    companion object {
        private const val TAG = "BakedNavVoice"
        const val DIR = "navvoice"
        private const val ASSET_DIR = "navvoice"
        private const val MIN_CLIP_BYTES = 500L
        private val MANIFESTS = listOf("manifest.json", "manifest.partial.json")
        private val MISS = Result.failure<Unit>(NoSuchElementException("no baked clip"))

        /** « … на Хрещатик» — the only part of a guidance cue that is unbakeable. */
        internal val ROAD_SUFFIX = Regex("\\s+на\\s+\\S.*$")

        /**
         * Lookup must survive the cosmetic drift between the string the app
         * builds and the string that was baked: case, spacing, and the
         * apostrophe variants Ukrainian text picks up along the way.
         */
        fun normalizeKey(text: String): String =
            text.trim()
                .replace('\'', 'ʼ')
                .replace('’', 'ʼ')
                .replace('`', 'ʼ')
                .replace(Regex("\\s+"), " ")
                .removeSuffix(".")
                .lowercase()
    }
}
