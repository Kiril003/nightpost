package local.phantom.companion.core.data.transit.metro

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import local.phantom.companion.core.data.transit.FeedBounds
import local.phantom.companion.core.data.transit.raptor.RawStop
import local.phantom.companion.core.data.transit.raptor.RawStopTime
import local.phantom.companion.core.data.transit.raptor.RawTrip
import local.phantom.companion.core.data.transit.raptor.TransitMode

@Serializable
data class MetroDoc(
    val city: String,
    val generatedUtc: String = "",
    val source: String = "",
    val timingModel: String = "",
    val serviceStartSec: Int,
    val serviceEndSec: Int,
    val headwaySeconds: Headways,
    val lines: List<MetroLine>,
    val interchanges: List<Interchange> = emptyList(),
    val parkRideLots: List<ParkRideLot> = emptyList(),
)

@Serializable
data class Headways(
    val peak: Int,
    val day: Int,
    val evening: Int,
)

@Serializable
data class MetroLine(
    val ref: String,
    val name: String = "",
    val colour: String? = null,
    val stations: List<MetroStation>,
    val hopSeconds: List<Int>,
)

@Serializable
data class MetroStation(val id: String, val name: String, val lat: Double, val lon: Double)

@Serializable
data class Interchange(
    val a: String, val b: String,
    val aName: String = "", val bName: String = "",
    val walkSeconds: Int,
)

@Serializable
data class ParkRideLot(
    val lat: Double, val lon: Double,
    val capacity: Int,
    val name: String? = null,
)

class MetroFeed(val doc: MetroDoc) {

    val stops: List<RawStop> =
        doc.lines.flatMap { line -> line.stations.map { RawStop(it.id, it.name, it.lat, it.lon) } }

    val transfers: List<Triple<String, String, Int>> =
        doc.interchanges.map { Triple(it.a, it.b, it.walkSeconds) }

    val stationIds: List<String> = stops.map { it.id }

    /** What this synthesized network covers — outside it, it has nothing to say. */
    val bounds: FeedBounds? = FeedBounds.around(stops.map { it.lat to it.lon })

    private val colourOf: (MetroLine) -> Int = { line ->
        line.colour?.removePrefix("#")?.let { runCatching { it.toInt(16) }.getOrNull() } ?: -1
    }

    fun trips(): List<RawTrip> {
        val out = ArrayList<RawTrip>(2048)
        for (line in doc.lines) {
            val colour = colourOf(line)
            val forward = line.stations
            val hops = line.hopSeconds
            if (forward.size < 2 || hops.size != forward.size - 1) continue

            for (direction in 0..1) {
                val stations = if (direction == 0) forward else forward.asReversed()
                val hopSeq = if (direction == 0) hops else hops.asReversed()

                var depart = doc.serviceStartSec
                while (depart < doc.serviceEndSec) {
                    val calls = ArrayList<RawStopTime>(stations.size)
                    var t = depart
                    for (i in stations.indices) {
                        calls += RawStopTime(stations[i].id, t, t)
                        if (i < hopSeq.size) t += hopSeq[i]
                    }
                    out += RawTrip(
                        routeLabel = line.ref,
                        mode = TransitMode.METRO,
                        colour = colour,
                        calls = calls,
                        timesAreModelled = true,
                    )
                    depart += headwayAt(depart)
                }
            }
        }
        return out
    }

    fun headwayAt(sec: Int): Int = when {
        sec >= EVENING_FROM -> doc.headwaySeconds.evening
        sec in PEAK_AM || sec in PEAK_PM -> doc.headwaySeconds.peak
        else -> doc.headwaySeconds.day
    }

    companion object {
        private val PEAK_AM = 7 * 3600..10 * 3600
        private val PEAK_PM = 17 * 3600..20 * 3600
        private const val EVENING_FROM = 22 * 3600

        private val json = Json { ignoreUnknownKeys = true }

        fun parse(raw: String): MetroFeed = MetroFeed(json.decodeFromString(MetroDoc.serializer(), raw))
    }
}
