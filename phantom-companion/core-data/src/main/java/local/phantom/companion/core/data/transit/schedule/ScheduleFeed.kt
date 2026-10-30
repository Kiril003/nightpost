package local.phantom.companion.core.data.transit.schedule

import local.phantom.companion.core.data.transit.FeedBounds
import local.phantom.companion.core.data.transit.TransitTimesOrigin
import local.phantom.companion.core.data.transit.gtfs.GtfsStaticParser
import local.phantom.companion.core.data.transit.raptor.RawStop
import local.phantom.companion.core.data.transit.raptor.RawTrip
import local.phantom.companion.core.data.transit.raptor.TransitMode
import java.io.InputStream
import java.util.Calendar

/** Одне відправлення, яке ОПЕРАТОР пообіцяв — не наслідок інтервалу. */
data class ScheduledDeparture(
    val routeLabel: String,
    val mode: TransitMode,
    /** 0xRRGGBB, або -1 коли фід кольору не оголосив. */
    val colour: Int,
    /** Кінцева цього рейсу — «куди», без якого номер маршруту нічого не каже. */
    val towards: String,
    /** Секунди від місцевої півночі; може перевищувати 86 400 (нічні рейси). */
    val departureSec: Int,
    val inSeconds: Int,
    val origin: TransitTimesOrigin = TransitTimesOrigin.SCHEDULED,
)

/**
 * Опублікований розклад міста, розібраний із GTFS перевізника.
 *
 * Це заміна ЧАСУ, не геометрії. Траса, якою маршрут фізично їде, лишається
 * за OSM (`SurfaceFeed`): українські GTFS або не мають `shapes.txt`, або
 * мають вироджений — київський віддає 2 160 форм із медіаною у ДВІ точки,
 * тобто прямі між зупинками. Звідси поділ: лінію малює OSM, хвилини каже
 * перевізник, і жодна з двох половин не вигадує чужу.
 *
 * [RawTrip.timesAreModelled] тут завжди false — це і є вся суть переходу.
 */
class ScheduleFeed(
    val city: String,
    val attribution: String,
    val feedVersion: String?,
    val stops: List<RawStop>,
    val trips: List<RawTrip>,
    val transfers: List<Triple<String, String, Int>>,
) {
    val bounds: FeedBounds? = FeedBounds.around(stops.map { it.lat to it.lon })

    private val stopNameById: Map<String, String> = stops.associate { it.id to it.name }

    /**
     * Табло будується при першому запиті, не при розборі: у Києві це 103 тисячі
     * викликів, і платити за індекс на старті застосунку має тільки той, хто
     * справді відкрив зупинку.
     *
     * Плоскі масиви замість обʼєктів свідомо — сто тисяч `data class` на
     * телефоні коштують мегабайти, які нікому не потрібні після бінарного
     * пошуку.
     */
    private val board: Board by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { buildBoard() }

    /** Чи цей розклад узагалі знає таку зупинку. */
    fun knowsStop(stopId: String): Boolean = board.byStop.containsKey(stopId)

    /** Найближчі зупинки розкладу до точки, в межах [radiusM]. */
    fun stopsNear(lat: Double, lon: Double, radiusM: Double): List<RawStop> =
        stops.filter { haversineM(lat, lon, it.lat, it.lon) <= radiusM }

    /**
     * Наступні відправлення з [stopId] після [nowSec] — по одному найранішому
     * на маршрут, бо людині на зупинці потрібно «що приїде наступним», а не
     * десять рядків одного номера.
     *
     * Останній виклик рейсу свідомо не відправлення: сісти там нікуди.
     */
    fun departuresAt(stopId: String, nowSec: Int, limit: Int = 6): List<ScheduledDeparture> {
        val calls = board.byStop[stopId] ?: return emptyList()
        var lo = 0
        var hi = calls.size - 1
        var first = calls.size
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (board.depSec[calls[mid]] >= nowSec) { first = mid; hi = mid - 1 } else lo = mid + 1
        }
        val out = ArrayList<ScheduledDeparture>(limit)
        val seen = HashSet<String>(limit * 2)
        var i = first
        while (i < calls.size && out.size < limit) {
            val call = calls[i]
            val trip = board.tripOf[call]
            val label = board.label[trip]
            val towards = board.towards[trip]
            if (seen.add("$label$towards")) {
                out += ScheduledDeparture(
                    routeLabel = label,
                    mode = board.mode[trip],
                    colour = board.colour[trip],
                    towards = towards,
                    departureSec = board.depSec[call],
                    inSeconds = board.depSec[call] - nowSec,
                )
            }
            i++
        }
        return out
    }

    private fun buildBoard(): Board {
        var callCount = 0
        for (t in trips) if (t.calls.size >= 2) callCount += t.calls.size - 1

        val depSec = IntArray(callCount)
        val tripOf = IntArray(callCount)
        val stopOf = arrayOfNulls<String>(callCount)
        val label = Array(trips.size) { "" }
        val towards = Array(trips.size) { "" }
        val mode = Array(trips.size) { TransitMode.OTHER }
        val colour = IntArray(trips.size) { -1 }

        var c = 0
        for (ti in trips.indices) {
            val t = trips[ti]
            label[ti] = t.routeLabel
            mode[ti] = t.mode
            colour[ti] = t.colour
            if (t.calls.size < 2) continue
            val lastId = t.calls.last().stopId
            towards[ti] = stopNameById[lastId] ?: lastId
            for (k in 0 until t.calls.size - 1) {
                depSec[c] = t.calls[k].departureSec
                tripOf[c] = ti
                stopOf[c] = t.calls[k].stopId
                c++
            }
        }

        val grouped = HashMap<String, MutableList<Int>>(stops.size * 2)
        for (i in 0 until callCount) {
            grouped.getOrPut(stopOf[i]!!) { ArrayList(8) } += i
        }
        val byStop = HashMap<String, IntArray>(grouped.size * 2)
        for ((sid, list) in grouped) {
            list.sortBy { depSec[it] }
            byStop[sid] = list.toIntArray()
        }
        return Board(depSec, tripOf, label, towards, mode, colour, byStop)
    }

    private class Board(
        val depSec: IntArray,
        val tripOf: IntArray,
        val label: Array<String>,
        val towards: Array<String>,
        val mode: Array<TransitMode>,
        val colour: IntArray,
        val byStop: Map<String, IntArray>,
    )

    companion object {

        /**
         * @param serviceDate `yyyyMMdd`. Розклад без дня — не розклад:
         *   недільний трамвай не понеділковий.
         */
        fun from(
            city: String,
            attribution: String,
            zip: InputStream,
            serviceDate: String = today(),
            weekday: Int = weekdayNow(),
        ): ScheduleFeed {
            val feed = GtfsStaticParser.parse(zip, serviceDate, weekday)
            return ScheduleFeed(
                city = city,
                attribution = attribution,
                feedVersion = feed.feedVersion,
                stops = feed.stops,
                trips = feed.trips,
                transfers = feed.transfers,
            )
        }

        fun today(cal: Calendar = Calendar.getInstance()): String = "%04d%02d%02d".format(
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH),
        )

        /** 1=понеділок … 7=неділя, як у стовпцях `calendar.txt`. */
        fun weekdayNow(cal: Calendar = Calendar.getInstance()): Int =
            when (cal.get(Calendar.DAY_OF_WEEK)) {
                Calendar.MONDAY -> 1
                Calendar.TUESDAY -> 2
                Calendar.WEDNESDAY -> 3
                Calendar.THURSDAY -> 4
                Calendar.FRIDAY -> 5
                Calendar.SATURDAY -> 6
                else -> 7
            }
    }
}

private fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
        kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
        kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
    return 2 * r * kotlin.math.asin(kotlin.math.sqrt(a.coerceIn(0.0, 1.0)))
}
