package local.phantom.companion.core.data.transit.gtfs

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import local.phantom.companion.core.data.transit.GtfsScheduleFeed
import local.phantom.companion.core.net.transit.GtfsStaticClient
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Calendar

/**
 * ATLAS-3 — доставка реального розкладу: мережа → [GtfsStaticParser] →
 * [GtfsSchedule], рівно раз на день служби, з диском як подушкою.
 *
 * ЧОМУ ОНЛАЙН, А НЕ В APK. Київський архів носить `feed_version`, що
 * дорівнює даті збірки, і перегенеровується щодоби; частина його служб
 * має `end_date` = сьогодні. Запечений знімок був би точним рівно в день
 * релізу й тихо неправдивим усі наступні — а тихо неправдивий розклад
 * гірший за відсутній, бо на нього спираються.
 *
 * ЧОМУ ДИСК. Мережа в полі — розкіш. Вчорашній архів на диску дозволяє
 * сказати «розклад за вчора» (і це правда, яку видно), тоді як порожнеча
 * дозволяє сказати лише «нічого». Але вчорашній НІКОЛИ не видає себе за
 * сьогоднішній: [GtfsSchedule.isStaleFor] лишається `true`, і споживач
 * зобовʼязаний це показати.
 *
 * ЧОГО ТУТ НЕМА НАВМИСНО: гілки «не вийшло — покажи модель як розклад».
 * Провал мережі дає [State.Unavailable] з причиною. Інтервальна модель
 * лишається собою — окремим, названим ярусом, — і не підміняє часи.
 */
class GtfsScheduleStore(
    private val client: GtfsStaticClient,
    /** Куди класти архів. Null — працюємо без диска (тести, перший запуск). */
    private val cacheDir: File?,
    private val now: () -> Calendar = { Calendar.getInstance() },
) {

    sealed interface State {
        /** Ще не питали / питаємо саме зараз. */
        data object Idle : State

        data class Ready(val schedule: GtfsSchedule) : State

        /**
         * Розкладу нема, і ось чому. [reason] іде людині дослівно —
         * «не вдалось дістати розклад: …», ніколи вигадані хвилини.
         */
        data class Unavailable(val city: String, val reason: String) : State
    }

    private val lock = Mutex()

    @Volatile
    private var state: State = State.Idle

    @Volatile
    private var loadedKey: String? = null

    fun current(): State = state

    /** Готовий розклад, якщо він є і саме на сьогодні; інакше null. */
    fun freshSchedule(): GtfsSchedule? =
        (state as? State.Ready)?.schedule?.takeIf { !it.isStaleFor(serviceDate()) }

    /**
     * Забезпечує розклад для [feed] на сьогодні. Ідемпотентно: повторний
     * виклик того самого дня не чіпає мережу.
     */
    suspend fun ensure(feed: GtfsScheduleFeed): State = lock.withLock {
        val today = serviceDate()
        val key = "${feed.city}@$today"
        if (loadedKey == key && state is State.Ready) return@withLock state

        val cached = cacheDir?.let { File(it, cacheName(feed)) }
        // 1. Диск, якщо він уже несе СЬОГОДНІШНІЙ день служби.
        cached?.takeIf { it.isFile && it.length() > 0 }?.let { file ->
            buildFrom(file.readBytes(), feed, today)?.let { built ->
                if (built is State.Ready && !built.schedule.isStaleFor(today)) {
                    state = built
                    loadedKey = key
                    return@withLock built
                }
            }
        }

        // 2. Мережа.
        val fetched = client.fetch(feed.staticUrl)
        val bytes = when (fetched) {
            is GtfsStaticClient.Result.Ok -> fetched.bytes
            is GtfsStaticClient.Result.NotModified -> cached?.takeIf { it.isFile }?.readBytes()
            is GtfsStaticClient.Result.Failed -> null
        }
        if (bytes == null) {
            val why = (fetched as? GtfsStaticClient.Result.Failed)?.reason ?: "джерело не віддало архів"
            // Вчорашній кеш кращий за нічого — але лишається вчорашнім.
            val stale = cached?.takeIf { it.isFile }?.let { buildFrom(it.readBytes(), feed, today) }
            state = if (stale is State.Ready) stale else State.Unavailable(feed.city, why)
            loadedKey = null
            return@withLock state
        }

        val built = buildFrom(bytes, feed, today)
            ?: State.Unavailable(feed.city, "архів не читається як GTFS")
        if (built is State.Ready) {
            cached?.runCatching {
                parentFile?.mkdirs()
                writeBytes(bytes)
            }
            loadedKey = if (built.schedule.isStaleFor(today)) null else key
        } else {
            loadedKey = null
        }
        state = built
        built
    }

    private fun buildFrom(bytes: ByteArray, feed: GtfsScheduleFeed, today: String): State? {
        val parsed = runCatching {
            GtfsStaticParser.parse(ByteArrayInputStream(bytes), today, weekdayIso())
        }.getOrNull() ?: return null

        return when (
            val built = GtfsSchedule.build(
                feed = parsed,
                city = feed.city,
                serviceDate = today,
                attribution = feed.attribution,
                expected = feed.expectedBounds,
                fetchedAtEpochMs = System.currentTimeMillis(),
            )
        ) {
            is GtfsSchedule.Companion.Build.Ready -> State.Ready(built.schedule)
            is GtfsSchedule.Companion.Build.Rejected -> State.Unavailable(feed.city, built.reason)
        }
    }

    private fun cacheName(feed: GtfsScheduleFeed): String =
        "gtfs-" + feed.city.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-') + ".zip"

    /** `yyyyMMdd` локального дня — той самий формат, що й у `calendar.txt`. */
    private fun serviceDate(): String = now().let {
        "%04d%02d%02d".format(
            it.get(Calendar.YEAR),
            it.get(Calendar.MONTH) + 1,
            it.get(Calendar.DAY_OF_MONTH),
        )
    }

    /** 1=понеділок … 7=неділя, як їх нумерує [GtfsStaticParser]. */
    private fun weekdayIso(): Int = when (now().get(Calendar.DAY_OF_WEEK)) {
        Calendar.MONDAY -> 1
        Calendar.TUESDAY -> 2
        Calendar.WEDNESDAY -> 3
        Calendar.THURSDAY -> 4
        Calendar.FRIDAY -> 5
        Calendar.SATURDAY -> 6
        else -> 7
    }
}
