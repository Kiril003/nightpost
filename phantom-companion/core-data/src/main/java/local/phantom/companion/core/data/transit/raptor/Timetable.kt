package local.phantom.companion.core.data.transit.raptor

/**
 * MULTIMODAL CONTINUUM — the compact timetable index RAPTOR runs on.
 *
 * This is the whole reason we did not take OpenTripPlanner or Valhalla.
 * Both build a *graph* (time-expanded or time-dependent) and then run a
 * Dijkstra-family search over it. A graph of Kyiv's timetable is hundreds
 * of megabytes and a JVM server to hold it — which forces routing onto a
 * backend, and a backend is exactly what a navigator must not need. The
 * EW work (dead-reckoning, spoof-gating) exists because the operator is
 * assumed to be in a jammed, degraded, possibly network-less place. A
 * route that requires a round-trip to a server dies precisely there.
 *
 * RAPTOR (Delling/Pajor/Werneck) needs no graph at all. It is rounds over
 * flat arrays: round k holds the best arrival using at most k-1 transfers.
 * So the entire timetable is a handful of `IntArray`s — for Kyiv, roughly
 * a megabyte — and a query is a few passes over contiguous memory. It
 * runs on the phone, offline, in milliseconds.
 *
 * Structure-of-arrays, not array-of-structures, on purpose: RAPTOR's inner
 * loop walks a route's stops and a trip's times in order, so the data it
 * touches next is the data physically next to it.
 *
 * A **RAPTOR route** is not a GTFS route. It is a maximal group of trips
 * that (a) visit exactly the same stop sequence and (b) never overtake one
 * another — so "trips sorted by departure at the first stop" is also their
 * order at *every* stop, which is what lets [earliestTrip] binary-search.
 * One GTFS route with two directions and three variants becomes several
 * RAPTOR routes. [GtfsIndexBuilder] does that grouping.
 *
 * All times are **seconds since local midnight of the service day**, and
 * may exceed 86_400 — GTFS says a 00:40 trip on the previous service day
 * is "24:40:00", and that convention is load-bearing here: the metro's
 * last train and the night buses depend on it. Never wrap these to 24h.
 */
class Timetable(
    /** Stop coordinates and names, indexed by stop index. */
    val stopLat: DoubleArray,
    val stopLon: DoubleArray,
    val stopName: Array<String>,
    /** Stable source id (GTFS stop_id / metro station id) per stop index. */
    val stopId: Array<String>,

    // — routes ————————————————————————————————————————————————————
    /** [routeStopsOffset]`[r]` .. `+`[routeStopCount]`[r]` indexes [routeStops]. */
    val routeStopsOffset: IntArray,
    val routeStopCount: IntArray,
    /** Stop index of each position of each route, concatenated. */
    val routeStops: IntArray,
    /** Number of trips on route r. */
    val routeTripCount: IntArray,
    /**
     * Base offset of route r into [arrivals]/[departures]. Time of trip `t`
     * at position `i` of route `r` lives at
     * `routeTimesOffset[r] + t * routeStopCount[r] + i`.
     */
    val routeTimesOffset: IntArray,
    val arrivals: IntArray,
    val departures: IntArray,
    /** Human label of the vehicle a route's trips run as (e.g. "M1", "Т7"). */
    val routeLabel: Array<String>,
    val routeMode: Array<TransitMode>,
    /** Route colour as 0xRRGGBB, or -1 when the feed declares none. */
    val routeColour: IntArray,
    /**
     * True where route r's minutes were synthesized from a headway model
     * rather than read from a published schedule. Carried per route because
     * after fusion the vehicle's mode says nothing about provenance — an
     * OSM-modelled bus and a GTFS-scheduled bus are the same mode.
     */
    val routeTimesModelled: BooleanArray,

    // — stop → routes reverse index ————————————————————————————————
    /** [stopRoutesOffset]`[s]` .. `[s+1]` indexes [stopRoutes]/[stopRoutePos]. */
    val stopRoutesOffset: IntArray,
    val stopRoutes: IntArray,
    /** Position of stop s within that route — saves re-scanning routeStops. */
    val stopRoutePos: IntArray,

    // — footpaths ——————————————————————————————————————————————————
    /** [transferOffset]`[s]` .. `[s+1]` indexes [transferTarget]/[transferSeconds]. */
    val transferOffset: IntArray,
    val transferTarget: IntArray,
    val transferSeconds: IntArray,

    /** Physical track geometry per route, null where the source has none. */
    val routeShape: Array<RouteShape?> = emptyArray(),
) {
    val stopCount: Int get() = stopLat.size
    val routeCount: Int get() = routeStopCount.size

    fun arrival(route: Int, trip: Int, pos: Int): Int =
        arrivals[routeTimesOffset[route] + trip * routeStopCount[route] + pos]

    fun departure(route: Int, trip: Int, pos: Int): Int =
        departures[routeTimesOffset[route] + trip * routeStopCount[route] + pos]

    fun stopAt(route: Int, pos: Int): Int = routeStops[routeStopsOffset[route] + pos]

    /**
     * The physical track between two call positions of [route], board→alight
     * inclusive, or null when this route has no shape (the caller falls back
     * to the stop chain — an honest bend, never a crash).
     */
    fun shapeBetween(route: Int, boardPos: Int, alightPos: Int): List<Pair<Double, Double>>? {
        val shape = routeShape.getOrNull(route) ?: return null
        val from = shape.stopIdx.getOrNull(boardPos) ?: return null
        val to = shape.stopIdx.getOrNull(alightPos) ?: return null
        if (from < 0 || to <= from || to >= shape.lat.size) return null
        return (from..to).map { shape.lat[it] to shape.lon[it] }
    }

    /**
     * Index of the earliest trip on [route] that can still be boarded at
     * position [pos] no earlier than [afterSec], or -1 if the day's service
     * on this route is spent.
     *
     * Binary search is sound only because of the no-overtaking invariant
     * above: departures at a fixed position are non-decreasing in trip
     * index. [GtfsIndexBuilder] splits any group that would violate it.
     */
    fun earliestTrip(route: Int, pos: Int, afterSec: Int): Int {
        var lo = 0
        var hi = routeTripCount[route] - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (departure(route, mid, pos) >= afterSec) {
                found = mid
                hi = mid - 1
            } else {
                lo = mid + 1
            }
        }
        return found
    }
}

/** The vehicle behind a leg — drives the icon, the colour and the verb PHANTOM speaks. */
enum class TransitMode {
    METRO, TRAM, TROLLEYBUS, BUS, RAIL, FUNICULAR, FERRY, OTHER;

    companion object {
        /** GTFS `route_type`. Only the values Ukrainian feeds actually emit. */
        fun fromGtfs(routeType: Int): TransitMode = when (routeType) {
            0 -> TRAM
            1 -> METRO
            2 -> RAIL
            3 -> BUS
            4 -> FERRY
            7 -> FUNICULAR
            11 -> TROLLEYBUS
            in 100..117, 400 -> RAIL
            in 401..405 -> METRO
            200, in 700..717 -> BUS
            800 -> TROLLEYBUS
            900 -> TRAM
            1000, 1200 -> FERRY
            1400 -> FUNICULAR
            else -> OTHER
        }
    }
}
