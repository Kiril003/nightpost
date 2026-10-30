package local.phantom.companion.core.data.transit.raptor

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A stop as any source describes it, before it becomes an index. */
data class RawStop(val id: String, val name: String, val lat: Double, val lon: Double)

/** One call of one trip. Seconds since local midnight; may exceed 86_400. */
data class RawStopTime(val stopId: String, val arrivalSec: Int, val departureSec: Int)

/**
 * One vehicle running once, end to end. The *only* unit of service the
 * solver understands — headway-based services (the metro) are expanded into
 * these at ingest, so the hot path has exactly one concept instead of two.
 */
data class RawTrip(
    val routeLabel: String,
    val mode: TransitMode,
    /** 0xRRGGBB, or -1 when the source declares no colour. */
    val colour: Int,
    val calls: List<RawStopTime>,
    /**
     * True when the calls were synthesized from a headway model; false when
     * they were read from a published schedule. Deliberately no default: a
     * source that forgets to declare where its minutes came from must fail
     * to compile, not ship a model dressed as a timetable.
     */
    val timesAreModelled: Boolean,
    /** Physical track geometry, shared by every trip of a line. Null = the
     *  source has no shape and rides degrade to the stop chain. */
    val shape: RouteShape? = null,
)

/**
 * The physical path a route's vehicles actually travel, with each call
 * position anchored to a shape vertex ([stopIdx] is parallel to the trip's
 * calls and MUST be non-decreasing). This is what lets a drawn ride bend
 * with the street instead of slashing through buildings.
 */
class RouteShape(
    val lat: DoubleArray,
    val lon: DoubleArray,
    val stopIdx: IntArray,
)

/**
 * MULTIMODAL CONTINUUM — turns any source's trips into the flat
 * [Timetable] RAPTOR runs on.
 *
 * Deliberately source-agnostic: a GTFS zip and the synthesized OSM feeds
 * all land here and fuse into one solvable world, which is what lets a
 * single RAPTOR query cross sources mid-journey. The one thing a trip keeps
 * from its source is [RawTrip.timesAreModelled]: time provenance must
 * survive the fusion, or the UI ends up promising modelled minutes.
 */
object TimetableBuilder {

    /**
     * @param maxTransferMeters how far we will make the operator walk to
     *   change vehicle. 250 m is about the width of a big interchange; more
     *   than that and RAPTOR starts inventing "transfers" across a motorway.
     * @param walkSpeedMps 0.9 m/s — a real person with a bag, not a
     *   pedestrian-model athlete. Under-promising here is the honest error.
     * @param extraTransfers footpaths the source knows about but geometry
     *   does not — a metro interchange is 200 m of tunnel and escalator, and
     *   its two platforms may sit 40 m apart on the map.
     */
    fun build(
        stops: List<RawStop>,
        trips: List<RawTrip>,
        maxTransferMeters: Double = 250.0,
        walkSpeedMps: Double = 0.9,
        extraTransfers: List<Triple<String, String, Int>> = emptyList(),
    ): Timetable {
        require(stops.isNotEmpty()) { "a timetable with no stops is not a timetable" }

        val stopIndex = HashMap<String, Int>(stops.size * 2)
        stops.forEachIndexed { i, s -> stopIndex[s.id] = i }
        val n = stops.size

        // ── group trips into RAPTOR routes ───────────────────────────────
        // A RAPTOR route is trips sharing a stop *sequence*. Label and mode
        // join the key so a bus and a trolleybus that happen to share a
        // corridor never merge into one line the operator can't name.
        val groups = LinkedHashMap<String, MutableList<Pair<RawTrip, IntArray>>>()
        for (t in trips) {
            if (t.calls.size < 2) continue
            val seq = IntArray(t.calls.size)
            var ok = true
            for (i in t.calls.indices) {
                val s = stopIndex[t.calls[i].stopId]
                if (s == null) { ok = false; break }   // call at an unknown stop — drop the trip, don't guess
                seq[i] = s
            }
            if (!ok) continue
            val key = buildString {
                append(t.mode.name).append('').append(t.routeLabel).append('')
                // Provenance joins the key: a modelled trip and a scheduled
                // one must never fuse into a route that can only carry one flag.
                append(t.timesAreModelled).append('')
                for (s in seq) append(s).append(',')
            }
            groups.getOrPut(key) { ArrayList() } += t to seq
        }

        // Split each group so that no trip overtakes another. Timetable's
        // binary search assumes departures are non-decreasing in trip index
        // at *every* position; an express sharing a local's stop list would
        // silently break it, and the bug would surface as a route that
        // "can't be boarded" only at certain hours. Split instead.
        data class Route(val label: String, val mode: TransitMode, val colour: Int,
                         val seq: IntArray, val trips: MutableList<RawTrip>,
                         val shape: RouteShape?, val modelled: Boolean)
        val routes = ArrayList<Route>()
        for ((_, members) in groups) {
            val sorted = members.sortedBy { it.first.calls.first().departureSec }
            val bins = ArrayList<Route>()
            for ((trip, seq) in sorted) {
                val bin = bins.firstOrNull { b ->
                    val last = b.trips.last()
                    trip.calls.indices.all { i ->
                        last.calls[i].departureSec <= trip.calls[i].departureSec
                    }
                }
                if (bin != null) bin.trips += trip
                else bins += Route(
                    trip.routeLabel, trip.mode, trip.colour, seq, arrayListOf(trip),
                    // A shape is only usable when its anchors match this exact
                    // stop sequence; trips grouped here share the sequence, so
                    // the first shaped trip speaks for the route.
                    trip.shape?.takeIf { it.stopIdx.size == seq.size },
                    trip.timesAreModelled,
                )
            }
            routes += bins
        }

        // ── flatten ──────────────────────────────────────────────────────
        val routeCount = routes.size
        val routeStopsOffset = IntArray(routeCount)
        val routeStopCount = IntArray(routeCount)
        val routeTripCount = IntArray(routeCount)
        val routeTimesOffset = IntArray(routeCount)
        val routeLabel = Array(routeCount) { "" }
        val routeMode = Array(routeCount) { TransitMode.OTHER }
        val routeColour = IntArray(routeCount) { -1 }

        var stopsCursor = 0
        var timesCursor = 0
        for (r in routes.indices) {
            val rt = routes[r]
            routeStopsOffset[r] = stopsCursor
            routeStopCount[r] = rt.seq.size
            routeTripCount[r] = rt.trips.size
            routeTimesOffset[r] = timesCursor
            routeLabel[r] = rt.label
            routeMode[r] = rt.mode
            routeColour[r] = rt.colour
            stopsCursor += rt.seq.size
            timesCursor += rt.seq.size * rt.trips.size
        }

        val routeStops = IntArray(stopsCursor)
        val arrivals = IntArray(timesCursor)
        val departures = IntArray(timesCursor)
        for (r in routes.indices) {
            val rt = routes[r]
            rt.seq.copyInto(routeStops, routeStopsOffset[r])
            val len = rt.seq.size
            for (t in rt.trips.indices) {
                val base = routeTimesOffset[r] + t * len
                val calls = rt.trips[t].calls
                for (i in 0 until len) {
                    arrivals[base + i] = calls[i].arrivalSec
                    departures[base + i] = calls[i].departureSec
                }
            }
        }

        // ── stop → routes reverse index ──────────────────────────────────
        // A stop may be served by the same route twice (a loop line calls at
        // its terminus on the way out and the way back); both positions are
        // recorded, because boarding at the wrong one sends you the long way.
        val perStopRoute = Array(n) { ArrayList<Int>(4) }
        val perStopPos = Array(n) { ArrayList<Int>(4) }
        for (r in routes.indices) {
            val rt = routes[r]
            for (pos in rt.seq.indices) {
                perStopRoute[rt.seq[pos]] += r
                perStopPos[rt.seq[pos]] += pos
            }
        }
        val stopRoutesOffset = IntArray(n + 1)
        for (s in 0 until n) stopRoutesOffset[s + 1] = stopRoutesOffset[s] + perStopRoute[s].size
        val stopRoutes = IntArray(stopRoutesOffset[n])
        val stopRoutePos = IntArray(stopRoutesOffset[n])
        for (s in 0 until n) {
            var i = stopRoutesOffset[s]
            for (k in perStopRoute[s].indices) {
                stopRoutes[i] = perStopRoute[s][k]
                stopRoutePos[i] = perStopPos[s][k]
                i++
            }
        }

        // ── footpaths ────────────────────────────────────────────────────
        val foot = buildFootpaths(stops, maxTransferMeters, walkSpeedMps, extraTransfers, stopIndex)
        val transferOffset = IntArray(n + 1)
        for (s in 0 until n) transferOffset[s + 1] = transferOffset[s] + foot[s].size
        val transferTarget = IntArray(transferOffset[n])
        val transferSeconds = IntArray(transferOffset[n])
        for (s in 0 until n) {
            var i = transferOffset[s]
            for ((target, secs) in foot[s]) {
                transferTarget[i] = target
                transferSeconds[i] = secs
                i++
            }
        }

        return Timetable(
            stopLat = DoubleArray(n) { stops[it].lat },
            stopLon = DoubleArray(n) { stops[it].lon },
            stopName = Array(n) { stops[it].name },
            stopId = Array(n) { stops[it].id },
            routeStopsOffset = routeStopsOffset,
            routeStopCount = routeStopCount,
            routeStops = routeStops,
            routeTripCount = routeTripCount,
            routeTimesOffset = routeTimesOffset,
            arrivals = arrivals,
            departures = departures,
            routeLabel = routeLabel,
            routeMode = routeMode,
            routeColour = routeColour,
            stopRoutesOffset = stopRoutesOffset,
            stopRoutes = stopRoutes,
            stopRoutePos = stopRoutePos,
            transferOffset = transferOffset,
            transferTarget = transferTarget,
            transferSeconds = transferSeconds,
            routeShape = Array(routeCount) { routes[it].shape },
            routeTimesModelled = BooleanArray(routeCount) { routes[it].modelled },
        )
    }

    /**
     * Nearby-stop footpaths, found by grid bucketing rather than comparing
     * every stop with every other: Kyiv plus Lviv is ~2 500 stops, and the
     * quadratic sweep is 3 M haversines we simply don't need to pay.
     */
    private fun buildFootpaths(
        stops: List<RawStop>,
        maxMeters: Double,
        walkSpeedMps: Double,
        extra: List<Triple<String, String, Int>>,
        stopIndex: Map<String, Int>,
    ): Array<MutableList<Pair<Int, Int>>> {
        val n = stops.size
        // Explicit element type: arrays are invariant, so an inferred
        // Array<ArrayList<…>> is NOT an Array<MutableList<…>> and cannot be
        // returned as one.
        val out = Array<MutableList<Pair<Int, Int>>>(n) { ArrayList(4) }
        val seen = Array(n) { HashSet<Int>(4) }

        val cell = maxMeters / 111_320.0            // degrees of latitude per cell
        val grid = HashMap<Long, MutableList<Int>>()
        fun key(la: Int, lo: Int): Long = (la.toLong() shl 32) xor (lo.toLong() and 0xFFFFFFFFL)
        for (i in 0 until n) {
            val la = (stops[i].lat / cell).toInt()
            val lo = (stops[i].lon / cell).toInt()
            grid.getOrPut(key(la, lo)) { ArrayList() } += i
        }

        for (i in 0 until n) {
            val la = (stops[i].lat / cell).toInt()
            val lo = (stops[i].lon / cell).toInt()
            for (dla in -1..1) for (dlo in -1..1) {
                val bucket = grid[key(la + dla, lo + dlo)] ?: continue
                for (j in bucket) {
                    if (j == i) continue
                    val d = haversine(stops[i].lat, stops[i].lon, stops[j].lat, stops[j].lon)
                    if (d > maxMeters) continue
                    if (seen[i].add(j)) out[i] += j to (d / walkSpeedMps).toInt()
                }
            }
        }

        // Source-declared footpaths override geometry: they win on ties
        // because a tunnel that exists beats a straight line that doesn't.
        for ((aId, bId, secs) in extra) {
            val a = stopIndex[aId] ?: continue
            val b = stopIndex[bId] ?: continue
            if (a == b) continue
            for ((from, to) in listOf(a to b, b to a)) {
                val existing = out[from].indexOfFirst { it.first == to }
                if (existing >= 0) out[from][existing] = to to min(out[from][existing].second, secs)
                else { out[from] += to to secs; seen[from] += to }
            }
        }
        return out
    }

    internal fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}
