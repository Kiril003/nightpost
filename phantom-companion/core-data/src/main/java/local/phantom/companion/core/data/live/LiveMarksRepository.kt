package local.phantom.companion.core.data.live

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import local.phantom.companion.core.data.spatial.HAZARD_LAYER_KIND
import local.phantom.companion.core.data.spatial.HAZARD_MARK_DEFAULT_TTL_MIN
import local.phantom.companion.core.data.spatial.HAZARD_MERGE_RADIUS_M
import local.phantom.companion.core.data.spatial.HAZARD_SOURCE_COMMUNITY
import local.phantom.companion.core.data.spatial.HazardMarkInput
import local.phantom.companion.core.data.spatial.SpatialLayer
import local.phantom.companion.core.data.spatial.SpatialLayerSink
import local.phantom.companion.core.data.spatial.SpatialMarker
import local.phantom.companion.core.data.spatial.hazardDistanceM
import local.phantom.companion.core.data.spatial.hazardMarkerOf
import local.phantom.companion.core.data.spatial.mutateHazardLayer
import local.phantom.companion.core.net.geosrc.GeoResult
import local.phantom.companion.core.net.geosrc.SourceUnavailable
import local.phantom.companion.core.net.live.LiveGeohash
import local.phantom.companion.core.net.live.LiveMark
import local.phantom.companion.core.net.live.LiveMarkDraft
import local.phantom.companion.core.net.live.LiveMarksConfig
import local.phantom.companion.core.net.live.LiveMarksPort
import local.phantom.companion.core.net.live.LiveMarksStreamPort
import local.phantom.companion.core.net.live.LiveStreamEvent
import kotlin.math.ceil

/**
 * ATLAS-4 v0 — живий шар міток поверх уже наявного шару небезпек.
 *
 * Local-first: усе працює з вимкненою синхронізацією. Площина лише додає
 * два рухи — власні мітки їдуть у чергу назовні, чужі приходять у ТОЙ САМИЙ
 * шар «позначки на мапі». Тому UI цієї хвилі не має жодного рядка: мапа вже
 * малює цей шар.
 *
 * Чесність: чужа мітка несе провенанс [HAZARD_SOURCE_COMMUNITY] у даних і
 * гасне за власним TTL, а не за життям шару — цикл нижче вичищає прострочені
 * чужі мітки сам, бо в шарі один термін життя на всіх.
 */

/** Ділянка, для якої зараз питають мітки — центр вʼюпорта, не позиція оператора. */
data class LiveRegion(val lat: Double, val lon: Double, val radiusM: Double = 3_000.0)

/**
 * Свідчення живої площини. Саме звідси читаються ворота «два пристрої бачать
 * мітки одне одного за <5 с».
 *
 * [lastLagMs] — від серверного `created_at` до годинника пристрою, тож у ньому
 * сидить і розбіжність цих двох годинників: на телефоні без синхронізації
 * часу число буде хибним і навіть відʼємним. Це замір, а не істина, і він
 * гасне разом із підпискою — мертвий сокет не має показувати свіжу цифру.
 *
 * [joined] означає «підписка є», а НЕ «дані свіжі»: сокет доносить лише те, що
 * трапилось поки він тримався. Свіжість гарантує звіряльне опитування.
 */
data class LiveStreamStats(
    val joined: Boolean = false,
    val received: Int = 0,
    val merged: Int = 0,
    val outOfRegion: Int = 0,
    val lastLagMs: Long? = null,
)

class LiveMarksRepository(
    private val port: LiveMarksPort,
    private val sink: SpatialLayerSink,
    private val layers: Flow<List<SpatialLayer>>,
    /** Null, поки мапа закрита — тоді регіон не питаємо взагалі. */
    private val region: () -> LiveRegion?,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val pullIntervalMs: Long = DEFAULT_PULL_INTERVAL_MS,
    private val tickMs: Long = DEFAULT_TICK_MS,
    /** Прискорювач. Null — площина живе самим опитуванням, як і до цієї хвилі. */
    private val stream: LiveMarksStreamPort? = null,
) {

    private val gate = Mutex()
    private val outbox = ArrayDeque<Pending>()
    private val queuedKeys = LinkedHashSet<String>()

    // Що вже поїхало (або відхилено назавжди). Без цього кожен новий знімок
    // шару ставив би ту саму мітку в чергу знову: вона з нього нікуди не діється.
    private val settledKeys = LinkedHashSet<String>()

    // Термін життя кожної чужої мітки. У шарі його зберігати ніде — там один
    // expiry на весь шар, — тож він живе тут і гасить мітку на наступному циклі.
    // Чіпається ВИКЛЮЧНО з-під замка шару (всередині `mutateHazardLayer`) —
    // це єдине, що робить її потокобезпечною після зняття власного замка.
    private val remoteExpiry = LinkedHashMap<String, Long>()

    // Знімок найближчого згасання для перевірки без замка: вхід у замок коштує
    // читання Room, а тік ходить раз на пʼять секунд.
    @Volatile
    private var earliestRemoteExpiryMs: Long = Long.MAX_VALUE

    private var lastPullAtMs: Long = 0L
    private var lastSeenAtMs: Long? = null

    // Клітинки регіону рахуються на обох шляхах — опитування питає ними
    // сервер, сокет ними ж відсіює чужий район. Пара, а не два поля: читають
    // її з двох корутин і рвана пара дала б клітинки не від того вʼюпорта.
    @Volatile
    private var cells: Pair<LiveRegion, List<String>>? = null

    @Volatile
    private var streamJob: Job? = null

    private var idleTicks = 0

    /** Свідчення для воріт затримки. Читається діагностикою й тестами. */
    @Volatile
    var streamStats: LiveStreamStats = LiveStreamStats()
        private set

    /** Скільки міток чекає на відправку. Для діагностики й тестів. */
    suspend fun pendingCount(): Int = gate.withLock { outbox.size }

    /**
     * Запускає обидва рухи. Повертає роботу, щоб контейнер міг її зупинити.
     * Нічого не робить, якщо ключів немає — площини просто немає.
     */
    fun start(scope: CoroutineScope): Job? {
        if (!port.isConfigured) return null
        return scope.launch {
            launch { layers.collect { snapshot -> capture(snapshot) } }
            launch {
                while (isActive) {
                    runCatching { drainOutbox() }
                    runCatching { syncRegion() }
                    // Сокет заводиться з того самого тіку, а не з власного
                    // циклу: зайвий будильник раз на пʼять секунд — це батарея
                    // за затримку, яку оператор однаково не побачить.
                    runCatching { superviseStream() }
                    delay(tickMs)
                }
            }
        }
    }

    /**
     * Сокет живе рівно доти, доки відкрита мапа. Підписка — це збирання
     * холодного потоку, тож «закрити» її означає просто скасувати збирача:
     * ні wakelock, ні служби переднього плану тут не зʼявиться.
     */
    private fun CoroutineScope.superviseStream() {
        val plane = stream?.takeIf { it.isConfigured } ?: return
        val job = streamJob
        if (region() != null) {
            idleTicks = 0
            if (job == null || !job.isActive) {
                streamJob = launch { collectStream(plane) }
            }
            return
        }
        if (job == null) return
        // Регіон зникає не лише коли мапа закрилась, а й коли зум перейшов
        // за z12. Без відстрочки пінч навколо цієї межі рвав би й піднімав
        // сокет щотіку — тримаємо ще один тік, перш ніж визнати мапу закритою.
        if (++idleTicks < IDLE_TICKS_BEFORE_CLOSE) return
        job.cancel()
        streamJob = null
        bump { it.copy(joined = false) }
    }

    /**
     * Збирач сокета. Ловить усе, крім скасування, і саме в цьому його сенс.
     *
     * Ця корутина — дитина тіку. Виняток, випущений звідси, забрав би з собою
     * і опитування, і чергу, і знімки шару — НАЗАВЖДИ, бо площина зводиться
     * один раз за життя процесу й перезапуску не має. Сокет не має права
     * забрати звірку з собою: він лише прискорювач.
     */
    private suspend fun collectStream(plane: LiveMarksStreamPort) {
        try {
            plane.stream().collect { event ->
                try {
                    absorb(event)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.w(TAG, "подія сокета не злилась: ${e.javaClass.simpleName}")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "збирач сокета впав: ${e.javaClass.simpleName}")
            bump { it.copy(joined = false, lastLagMs = null) }
        }
    }

    /**
     * Правка свідчень. Через одні двері й від ПОТОЧНОГО значення: `joined`
     * пишуть дві корутини, а знімок, узятий до `suspend`, повернув би сюди
     * вже неправдиве «підписка є» — і звірка сповільнилась би на мертвому
     * сокеті.
     */
    @Synchronized
    private fun bump(edit: (LiveStreamStats) -> LiveStreamStats) {
        streamStats = edit(streamStats)
    }

    /** Обробляє подію сокета. Мовчазна: жодна з гілок не доходить до ока. */
    internal suspend fun absorb(event: LiveStreamEvent) {
        when (event) {
            is LiveStreamEvent.Connected -> bump { it.copy(joined = true) }
            // Затримка гасне разом із підпискою: лишити останнє число означало б
            // показувати свіжий замір на мертвому сокеті.
            is LiveStreamEvent.Dropped -> bump { it.copy(joined = false, lastLagMs = null) }
            is LiveStreamEvent.Inserted -> ingest(event.mark, event.receivedAtMs)
        }
    }

    private suspend fun ingest(mark: LiveMark, receivedAtMs: Long) {
        val lag = receivedAtMs - mark.createdAtMs
        val where = region()
        if (where == null || !covers(where, mark)) {
            bump { it.copy(received = it.received + 1, outOfRegion = it.outOfRegion + 1) }
            return
        }
        // Курсор опитування свідомо НЕ рухається: сокет бачив цю мітку, але
        // не бачив тих, що впали повз нього. Зсунути курсор — це попросити
        // звірку пропустити саме ту прогалину, заради якої вона й існує.
        val merged = mergeRemote(listOf(mark), clockMs())
        bump {
            it.copy(received = it.received + 1, merged = it.merged + merged, lastLagMs = lag)
        }
        // Свідчення воріт. Без координат: у журнал іде клітинка ≈1.2 км.
        Log.i(TAG, "realtime ← ${mark.kind} lag=${lag}ms cell=${mark.geohash6} merged=$merged")
    }

    /**
     * Чи лежить мітка в тому районі, який зараз дивляться.
     *
     * Серверний фільтр `postgres_changes` уміє рівність колонки, а район —
     * це префікс геохешу; виразити його там нічим. Тож відсів робиться тут,
     * і рівно тими клітинками, якими питає опитування, — інакше сокет і
     * звірка розійшлися б у тому, що взагалі належить вʼюпорту.
     */
    private fun covers(where: LiveRegion, mark: LiveMark): Boolean {
        val cell = mark.geohash6.takeIf { it.isNotBlank() }
            ?: LiveGeohash.encode(mark.lat, mark.lon)
        if (cell.isEmpty()) return false
        return cellsFor(where).any { cell.startsWith(it) }
    }

    private fun cellsFor(where: LiveRegion): List<String> {
        cells?.takeIf { it.first == where }?.let { return it.second }
        val computed = LiveGeohash.regionCells(where.lat, where.lon, where.radiusM)
        cells = where to computed
        return computed
    }

    /**
     * Крок опитування зараз. Опитування не вимикається ніколи — воно тут
     * звіряльник, а не резерв: сокет може тримати зʼєднання і мовчати.
     */
    private fun pullIntervalNow(): Long = when {
        stream?.isConfigured != true -> pullIntervalMs
        streamStats.joined -> maxOf(pullIntervalMs, RELAXED_PULL_INTERVAL_MS)
        else -> minOf(pullIntervalMs, TIGHT_PULL_INTERVAL_MS)
    }

    /**
     * Ставить у чергу власні мітки з поточного стану шару. Викликається зі
     * знімка сховища, тому ловить обидві руки — і аркуш довгого натиску, і
     * `world.report`, — не знаючи про жодну з них.
     */
    suspend fun capture(snapshot: List<SpatialLayer>) {
        val now = clockMs()
        val layer = snapshot.firstOrNull { it.spec.kind == HAZARD_LAYER_KIND } ?: return
        // Термін мітки — це термін її шару, а не «дві години від зараз»:
        // інакше кожен перезапуск подовжував би чужим людям стару мітку.
        val expiresAt = layer.expiresAt ?: (now + HAZARD_MARK_DEFAULT_TTL_MIN * 60_000L)
        for (marker in layer.spec.markers) {
            if (marker.source != null) continue
            val kind = marker.label ?: marker.glyph ?: continue
            // Схема живого шару знає рівно три види. Решта («камера», «затор»)
            // лишається локальною — краще не синхронізувати, ніж підмінити вид.
            if (kind !in LiveMarksConfig.ALLOWED_KINDS) continue
            enqueue(kind, marker.lat, marker.lon, expiresAt)
        }
    }

    /** Кладе мітку в чергу; повтор тієї самої точки ігнорується. */
    suspend fun enqueue(kind: String, lat: Double, lon: Double, expiresAtMs: Long) {
        val key = markKey(kind, lat, lon)
        gate.withLock {
            if (key in settledKeys) return
            if (!queuedKeys.add(key)) return
            outbox.addLast(Pending(kind, lat, lon, expiresAtMs))
            // Черга в памʼяті: переживає втрату мережі, не переживає перезапуск.
            // Мітка, що не встигла поїхати до вбивства процесу, поїде з
            // наступного знімка шару — вона там ще лежить.
            while (outbox.size > MAX_OUTBOX) {
                outbox.removeFirst().let { queuedKeys.remove(markKey(it.kind, it.lat, it.lon)) }
            }
        }
    }

    /** Пробує відправити чергу. Повертає, скільки міток поїхало. */
    suspend fun drainOutbox(): Int {
        if (!port.isConfigured) return 0
        var sent = 0
        while (true) {
            val head = gate.withLock { outbox.firstOrNull() } ?: break
            val now = clockMs()
            if (head.expiresAtMs <= now) {
                // Прострочена мітка нікому не потрібна: сервер її й не прийме.
                settle(head)
                continue
            }
            if (now - head.lastAttemptAtMs < retryDelayFor(head.attempts)) break
            when (val result = port.push(LiveMarkDraft(head.kind, head.lat, head.lon, head.expiresAtMs))) {
                is GeoResult.Ok -> {
                    settle(head)
                    sent++
                }
                // Постійна відмова (вид поза схемою, мертвий ключ) — черга не
                // має битись об неї вічно; решта причин чекає наступного кола.
                is GeoResult.Unavailable -> if (isPermanent(result.reason)) {
                    settle(head)
                } else {
                    gate.withLock {
                        outbox.firstOrNull()?.takeIf { it === head }?.let {
                            it.attempts++
                            it.lastAttemptAtMs = now
                        }
                    }
                    break
                }
            }
        }
        return sent
    }

    /**
     * Читає регіон і зводить його з локальним шаром: гасить прострочені чужі
     * мітки, додає нові. Повертає, скільки чужих міток лягло на мапу.
     */
    suspend fun syncRegion(): Int {
        if (!port.isConfigured) return 0
        val now = clockMs()
        val where = region()
        // Гасіння чужих міток не залежить від мережі: мітка мусить згаснути
        // навіть тоді, коли сервер недосяжний і мапа закрита.
        pruneExpiredRemote(now)
        if (where == null) return 0
        if (now - lastPullAtMs < pullIntervalNow()) return 0
        val cells = cellsFor(where)
        if (cells.isEmpty()) return 0
        lastPullAtMs = now
        val rows = when (val result = port.pull(cells, lastSeenAtMs)) {
            is GeoResult.Unavailable -> return 0
            is GeoResult.Ok -> result.value
        }
        lastSeenAtMs = rows.maxOfOrNull { it.createdAtMs } ?: lastSeenAtMs
        return mergeRemote(rows, now)
    }

    /**
     * Кладе чужі мітки в шар. Спільне для обох шляхів — сокет і звірка мусять
     * зливати ОДНАКОВО, інакше «мітка вже є» залежало б від того, хто приніс.
     */
    private suspend fun mergeRemote(rows: List<LiveMark>, now: Long): Int {
        var landed = 0
        // Читання приходить аргументом: замок шару тримається через обидві
        // фази, тож мітка з пальця, що впала між читанням і записом, не гине.
        sink.mutateHazardLayer { existing ->
            val fresh = mutableListOf<SpatialMarker>()
            for (row in rows) {
                if (row.expiresAtMs <= now) continue
                // Старий клієнт чи чужий запис у сервер без нової перевірки виду.
                if (row.kind !in LiveMarksConfig.ALLOWED_KINDS) continue
                val near = { m: SpatialMarker -> hazardDistanceM(m.lat, m.lon, row.lat, row.lon) <= HAZARD_MERGE_RADIUS_M }
                // Своя мітка виграє в чужої на тій самій точці: інакше власний
                // звіт оператора мовчки перепідписався б «спільнота».
                if (existing.any(near) || fresh.any(near)) continue
                fresh += hazardMarkerOf(
                    HazardMarkInput(
                        kind = row.kind,
                        lat = row.lat,
                        lon = row.lon,
                        note = HAZARD_SOURCE_COMMUNITY,
                        source = HAZARD_SOURCE_COMMUNITY,
                    ),
                )
                remoteExpiry[markKey(row.kind, row.lat, row.lon)] = row.expiresAtMs
            }
            if (fresh.isEmpty()) return@mutateHazardLayer null
            rememberEarliestExpiry()
            landed = fresh.size
            val merged = existing + fresh
            merged to ttlMinFor(merged, now)
        }
        return landed
    }

    /** Прибирає чужі мітки, чий власний TTL минув. `true` — шар змінився. */
    private suspend fun pruneExpiredRemote(now: Long): Boolean {
        // Поки не згасла найближча мітка, чіпати сховище нічого: тік ходить
        // раз на пʼять секунд, а вхід у замок — це читання Room і розбір JSON.
        // Знімок, а не сама мапа: її чіпають дві корутини, і голий обхід тут
        // ловив би ConcurrentModificationException.
        if (now < earliestRemoteExpiryMs) return false
        var changed = false
        sink.mutateHazardLayer { markers ->
            val dead = remoteExpiry.filterValues { it <= now }.keys
            if (dead.isEmpty()) return@mutateHazardLayer null
            // Лічильник чистимо ДО будь-якого раннього виходу. Мітка могла піти
            // з шару іншим шляхом — і тоді запис лишався б тут навічно,
            // «найближче згасання» назавжди в минулому, а кожен тік дарма
            // ходив би в Room.
            remoteExpiry.keys.removeAll(dead)
            rememberEarliestExpiry()
            val alive = markers.filterNot { m ->
                m.source == HAZARD_SOURCE_COMMUNITY &&
                    markKey(m.label ?: m.glyph.orEmpty(), m.lat, m.lon) in dead
            }
            if (alive.size == markers.size) return@mutateHazardLayer null
            changed = true
            alive to ttlMinFor(alive, now)
        }
        return changed
    }

    /** Оновлює знімок найближчого згасання. Кличеться лише з-під замка шару. */
    private fun rememberEarliestExpiry() {
        earliestRemoteExpiryMs = remoteExpiry.values.minOrNull() ?: Long.MAX_VALUE
    }

    /** Життя шару — найдовше з життів його міток, але не довше стандартного. */
    private fun ttlMinFor(markers: List<SpatialMarker>, now: Long): Int {
        val remoteMax = markers
            .filter { it.source == HAZARD_SOURCE_COMMUNITY }
            .mapNotNull { remoteExpiry[markKey(it.label ?: it.glyph.orEmpty(), it.lat, it.lon)] }
            .maxOrNull()
        val hasLocal = markers.any { it.source == null }
        if (hasLocal || remoteMax == null) return HAZARD_MARK_DEFAULT_TTL_MIN
        val minutes = ceil((remoteMax - now) / 60_000.0).toInt()
        return minutes.coerceIn(5, HAZARD_MARK_DEFAULT_TTL_MIN)
    }

    private suspend fun settle(head: Pending) {
        gate.withLock {
            outbox.firstOrNull()?.takeIf { it === head }?.let { outbox.removeFirst() }
            val key = markKey(head.kind, head.lat, head.lon)
            queuedKeys.remove(key)
            settledKeys.add(key)
            while (settledKeys.size > MAX_SETTLED) settledKeys.iterator().let { it.next(); it.remove() }
        }
    }

    private fun retryDelayFor(attempts: Int): Long =
        if (attempts <= 0) 0L else minOf(BASE_RETRY_MS shl minOf(attempts - 1, 5), MAX_RETRY_MS)

    private fun isPermanent(reason: SourceUnavailable): Boolean = when (reason) {
        is SourceUnavailable.RequestRejected -> true
        is SourceUnavailable.MissingApiKey -> true
        is SourceUnavailable.InvalidApiKey -> true
        is SourceUnavailable.HttpError -> reason.code in 400..499 && reason.code != 429
        else -> false
    }

    private class Pending(
        val kind: String,
        val lat: Double,
        val lon: Double,
        val expiresAtMs: Long,
        var attempts: Int = 0,
        var lastAttemptAtMs: Long = 0L,
    )

    companion object {
        /** Не частіше ніж раз на пів хвилини — мітки не міняються посекундно. */
        const val DEFAULT_PULL_INTERVAL_MS: Long = 30_000L

        /**
         * Крок звірки, поки сокет тримає підписку. Не «вимкнено»: сокет уміє
         * мовчати, тримаючись, і тоді єдиний, хто помітить прогалину, — саме
         * ця хвилина.
         */
        const val RELAXED_PULL_INTERVAL_MS: Long = 60_000L

        /**
         * Крок звірки без сокета. Дванадцять секунд, а не десять: обмежувач
         * клієнта пускає читання раз на десять, і рівний крок бився б об нього
         * приблизно щоразу.
         */
        const val TIGHT_PULL_INTERVAL_MS: Long = 12_000L

        /** Скільки тіків без регіону терпіти, перш ніж рвати сокет. */
        const val IDLE_TICKS_BEFORE_CLOSE: Int = 2

        /** Крок циклу: черга має відповідати швидко, регіон — рідко. */
        const val DEFAULT_TICK_MS: Long = 5_000L

        const val MAX_OUTBOX: Int = 64

        /** Скільки відправлених міток памʼятаємо, щоб не слати їх по колу. */
        const val MAX_SETTLED: Int = 256

        private const val BASE_RETRY_MS = 5_000L
        private const val MAX_RETRY_MS = 5L * 60 * 1000
        private const val TAG = "LivePlane"

        /** Ключ точки з точністю ~11 см — це та сама мітка, а не сусідня. */
        internal fun markKey(kind: String, lat: Double, lon: Double): String =
            "$kind@${String.format(java.util.Locale.ROOT, "%.6f,%.6f", lat, lon)}"
    }
}
