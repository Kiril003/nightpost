package local.phantom.companion.core.net.peer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Два сховки на час переїзду: [primary] і старий [legacy], на якому ще сидять
 * старші збірки. Кожна копія лягає в обидва, забір питає обидва.
 *
 * Старий сховок ніколи не тримає головний шлях: вкидання туди йде фоновою
 * чергою, забір звідти — фоном, а принесене віддається насосу живим шляхом
 * (або наступним забором). Провал лише вмикає паузу; черга чекає її кінця.
 * Людина про нього не дізнається ні затримкою, ні помилкою.
 *
 * Та сама копія з обох сховків — це байт у байт той самий конверт: другу
 * насосу не віддаємо, а палимо там, де вона лежить. Інакше кадр дзвінка
 * прийшов би двічі й рушій прийняв би його за перезапуск.
 */
class PeerRelayFanout(
    private val primary: PeerRelayTransport,
    private val legacy: PeerRelayTransport,
    private val legacyOn: () -> Boolean = { true },
    private val legacyWaitMs: Long = LEGACY_WAIT_MS,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** Де черга до старого сховка переживає перезапуск. null — лише в памʼяті. */
    private val queueFile: File? = null,
) : PeerRelayTransport {

    @Volatile private var quietUntilMs = 0L
    @Volatile private var quietMs = 0L
    private val seen = object : LinkedHashMap<String, Boolean>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>): Boolean = size > SEEN_MAX
    }
    private val queue = ArrayDeque<Pair<String, ByteArray>>().also { it.addAll(load()) }
    private val flushing = AtomicBoolean(false)
    private val fetching = AtomicBoolean(false)
    private val parked = mutableListOf<PeerRelayTransport.Blob>()
    private val burnLater = LinkedHashSet<String>()
    @Volatile private var sink: PeerRelayLive.Events? = null

    private fun legacyLive(): Boolean = legacyOn() && nowMs() >= quietUntilMs

    private fun missed() {
        quietMs = (quietMs * 2).coerceIn(QUIET_MIN_MS, QUIET_MAX_MS)
        quietUntilMs = nowMs() + quietMs
    }

    private fun answered() {
        quietMs = 0L
    }

    override suspend fun drop(tag: String, blob: ByteArray): PeerRelayTransport.Dropped =
        dropMany(listOf(tag to blob)).single()

    override suspend fun dropMany(items: List<Pair<String, ByteArray>>): List<PeerRelayTransport.Dropped> {
        mirror(items)
        return primary.dropMany(items)
    }

    override fun mirror(items: List<Pair<String, ByteArray>>) {
        if (items.isNotEmpty()) {
            synchronized(queue) {
                queue.addAll(items)
                var bytes = queue.sumOf { it.second.size }
                while (queue.size > QUEUE_MAX || bytes > QUEUE_MAX_BYTES) bytes -= queue.removeFirst().second.size
                save()
            }
        }
        flush()
    }

    /** Черга йде в старий сховок пачками; копія лишається, доки сховок не відповів про неї. */
    private fun flush() {
        if (!legacyOn()) {
            synchronized(queue) { if (queue.isNotEmpty()) { queue.clear(); save() } }
            return
        }
        if (!legacyLive() || !flushing.compareAndSet(false, true)) return
        scope.launch {
            try {
                while (legacyLive()) {
                    val batch = synchronized(queue) { queue.take(FLUSH_BATCH) }
                    if (batch.isEmpty()) break
                    val got = withTimeoutOrNull(LEGACY_MIRROR_MS) { runCatching { legacy.dropMany(batch) }.getOrNull() }
                    if (got == null || got.size != batch.size || got.all { it.verdict == PeerRelayTransport.Drop.NO_CONTACT }) {
                        missed()
                        break
                    }
                    answered()
                    val done = batch.filterIndexed { i, _ -> got[i].verdict in SETTLED }
                    if (done.isEmpty()) break
                    synchronized(queue) {
                        done.forEach { item -> queue.indexOfFirst { it === item }.takeIf { it >= 0 }?.let(queue::removeAt) }
                        save()
                    }
                }
            } finally {
                flushing.set(false)
            }
        }
    }

    override suspend fun fetch(tags: List<String>): PeerRelayTransport.Pickup? {
        val main = primary.fetch(tags)
        if (legacyLive()) {
            flush()
            retryBurns()
            fetchLegacy(tags)
        }
        val fresh = main?.blobs.orEmpty().filter { keep(it, PRIMARY) }
        val extra = synchronized(parked) { parked.toList().also { parked.clear() } }
        return when {
            main != null -> PeerRelayTransport.Pickup(fresh + extra, main.storeSinceMs)
            extra.isNotEmpty() -> PeerRelayTransport.Pickup(extra, null)
            else -> null
        }
    }

    /**
     * Забір зі старого сховка — фоном, поза чергою насоса: той тримає замок,
     * поки розбирає забір. Принесене йде живим шляхом під той самий замок, а
     * без живого каналу чекає наступного забору.
     */
    private fun fetchLegacy(tags: List<String>) {
        if (!fetching.compareAndSet(false, true)) return
        scope.launch {
            try {
                val wait = maxOf(legacyWaitMs, legacy.fetchWaitMs ?: legacyWaitMs)
                val back = withTimeoutOrNull(wait) { runCatching { legacy.fetch(tags) }.getOrNull() }
                if (back == null) {
                    missed()
                    return@launch
                }
                answered()
                val extra = back.blobs.filter { keep(it, LEGACY) }
                    .map { PeerRelayTransport.Blob(LEGACY_ID + it.id, it.tag, it.bytes) }
                if (extra.isEmpty()) return@launch
                val live = sink
                if (live != null) live.onBlobs(extra, null) else synchronized(parked) { parked += extra }
            } finally {
                fetching.set(false)
            }
        }
    }

    /** Копію, яку вже приніс другий сховок, насосу не віддаємо — палимо на місці. */
    private fun keep(blob: PeerRelayTransport.Blob, from: Boolean): Boolean {
        val key = digest(blob)
        val other = synchronized(seen) { seen[key].also { if (it == null) seen[key] = from } }
        if (other == null || other == from) return true
        if (from == LEGACY) {
            burnLegacy(listOf(blob.id))
        } else {
            val ids = listOf(blob.id)
            scope.launch { runCatching { primary.burn(ids) } }
        }
        return false
    }

    private fun burnLegacy(ids: List<String>) {
        scope.launch {
            val ok = withTimeoutOrNull(LEGACY_MIRROR_MS) { runCatching { legacy.burn(ids) }.getOrDefault(false) } == true
            if (!ok) {
                synchronized(burnLater) {
                    burnLater.addAll(ids)
                    while (burnLater.size > SEEN_MAX) burnLater.remove(burnLater.first())
                }
            }
        }
    }

    private fun retryBurns() {
        val ids = synchronized(burnLater) { burnLater.toList().also { burnLater.clear() } }
        if (ids.isNotEmpty()) burnLegacy(ids)
    }

    override suspend fun burn(ids: List<String>): Boolean {
        val (old, main) = ids.partition { it.startsWith(LEGACY_ID) }
        if (old.isNotEmpty()) burnLegacy(old.map { it.removePrefix(LEGACY_ID) })
        return if (main.isEmpty()) true else primary.burn(main)
    }

    override fun openLive(events: PeerRelayLive.Events): PeerRelayLive? {
        sink = events
        return primary.openLive(object : PeerRelayLive.Events by events {
            override fun onBlobs(blobs: List<PeerRelayTransport.Blob>, storeSinceMs: Long?) {
                val fresh = blobs.filter { keep(it, PRIMARY) }
                if (fresh.isNotEmpty()) events.onBlobs(fresh, storeSinceMs)
            }
        })
    }

    override val liveDrops: Boolean get() = primary.liveDrops

    override val smallBlobs: Boolean get() = primary.smallBlobs

    override val meter: PeerLinkMeter? get() = primary.meter

    override val pollEveryMs: Long? get() = if (legacyLive()) LEGACY_POLL_MS else null

    override val fetchWaitMs: Long? get() = primary.fetchWaitMs

    override suspend fun probe() {
        quietUntilMs = 0L
        quietMs = 0L
        primary.probe()
        flush()
    }

    private fun load(): List<Pair<String, ByteArray>> {
        val file = queueFile?.takeIf { it.isFile } ?: return emptyList()
        return runCatching {
            file.readLines().mapNotNull { line ->
                val cut = line.indexOf(' ')
                if (cut <= 0) null else line.substring(0, cut) to java.util.Base64.getDecoder().decode(line.substring(cut + 1))
            }
        }.getOrDefault(emptyList()).takeLast(QUEUE_MAX)
    }

    private fun save() {
        val file = queueFile ?: return
        runCatching {
            if (queue.isEmpty()) {
                file.delete()
                return
            }
            val tmp = File(file.path + ".tmp")
            tmp.writeText(queue.joinToString("\n") { (tag, bytes) -> tag + " " + java.util.Base64.getEncoder().encodeToString(bytes) })
            tmp.renameTo(file)
        }
    }

    companion object {
        const val LEGACY_WAIT_MS = 3_000L
        const val LEGACY_CONNECT_MS = 3_000L
        const val LEGACY_MIRROR_MS = 30_000L
        const val LEGACY_POLL_MS = 120_000L
        const val QUIET_MIN_MS = 60_000L
        const val QUIET_MAX_MS = 1_800_000L
        const val LEGACY_ID = "legacy:"
        const val QUEUE_MAX = 512
        const val QUEUE_MAX_BYTES = 4 * 1024 * 1024
        private const val FLUSH_BATCH = 32
        private val SETTLED = setOf(PeerRelayTransport.Drop.HELD, PeerRelayTransport.Drop.REFUSED)
        private const val SEEN_MAX = 2_048
        private const val PRIMARY = true
        private const val LEGACY = false

        private fun digest(blob: PeerRelayTransport.Blob): String {
            val md = MessageDigest.getInstance("SHA-256")
            md.update(blob.tag.toByteArray(Charsets.US_ASCII))
            md.update(blob.bytes)
            return java.util.Base64.getEncoder().encodeToString(md.digest())
        }
    }
}
