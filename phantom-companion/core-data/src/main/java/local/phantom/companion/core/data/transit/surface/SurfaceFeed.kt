package local.phantom.companion.core.data.transit.surface

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import local.phantom.companion.core.data.transit.FeedBounds
import local.phantom.companion.core.data.transit.raptor.RawStop
import local.phantom.companion.core.data.transit.raptor.RawStopTime
import local.phantom.companion.core.data.transit.raptor.RawTrip
import local.phantom.companion.core.data.transit.raptor.RouteShape
import local.phantom.companion.core.data.transit.raptor.TransitMode

/**
 * MULTIMODAL CONTINUUM — the synthesized surface network.
 */
@Serializable
data class SurfaceDoc(
    val city: String,
    val generatedUtc: String = "",
    val source: String = "",
    val timingModel: String = "",
    val serviceStartSec: Int,
    val serviceEndSec: Int,
    val lines: List<SurfaceLine>,
)

@Serializable
data class SurfaceHeadway(val peak: Int, val day: Int, val evening: Int)

@Serializable
data class SurfaceLine(
    val ref: String,
    val mode: String,
    val name: String = "",
    val colour: String? = null,
    val headway: SurfaceHeadway,
    val stops: List<SurfaceStop>,
    val hopSeconds: List<Int>,
    val shape: String? = null,
    val stopShapeIdx: List<Int> = emptyList(),
)

@Serializable
data class SurfaceStop(val id: String, val name: String, val lat: Double, val lon: Double)

class SurfaceFeed(val doc: SurfaceDoc) {

    val stops: List<RawStop> = run {
        val seen = HashMap<String, RawStop>()
        for (line in doc.lines) {
            for (s in line.stops) {
                seen.getOrPut(s.id) { RawStop(s.id, s.name, s.lat, s.lon) }
            }
        }
        seen.values.toList()
    }

    /** What this synthesized network covers — outside it, it has nothing to say. */
    val bounds: FeedBounds? = FeedBounds.around(stops.map { it.lat to it.lon })

    fun trips(): List<RawTrip> {
        val out = ArrayList<RawTrip>(16_384)
        for (line in doc.lines) {
            if (line.stops.size < 2 || line.hopSeconds.size != line.stops.size - 1) continue
            val mode = modeOf(line.mode)
            val colour = colourOf(line)
            val shape = shapeOf(line)
            var depart = doc.serviceStartSec
            while (depart < doc.serviceEndSec) {
                val calls = ArrayList<RawStopTime>(line.stops.size)
                var t = depart
                for (i in line.stops.indices) {
                    calls += RawStopTime(line.stops[i].id, t, t)
                    if (i < line.hopSeconds.size) t += line.hopSeconds[i]
                }
                out += RawTrip(
                    routeLabel = line.ref,
                    mode = mode,
                    colour = colour,
                    calls = calls,
                    timesAreModelled = true,
                    shape = shape,
                )
                depart += headwayAt(line.headway, depart)
            }
        }
        return out
    }

    // 14.09: генератор змодельованих позицій прибрано (§TransitGlassLaw —
    // скло бачить лише LIVE_FEED, розкладовим/змодельованим планам туди
    // зась). Це друга половина видалення, розпочатого рятувальним комітом
    // f41916c6 (агента "transit-real" обірвано лімітом якраз на "remove the
    // position generators from both feeds" — MetroFeed свою частину вже
    // втратив, SurfaceFeed лишався сиротою без жодного продукційного
    // викликача). Симетрія з MetroFeed відновлена.

    private fun shapeOf(line: SurfaceLine): RouteShape? {
        val encoded = line.shape ?: return null
        if (line.stopShapeIdx.size != line.stops.size) return null
        val pts = Polyline5.decode(encoded) ?: return null
        if (pts.size < 2) return null
        val idx = IntArray(line.stopShapeIdx.size) { line.stopShapeIdx[it] }
        for (k in idx.indices) {
            if (idx[k] < 0 || idx[k] >= pts.size) return null
            if (k > 0 && idx[k] < idx[k - 1]) return null
        }
        return RouteShape(
            lat = DoubleArray(pts.size) { pts[it].first },
            lon = DoubleArray(pts.size) { pts[it].second },
            stopIdx = idx,
        )
    }

    fun headwayAt(h: SurfaceHeadway, sec: Int): Int = when {
        sec >= EVENING_FROM -> h.evening
        sec in PEAK_AM || sec in PEAK_PM -> h.peak
        else -> h.day
    }

    private fun colourOf(line: SurfaceLine): Int {
        val declared = line.colour
            ?.removePrefix("#")
            ?.takeIf { it.length == 6 }
            ?.let { runCatching { it.toInt(16) }.getOrNull() }
        return declared ?: MODE_COLOUR.getValue(modeOf(line.mode))
    }

    private fun modeOf(word: String): TransitMode = when (word) {
        "tram" -> TransitMode.TRAM
        "trolleybus" -> TransitMode.TROLLEYBUS
        else -> TransitMode.BUS
    }

    companion object {
        private val PEAK_AM = 7 * 3600..10 * 3600
        private val PEAK_PM = 17 * 3600..20 * 3600
        private const val EVENING_FROM = 22 * 3600

        private val MODE_COLOUR = mapOf(
            TransitMode.BUS to 0x1565C0,
            TransitMode.TROLLEYBUS to 0x2E7D32,
            TransitMode.TRAM to 0xC62828,
        )

        private val json = Json { ignoreUnknownKeys = true }

        fun parse(raw: String): SurfaceFeed =
            SurfaceFeed(json.decodeFromString(SurfaceDoc.serializer(), raw))
    }
}
