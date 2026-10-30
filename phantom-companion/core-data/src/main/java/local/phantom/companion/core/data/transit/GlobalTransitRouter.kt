package local.phantom.companion.core.data.transit

import local.phantom.companion.core.data.transit.raptor.TransitMode
import local.phantom.companion.core.net.transit.LineLabel
import local.phantom.companion.core.net.transit.TransitousTransitClient
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Calendar

/**
 * Universal Global Transit Planner that connects to open MOTIS / Transitous worldwide
 * schedule graph, enabling door-to-door public transit navigation anywhere on the globe.
 */
open class GlobalTransitRouter(
    private val transitous: TransitousTransitClient = TransitousTransitClient(),
) {

    /**
     * Три різні речі, які раніше були одним порожнім списком. Сказати «рейсів
     * немає», коли насправді мовчить мережа, — це заява про СВІТ замість
     * зізнання про себе.
     */
    sealed interface Outcome {
        data class Planned(val itineraries: List<Itinerary>) : Outcome

        /** Дріт не відповів: мережі немає, сервер віддав помилку чи сміття. */
        object Unreachable : Outcome

        /** Дріт відповів, і в цьому місці о цій годині рейсів справді нема. */
        object NoService : Outcome
    }

    open suspend fun planTransit(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        departSec: Int? = null,
    ): Outcome {
        val now = Calendar.getInstance()
        val nowSec = now.get(Calendar.HOUR_OF_DAY) * 3600 +
            now.get(Calendar.MINUTE) * 60 +
            now.get(Calendar.SECOND)
        val depart = departSec ?: nowSec

        val timeStr = String.format("%02d:%02d", (depart / 3600) % 24, (depart % 3600) / 60)

        // Дата мусить слухатись departSec, інакше «виїхати о 8:00», обране
        // ввечері, їхало запитом у СЬОГОДНІШНІЙ ранок — у минуле.
        val dayShift = when {
            depart >= DAY_SEC -> depart / DAY_SEC
            departSec != null && depart < nowSec -> 1
            else -> 0
        }
        val dateCal = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, dayShift) }
        val dateStr = String.format(
            "%04d-%02d-%02d",
            dateCal.get(Calendar.YEAR),
            dateCal.get(Calendar.MONTH) + 1,
            dateCal.get(Calendar.DAY_OF_MONTH),
        )

        val plan = transitous.plan(
            fromLat = fromLat,
            fromLon = fromLon,
            toLat = toLat,
            toLon = toLon,
            departTime = timeStr,
            departDate = dateStr,
            numItineraries = 3,
        ) ?: return Outcome.Unreachable

        val itineraries = plan.itineraries.mapNotNull { itDto ->
            mapToItinerary(itDto, departSec)
        }
        return if (itineraries.isEmpty()) Outcome.NoService else Outcome.Planned(itineraries)
    }

    private fun mapToItinerary(
        itDto: TransitousTransitClient.Itinerary,
        requestedDepartSec: Int?,
    ): Itinerary? {
        if (itDto.legs.isEmpty()) return null

        // Якір — момент, від якого відміряються всі ноги. Він же дає секунди
        // від опівночі; різниці рахуються в епосі, тож поїздка через північ
        // росте далі за 86400, як і обіцяє Itinerary.arriveSec.
        val anchorIso = itDto.startTimeIso ?: itDto.legs.first().startTimeIso
        val anchorEpoch = parseIsoToEpoch(anchorIso)

        val baseDepartSec = parseIsoToSecondsSinceMidnight(anchorIso)
            ?: requestedDepartSec
            ?: (Calendar.getInstance().let {
                it.get(Calendar.HOUR_OF_DAY) * 3600 + it.get(Calendar.MINUTE) * 60 + it.get(Calendar.SECOND)
            })

        var currentSec = baseDepartSec
        val legs = mutableListOf<Itinerary.Leg>()
        var anyModelled = false

        for (legDto in itDto.legs) {
            val legDuration = legDto.durationSeconds.coerceAtLeast(0)
            val legStartEpoch = parseIsoToEpoch(legDto.startTimeIso)
            val legEndEpoch = parseIsoToEpoch(legDto.endTimeIso)

            // Ніс-у-хвіст — це і був корінь брехні: час чекання на зупинці
            // структурно дорівнював нулю. Сума тривалостей ніг НЕ дорівнює
            // поїздці; різниця — це пауза, і вона є тільки в абсолютних часах.
            val legDepart = if (anchorEpoch != null && legStartEpoch != null) {
                baseDepartSec + (legStartEpoch - anchorEpoch).toInt()
            } else {
                null
            }
            val legArrive = if (legDepart != null && anchorEpoch != null && legEndEpoch != null) {
                baseDepartSec + (legEndEpoch - anchorEpoch).toInt()
            } else {
                (legDepart ?: currentSec) + legDuration
            }
            val timed = legDepart != null
            val departAt = legDepart ?: currentSec

            if (legDto.mode.equals("WALK", ignoreCase = true)) {
                legs += Itinerary.Leg.Access(
                    mode = AccessMode.WALK,
                    fromLat = legDto.fromLat,
                    fromLon = legDto.fromLon,
                    toLat = legDto.toLat,
                    toLon = legDto.toLon,
                    toStopName = legDto.toName,
                    seconds = legDuration,
                    meters = legDto.distanceMeters.toInt().coerceAtLeast(1),
                    points = legDto.points,
                    estimated = false,
                )
            } else {
                val mode = mapTransitMode(legDto.mode)
                val colour = parseColor(legDto.routeColor, mode)
                val label = LineLabel.of(legDto.routeShortName, legDto.routeLongName)

                if (!timed) anyModelled = true
                legs += Itinerary.Leg.Ride(
                    routeLabel = label,
                    mode = mode,
                    colour = colour,
                    fromStopName = legDto.fromName,
                    toStopName = legDto.toName,
                    fromLat = legDto.fromLat,
                    fromLon = legDto.fromLon,
                    toLat = legDto.toLat,
                    toLon = legDto.toLon,
                    departSec = departAt,
                    arriveSec = legArrive,
                    intermediateStops = legDto.intermediateStopCount ?: 0,
                    points = legDto.points,
                    // Часу в дроті нема — значить він наш, вигаданий складанням
                    // тривалостей. UI зобовʼязаний поставити «≈».
                    timesAreModelled = !timed,
                    headsign = legDto.headsign,
                    boardPlatform = legDto.fromTrack,
                    alightPlatform = legDto.toTrack,
                    stops = legDto.stops.map { call ->
                        val at = parseIsoToEpoch(call.arrivalIso ?: call.departureIso)
                        Itinerary.Leg.RideStop(
                            name = call.name,
                            lat = call.lat,
                            lon = call.lon,
                            arriveSec = if (at != null && anchorEpoch != null) baseDepartSec + (at - anchorEpoch).toInt() else null,
                        )
                    },
                    tripRef = legDto.tripId,
                    operatorName = legDto.agencyName,
                    liveDelaySec = if (legDto.realTime) {
                        val planned = parseIsoToEpoch(legDto.scheduledStartIso)
                        if (planned != null && legStartEpoch != null) (legStartEpoch - planned).toInt() else null
                    } else null,
                )
            }
            currentSec = legArrive
        }

        val endEpoch = parseIsoToEpoch(itDto.endTimeIso)
        val arriveSec = if (anchorEpoch != null && endEpoch != null) {
            baseDepartSec + (endEpoch - anchorEpoch).toInt()
        } else {
            baseDepartSec + (currentSec - baseDepartSec).coerceAtLeast(itDto.durationSeconds)
        }

        return Itinerary(
            legs = legs,
            departSec = baseDepartSec,
            arriveSec = arriveSec,
            transfers = itDto.transfers,
            hasModelledTimes = anyModelled,
        )
    }

    private fun mapTransitMode(raw: String): TransitMode = when (raw.uppercase()) {
        "SUBWAY", "METRO", "UNDERGROUND" -> TransitMode.METRO
        "TRAM", "LIGHT_RAIL" -> TransitMode.TRAM
        "BUS", "MINIBUS" -> TransitMode.BUS
        "TROLLEYBUS" -> TransitMode.TROLLEYBUS
        "COACH" -> TransitMode.BUS
        "RAIL", "TRAIN", "HIGHSPEED_RAIL", "REGIONAL_RAIL", "REGIONAL_FAST_RAIL", "SUBURBAN_RAIL", "SUBURBAN",
        "LONG_DISTANCE", "NIGHT_RAIL" -> TransitMode.RAIL
        "FERRY", "BOAT" -> TransitMode.FERRY
        "FUNICULAR" -> TransitMode.FUNICULAR
        else -> TransitMode.OTHER
    }

    private fun parseColor(hex: String?, mode: TransitMode): Int {
        if (!hex.isNullOrBlank()) {
            val clean = hex.removePrefix("#").trim()
            val parsed = clean.toIntOrNull(16)
            if (parsed != null) return parsed
        }
        return when (mode) {
            TransitMode.METRO -> 0xEF4444 // Red
            TransitMode.TRAM -> 0xF59E0B  // Warm Amber
            TransitMode.TROLLEYBUS -> 0x3B82F6 // Sapphire Blue
            TransitMode.BUS -> 0x10B981   // Emerald Green
            TransitMode.RAIL -> 0x8B5CF6  // Royal Violet
            TransitMode.FERRY -> 0x06B6D4 // Cyan
            TransitMode.FUNICULAR -> 0xEC4899 // Pink
            TransitMode.OTHER -> 0x14B8A6 // Teal
        }
    }

    private fun parseIsoToSecondsSinceMidnight(iso: String?): Int? {
        if (iso.isNullOrBlank()) return null
        return runCatching {
            val instant = Instant.parse(iso)
            val zdt = ZonedDateTime.ofInstant(instant, ZoneId.systemDefault())
            zdt.hour * 3600 + zdt.minute * 60 + zdt.second
        }.getOrNull()
    }

    private fun parseIsoToEpoch(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        return runCatching { Instant.parse(iso).epochSecond }.getOrNull()
    }

    private companion object {
        const val DAY_SEC = 86_400
    }
}
