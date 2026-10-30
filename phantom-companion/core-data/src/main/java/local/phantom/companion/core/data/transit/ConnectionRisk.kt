package local.phantom.companion.core.data.transit

import local.phantom.companion.core.data.transit.raptor.TransitMode
import java.time.LocalDate
import java.time.ZoneId

/** Пересадки, які звичне запізнення попередньої лінії зʼїдає. */
object ConnectionRisk {

    /** Індекс ноги-поїздки → хвилини звичного запізнення, що більші за запас пересадки. */
    fun tight(
        itin: Itinerary,
        memory: TransitDelayMemory,
        zone: ZoneId = ZoneId.systemDefault(),
        day: LocalDate = LocalDate.now(zone),
    ): Map<Int, Int> {
        val midnightMs = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val out = HashMap<Int, Int>()
        val rides = itin.legs.withIndex().filter { it.value is Itinerary.Leg.Ride }
        rides.zipWithNext().forEach { (a, b) ->
            val prev = a.value as Itinerary.Leg.Ride
            val next = b.value as Itinerary.Leg.Ride
            if (prev.liveDelaySec != null) return@forEach
            val usual = memory.typicalDelaySec(keyOf(prev), midnightMs + prev.arriveSec * 1000L, zone) ?: return@forEach
            val walk = itin.legs.subList(a.index + 1, b.index).sumOf { it.seconds }
            val slack = next.departSec - prev.arriveSec - walk
            if (usual > 60 && usual >= slack) out[a.index] = usual / 60
        }
        return out
    }

    /**
     * Найшвидший варіант поступається, коли його пересадку звичне запізнення
     * зʼїдає, а інший приїжджає не пізніше ніж на [graceSec].
     */
    fun saferFirst(
        itins: List<Itinerary>,
        memory: TransitDelayMemory,
        zone: ZoneId = ZoneId.systemDefault(),
        day: LocalDate = LocalDate.now(zone),
        graceSec: Int = 10 * 60,
    ): List<Itinerary> {
        val first = itins.firstOrNull() ?: return itins
        if (tight(first, memory, zone, day).isEmpty()) return itins
        val safe = itins.drop(1).firstOrNull { it.arriveSec - first.arriveSec <= graceSec && tight(it, memory, zone, day).isEmpty() }
            ?: return itins
        return listOf(safe) + itins.filter { it !== safe }
    }

    fun keyOf(ride: Itinerary.Leg.Ride): String = TransitDelayMemory.lineKey(ride.routeLabel, modeWord(ride.mode), ride.operatorName)

    fun modeWord(mode: TransitMode): String = when (mode) {
        TransitMode.METRO -> "subway"
        TransitMode.TRAM -> "tram"
        TransitMode.TROLLEYBUS -> "trolleybus"
        TransitMode.BUS -> "bus"
        TransitMode.RAIL -> "train"
        TransitMode.FERRY -> "ferry"
        TransitMode.FUNICULAR -> "funicular"
        TransitMode.OTHER -> "transit"
    }
}
