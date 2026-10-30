package local.phantom.companion.core.net.transit

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Один рейс, що відправляється від зупинки за РОЗКЛАДОМ.
 *
 * Розклад — не позиція. Ці записи живлять табло відправлень, картку лінії
 * й досьє зупинки; вони ніколи не стають рухомою міткою на склі (закон 1
 * D8: рухається лише те, що прийшло живим фідом позицій).
 *
 * [estimatedSecondOfDay] заповнюється лише тоді, коли Transitland віддав
 * прогноз реального часу для цього рейсу — саме за цим, а не за наявністю
 * запису, визначається [isRealtime].
 */
data class TransitDeparture(
    val stopOnestopId: String,
    val routeOnestopId: String?,
    val routeShortName: String?,
    val headsign: String?,
    val mode: TransitMode,
    /**
     * Секунда доби за розкладом. GTFS дозволяє значення > 86400 для
     * нічних рейсів «наступного дня» — воно зберігається як є, бо
     * обрізання перенесло б рейс 25:10 на початок тієї самої доби.
     */
    val scheduledSecondOfDay: Int?,
    val estimatedSecondOfDay: Int?,
    val tripId: String?,
) {
    val isRealtime: Boolean get() = estimatedSecondOfDay != null

    /** Час, який показуємо: прогноз, якщо він є, інакше розклад. */
    val effectiveSecondOfDay: Int? get() = estimatedSecondOfDay ?: scheduledSecondOfDay
}

/**
 * `HH:MM:SS` → секунда доби. `null` на будь-якій непридатній формі:
 * зіпсований рядок краще пропустити, ніж показати відправлення о 00:00.
 */
internal fun parseGtfsClock(raw: String?): Int? {
    val parts = raw?.trim()?.split(':') ?: return null
    if (parts.size < 2) return null
    val h = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    val s = if (parts.size > 2) parts[2].toIntOrNull() ?: return null else 0
    if (h < 0 || m !in 0..59 || s !in 0..59) return null
    return h * 3600 + m * 60 + s
}

@Serializable
internal data class DeparturesEnvelope(val stops: List<DepartureStopDto>? = null)

@Serializable
internal data class DepartureStopDto(
    @SerialName("onestop_id") val onestopId: String? = null,
    val departures: List<DepartureDto>? = null,
)

/**
 * Transitland віддавав час відправлення двома формами між мінорними
 * версіями: пласким `departure_time` і вкладеним `departure.scheduled`.
 * Приймаються обидві — інакше зміна версії API робить табло порожнім,
 * не подавши жодної ознаки збою.
 */
@Serializable
internal data class DepartureDto(
    @SerialName("departure_time") val departureTime: String? = null,
    @SerialName("arrival_time") val arrivalTime: String? = null,
    val departure: DepartureTimesDto? = null,
    @SerialName("stop_headsign") val stopHeadsign: String? = null,
    val trip: DepartureTripDto? = null,
) {
    fun toDomain(stopId: String): TransitDeparture? {
        val route = trip?.route
        val scheduled = parseGtfsClock(departure?.scheduled ?: departureTime ?: arrivalTime)
        val estimated = parseGtfsClock(departure?.estimated)
        if (scheduled == null && estimated == null) return null
        val long = route?.longName?.takeIf { it.isNotBlank() }
        val short = LineLabel.of(route?.shortName, null, route?.onestopId).ifEmpty { null }
        return TransitDeparture(
            stopOnestopId = stopId,
            routeOnestopId = route?.onestopId?.takeIf { it.isNotBlank() },
            routeShortName = short,
            headsign = listOfNotNull(stopHeadsign, trip?.tripHeadsign, long)
                .firstOrNull { it.isNotBlank() },
            mode = TransitModeHeuristic.classify(
                route?.routeType,
                short,
                long,
                route?.agency?.agencyName,
            ),
            scheduledSecondOfDay = scheduled,
            estimatedSecondOfDay = estimated,
            tripId = trip?.tripId?.takeIf { it.isNotBlank() },
        )
    }
}

@Serializable
internal data class DepartureTimesDto(
    val scheduled: String? = null,
    val estimated: String? = null,
)

@Serializable
internal data class DepartureTripDto(
    @SerialName("trip_id") val tripId: String? = null,
    @SerialName("trip_headsign") val tripHeadsign: String? = null,
    val route: DepartureRouteDto? = null,
)

@Serializable
internal data class DepartureRouteDto(
    @SerialName("onestop_id") val onestopId: String? = null,
    @SerialName("route_short_name") val shortName: String? = null,
    @SerialName("route_long_name") val longName: String? = null,
    @SerialName("route_type") val routeType: Int? = null,
    val agency: DepartureAgencyDto? = null,
)

@Serializable
internal data class DepartureAgencyDto(
    @SerialName("agency_name") val agencyName: String? = null,
)
