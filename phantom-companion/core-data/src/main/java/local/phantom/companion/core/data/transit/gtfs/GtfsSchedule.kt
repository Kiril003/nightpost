package local.phantom.companion.core.data.transit.gtfs

import local.phantom.companion.core.data.transit.FeedBounds
import local.phantom.companion.core.data.transit.raptor.Timetable
import local.phantom.companion.core.data.transit.raptor.TimetableBuilder
import local.phantom.companion.core.data.transit.raptor.TransitMode

/**
 * Один ОПУБЛІКОВАНИЙ розклад міста, доведений до [Timetable].
 *
 * Це те, чого бракувало: [GtfsStaticParser] існував і не мав жодного
 * виклику в бойовому коді, [TimetableBuilder] будувався в [AppContainer] і
 * чекав на дані, яких ніхто не приносив. Клас нижче — і є та ланка.
 *
 * Часи тут — РЕАЛЬНІ рядки `stop_times.txt` міського перевізника, не
 * інтервальна модель. [Timetable.routeTimesModelled] на кожному маршруті
 * звідси `false`, і саме за цим прапором споживач відрізняє «о 22:14»
 * від «десь раз на 18 хвилин».
 */
class GtfsSchedule(
    val timetable: Timetable,
    /** Місто, як його називає пак — іде в підпис джерела. */
    val city: String,
    /** `feed_version` з `feed_info.txt`, або null — фід не назвався. */
    val feedVersion: String?,
    /** День служби, який витягнуто з архіву: `yyyyMMdd`. */
    val serviceDate: String,
    val fetchedAtEpochMs: Long,
    /** Хто опублікував і під якою ліцензією — це має бути видно людині. */
    val attribution: String,
    /** route_id → номер з таблички; живий фід знає лише route_id. */
    val routeLabels: Map<String, String> = emptyMap(),
) {

    val bounds: FeedBounds? = FeedBounds.around(
        (0 until timetable.stopCount).map { timetable.stopLat[it] to timetable.stopLon[it] },
    )

    /** Скільки рейсів цей розклад знає на [serviceDate]. Нуль — розкладу нема. */
    val tripCount: Int = timetable.routeTripCount.sum()

    /**
     * Розклад протух, коли його день служби — не сьогоднішній.
     *
     * Київський архів перегенеровується ЩОДОБИ (виміряно: `feed_version`
     * дорівнює даті збірки), і половина його `calendar.txt` має
     * `end_date` = день викладення. Запечений в APK знімок був би
     * неправдою вже назавтра — тому фід тягнеться онлайн, а це питання
     * задається перед кожним показом.
     */
    fun isStaleFor(todayYmd: String): Boolean = serviceDate != todayYmd

    /**
     * НАСТУПНІ ВІДПРАВЛЕННЯ біля точки — те, що людина бачить у досьє.
     *
     * @param nowSec секунди від локальної півночі; може перевищувати 86 400
     *   рівно так само, як і самі часи GTFS — нічні рейси живуть там.
     * @param perRoute скільки найближчих рейсів брати з кожного маршруту.
     */
    fun departuresNear(
        lat: Double,
        lon: Double,
        radiusM: Int,
        nowSec: Int,
        perRoute: Int = 3,
    ): List<ScheduledDeparture> {
        val out = ArrayList<ScheduledDeparture>(32)
        for (s in 0 until timetable.stopCount) {
            val d = TimetableBuilder.haversine(lat, lon, timetable.stopLat[s], timetable.stopLon[s])
            if (d > radiusM) continue
            val from = timetable.stopRoutesOffset[s]
            val to = timetable.stopRoutesOffset[s + 1]
            for (i in from until to) {
                val route = timetable.stopRoutes[i]
                val pos = timetable.stopRoutePos[i]
                // Кінцева зупинка маршруту — сісти на неї не можна, і
                // «відправлення» там було б часом, якого ніхто не дочекається.
                if (pos >= timetable.routeStopCount[route] - 1) continue
                var trip = timetable.earliestTrip(route, pos, nowSec)
                if (trip < 0) continue
                var taken = 0
                while (taken < perRoute && trip < timetable.routeTripCount[route]) {
                    out += ScheduledDeparture(
                        routeLabel = timetable.routeLabel[route],
                        mode = timetable.routeMode[route],
                        colour = timetable.routeColour[route].takeIf { it >= 0 },
                        stopId = timetable.stopId[s],
                        stopName = timetable.stopName[s],
                        departureSec = timetable.departure(route, trip, pos),
                        terminusName = terminusOf(route),
                        distanceMeters = d,
                    )
                    trip++
                    taken++
                }
            }
        }
        out.sortBy { it.departureSec }
        return out
    }

    /**
     * Те саме, що [departuresNear], але згорнуте в ЛІНІЇ — форма, якою
     * досьє показує транспорт: маршрут, його зупинки по порядку, де в
     * цьому ланцюгу стоїть людина, і найближчі рейси.
     *
     * Потрібне окремо, бо інакше розклад умів би лише дописати хвилини до
     * ліній, які випадково знайшлись в OSM-моделі. Маршрут, якого модель
     * не знає, лишався б невидимим попри те, що місто його опублікувало.
     */
    fun linesNear(
        lat: Double,
        lon: Double,
        radiusM: Int,
        nowSec: Int,
        perRoute: Int = 3,
    ): List<ScheduledLine> {
        val out = ArrayList<ScheduledLine>(16)
        for (s in 0 until timetable.stopCount) {
            val d = TimetableBuilder.haversine(lat, lon, timetable.stopLat[s], timetable.stopLon[s])
            if (d > radiusM) continue
            val from = timetable.stopRoutesOffset[s]
            val to = timetable.stopRoutesOffset[s + 1]
            for (i in from until to) {
                val route = timetable.stopRoutes[i]
                val pos = timetable.stopRoutePos[i]
                val len = timetable.routeStopCount[route]
                if (pos >= len - 1) continue
                var trip = timetable.earliestTrip(route, pos, nowSec)
                if (trip < 0) continue
                val times = ArrayList<Int>(perRoute)
                while (times.size < perRoute && trip < timetable.routeTripCount[route]) {
                    times += timetable.departure(route, trip, pos)
                    trip++
                }
                out += ScheduledLine(
                    routeLabel = timetable.routeLabel[route],
                    mode = timetable.routeMode[route],
                    colour = timetable.routeColour[route].takeIf { it >= 0 },
                    boardStopId = timetable.stopId[s],
                    boardStopName = timetable.stopName[s],
                    boardIndex = pos,
                    terminusName = terminusOf(route),
                    departureSecs = times,
                    stops = List(len) { p ->
                        val idx = timetable.stopAt(route, p)
                        ScheduledLineStop(
                            id = timetable.stopId[idx],
                            name = timetable.stopName[idx],
                            // Секунди від посадки — «через скільки я там буду».
                            secondsFromBoard = timetable.arrival(route, 0, p) -
                                timetable.departure(route, 0, pos),
                        )
                    },
                )
            }
        }
        // Один маршрут може торкатись кількох зупинок у радіусі (і мати
        // кілька RAPTOR-варіантів). Людині потрібен найближчий рейс, тож
        // на (маршрут, вид) лишається той варіант, що їде раніше.
        return out
            .groupBy { it.routeLabel to it.mode }
            .values
            .mapNotNull { group -> group.minByOrNull { it.departureSecs.firstOrNull() ?: Int.MAX_VALUE } }
            .sortedBy { it.departureSecs.firstOrNull() ?: Int.MAX_VALUE }
    }

    private fun terminusOf(route: Int): String? {
        val last = timetable.routeStopCount[route] - 1
        if (last < 0) return null
        return timetable.stopName[timetable.stopAt(route, last)].takeIf { it.isNotBlank() }
    }

    companion object {

        /**
         * Будує розклад із архіву, або відмовляється з причиною.
         *
         * ПЕРЕВІРКА ГЛУЗДУ ОБОВʼЯЗКОВА. HTTP 200 і валідний zip не означають
         * придатних даних: у виміряному раніше фіді Дніпра 53 % координат
         * лежали поза межами міста. Тому архів, у якому менш ніж
         * [MIN_INSIDE_RATIO] зупинок потрапляють у рамку пака, відхиляється
         * цілком — краще чесно нічого, ніж мапа з зупинками в Атлантиці.
         */
        fun build(
            feed: GtfsFeed,
            city: String,
            serviceDate: String,
            attribution: String,
            expected: FeedBounds?,
            fetchedAtEpochMs: Long,
        ): Build {
            if (feed.stops.isEmpty()) return Build.Rejected("архів не має жодної зупинки")
            if (feed.trips.isEmpty()) {
                return Build.Rejected("на $serviceDate архів не має жодного рейсу")
            }
            if (expected != null) {
                val inside = feed.stops.count { expected.contains(it.lat, it.lon) }
                val ratio = inside.toDouble() / feed.stops.size
                if (ratio < MIN_INSIDE_RATIO) {
                    return Build.Rejected(
                        "лише ${(ratio * 100).toInt()} % зупинок у межах міста — фід не про це місце",
                    )
                }
            }
            val timetable = runCatching {
                TimetableBuilder.build(
                    stops = feed.stops,
                    trips = feed.trips,
                    extraTransfers = feed.transfers,
                )
            }.getOrElse { return Build.Rejected("розклад не збирається: ${it.message}") }

            return Build.Ready(
                GtfsSchedule(
                    timetable = timetable,
                    city = city,
                    feedVersion = feed.feedVersion,
                    serviceDate = serviceDate,
                    fetchedAtEpochMs = fetchedAtEpochMs,
                    attribution = attribution,
                    routeLabels = feed.routeLabels,
                ),
            )
        }

        /**
         * 80 %. Не 100: фіди законно несуть кілька приміських кінцевих за
         * адмінмежею, і різати їх було б власною неправдою. Але 53 % —
         * це вже не околиця, це інші дані.
         */
        const val MIN_INSIDE_RATIO = 0.80

        sealed interface Build {
            data class Ready(val schedule: GtfsSchedule) : Build
            data class Rejected(val reason: String) : Build
        }
    }
}

/** Одна зупинка в ланцюгу маршруту, як його бачить людина з місця посадки. */
data class ScheduledLineStop(
    val id: String,
    val name: String,
    /** Відʼємне для зупинок, які маршрут уже проїхав. */
    val secondsFromBoard: Int,
)

/** Маршрут біля людини: куди їде, коли найближчі рейси, і через що. */
data class ScheduledLine(
    val routeLabel: String,
    val mode: TransitMode,
    val colour: Int?,
    val boardStopId: String,
    val boardStopName: String,
    /** Індекс зупинки посадки в [stops]. */
    val boardIndex: Int,
    val terminusName: String?,
    /** Секунди від локальної півночі; порожньо — сьогодні вже не поїде. */
    val departureSecs: List<Int>,
    val stops: List<ScheduledLineStop>,
)

/** Один рейс, що відправляється з конкретної зупинки в конкретну секунду. */
data class ScheduledDeparture(
    val routeLabel: String,
    val mode: TransitMode,
    /** 0xRRGGBB з `routes.txt`, або null — фід кольору не оголосив. */
    val colour: Int?,
    val stopId: String,
    val stopName: String,
    /** Секунди від локальної півночі дня служби; може бути > 86 400. */
    val departureSec: Int,
    val terminusName: String?,
    val distanceMeters: Double,
) {
    /** Скільки чекати від [nowSec], у хвилинах, ніколи менше однієї. */
    fun minutesFrom(nowSec: Int): Int = maxOf(1, ((departureSec - nowSec) + 30) / 60)
}
