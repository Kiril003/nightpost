package local.phantom.companion.core.data.transit

import local.phantom.companion.core.data.transit.metro.ParkRideLot
import local.phantom.companion.core.data.transit.raptor.Raptor
import local.phantom.companion.core.data.transit.raptor.Timetable
import local.phantom.companion.core.data.transit.raptor.TimetableBuilder.haversine

/**
 * Durations over the road network. Implemented in `core-net` by OSRM; kept
 * as an interface here so the planner stays pure, offline-testable, and
 * ignorant of ktor.
 */
interface RoadRouter {
    /**
     * One-to-many durations in seconds, `null` where unreachable.
     * Returns `null` overall when the router itself is unreachable — the
     * caller then falls back to a crow-fly estimate and *says so*.
     *
     * One-to-many rather than N calls on purpose: seeding a park-and-ride
     * search asks "how long to each of 52 metro stations", and that is one
     * matrix request, not 52 route requests.
     */
    suspend fun durations(
        fromLat: Double, fromLon: Double,
        to: List<Pair<Double, Double>>,
        mode: AccessMode,
    ): List<Int?>?

    /** Full geometry for the one leg we actually chose to draw. */
    suspend fun leg(
        fromLat: Double, fromLon: Double,
        toLat: Double, toLon: Double,
        mode: AccessMode,
    ): RoadLeg?
}

data class RoadLeg(val seconds: Int, val meters: Int, val points: List<Pair<Double, Double>>)

/**
 * MULTIMODAL CONTINUUM — door-to-door hybrid planning.
 *
 * This class does one interesting thing: it decides **what to seed RAPTOR
 * with**. Everything else follows.
 *
 * The industry builds park-and-ride the other way round. It keeps a curated
 * database of P&R lots, picks one by heuristic ("nearest to origin", "on the
 * corridor"), routes a car leg to it, then routes transit onward from there
 * and staples the two together. The seam is chosen *before* the timetable is
 * consulted, so the answer is only as good as the guess — and the guess is
 * made blind to whether a train is actually leaving that station soon.
 *
 * We seed instead. Every metro station becomes a candidate car-drop, each
 * with its own honest cost (drive time + the minutes to park). RAPTOR then
 * explores all 52 *simultaneously* and returns the one that genuinely gets
 * the operator there earliest — because it is an exact algorithm, not a
 * heuristic. Where to park is an **output**.
 *
 * The consequences are worth stating plainly:
 *  • No park-and-ride database is required. We ship one only to make good
 *    lots *cheaper* to use, never to enumerate the options.
 *  • The choice is timetable-aware for free: a station one stop further out
 *    wins if the train there leaves four minutes sooner, and no heuristic
 *    would ever have found that.
 *  • Only metro stations are car-drop candidates. Driving to a bus stop to
 *    stand in the same traffic you just left is not a journey; it's a joke.
 *
 * Honesty (Law 3): if the road router is unreachable we still answer, from a
 * crow-fly estimate, and every leg built that way is flagged
 * [Itinerary.Leg.Access.estimated] so the UI can say the drive time is a
 * guess. We never silently present an estimate as a measurement.
 */
class MultimodalPlanner(
    private val timetable: Timetable,
    private val road: RoadRouter,
    /** Stop ids that a car may be left at — the metro, and only the metro. */
    metroStopIds: Collection<String>,
    private val parkRideLots: List<ParkRideLot> = emptyList(),
) {
    private val stopIndexOf: Map<String, Int> =
        timetable.stopId.withIndex().associate { (i, id) -> id to i }

    private val carDropStops: List<Int> = metroStopIds.mapNotNull { stopIndexOf[it] }

    data class Options(
        /** Seconds since local midnight. */
        val departSec: Int,
        /** May the operator take the car and abandon it at a station? */
        val allowCar: Boolean = true,
        /** How far we'll ask someone to walk to a first/last stop. With the
         *  fused surface network a street stop sits every ~300 m, so this is
         *  a comfort bound, not a coverage bound — RAPTOR discards a bad
         *  walk on its own when a better seed exists. */
        val maxWalkMeters: Double = 900.0,
        val walkSpeedMps: Double = 0.9,
        /** Below this, driving the whole way is obviously right; don't pretend otherwise. */
        val minCarUsefulMeters: Double = 4_000.0,
        val maxResults: Int = 3,
    )

    data class Plan(
        /** Pareto-optimal by (arrival, transfers). Best arrival first. */
        val itineraries: List<Itinerary>,
        /**
         * Driving the whole way, for comparison — or null if we couldn't
         * measure it. PHANTOM must be willing to say "drive, it's faster";
         * a transit router that hides the car is an advocate, not a navigator.
         */
        val drivingAllTheWaySec: Int?,
    )

    suspend fun plan(
        fromLat: Double, fromLon: Double,
        toLat: Double, toLon: Double,
        opts: Options,
    ): Plan {
        val directMeters = haversine(fromLat, fromLon, toLat, toLon)

        // ── egress: every stop within walking range of the destination ──
        // A *set*, not a single "nearest stop". This is what lets the solver
        // get off one stop early when that leaves a shorter walk — behaviour
        // most routers special-case, and which here is simply the model.
        val egressStops = stopsWithin(toLat, toLon, opts.maxWalkMeters)
        val egress = egressStops.map { (stop, metres) ->
            Raptor.Egress(stop, (metres / opts.walkSpeedMps).toInt())
        }

        // ── seeds ────────────────────────────────────────────────────────
        val seeds = ArrayList<Raptor.Seed>()
        val seedLegs = ArrayList<SeedLeg>()

        // Walking access.
        val walkStops = stopsWithin(fromLat, fromLon, opts.maxWalkMeters)
        for ((stop, metres) in walkStops) {
            val secs = (metres / opts.walkSpeedMps).toInt()
            seeds += Raptor.Seed(stop, opts.departSec + secs, seedLegs.size)
            seedLegs += SeedLeg(AccessMode.WALK, stop, secs, metres.toInt(), estimated = false, parking = 0, knownLot = false)
        }

        // Driving access — the park-and-ride seeds.
        val carUseful = opts.allowCar && directMeters >= opts.minCarUsefulMeters && carDropStops.isNotEmpty()
        if (carUseful) {
            val targets = carDropStops.map { timetable.stopLat[it] to timetable.stopLon[it] }
            val measured = road.durations(fromLat, fromLon, targets, AccessMode.DRIVE)

            for ((i, stop) in carDropStops.withIndex()) {
                val sLat = timetable.stopLat[stop]
                val sLon = timetable.stopLon[stop]

                // Never drive *away* from the destination to catch a train:
                // a station further from the target than the origin already is
                // cannot be on a sane park-and-ride, and seeding it only wastes
                // rounds. (RAPTOR would reject it on time anyway — this just
                // keeps the seed set honest and small.)
                if (haversine(sLat, sLon, toLat, toLon) > directMeters) continue

                val drive = measured?.getOrNull(i)
                val estimated = drive == null
                // Crow-fly fallback: 1.35 detour factor at 30 km/h city speed.
                val secs = drive ?: ((haversine(fromLat, fromLon, sLat, sLon) * 1.35) / 8.3).toInt()

                val lot = nearestLot(sLat, sLon)
                val parking = if (lot != null) PARK_KNOWN_SEC else PARK_UNKNOWN_SEC

                seeds += Raptor.Seed(stop, opts.departSec + secs + parking, seedLegs.size)
                seedLegs += SeedLeg(
                    AccessMode.DRIVE, stop, secs + parking,
                    haversine(fromLat, fromLon, sLat, sLon).toInt(),
                    estimated = estimated, parking = parking, knownLot = lot != null,
                )
            }
        }

        if (seeds.isEmpty() || egress.isEmpty()) {
            return Plan(emptyList(), drivingBaseline(fromLat, fromLon, toLat, toLon))
        }

        val journeys = Raptor(timetable).plan(seeds, egress)
        val itineraries = journeys
            .take(opts.maxResults)
            .mapNotNull { assemble(it, seedLegs, fromLat, fromLon, toLat, toLon, opts) }

        return Plan(itineraries, drivingBaseline(fromLat, fromLon, toLat, toLon))
    }

    private suspend fun drivingBaseline(fLat: Double, fLon: Double, tLat: Double, tLon: Double): Int? =
        road.leg(fLat, fLon, tLat, tLon, AccessMode.DRIVE)?.seconds

    /** Crow-fly metres to the nearest stop this timetable knows, or null on
     *  an empty timetable — the honest number behind «транзит не зміг». */
    fun nearestStopMeters(lat: Double, lon: Double): Double? =
        (0 until timetable.stopCount).minOfOrNull { s ->
            haversine(lat, lon, timetable.stopLat[s], timetable.stopLon[s])
        }

    /** Stops within [maxMeters] of a point, nearest first. */
    private fun stopsWithin(lat: Double, lon: Double, maxMeters: Double): List<Pair<Int, Double>> {
        val out = ArrayList<Pair<Int, Double>>()
        for (s in 0 until timetable.stopCount) {
            val d = haversine(lat, lon, timetable.stopLat[s], timetable.stopLon[s])
            if (d <= maxMeters) out += s to d
        }
        out.sortBy { it.second }
        return out
    }

    private fun nearestLot(lat: Double, lon: Double): ParkRideLot? =
        parkRideLots.firstOrNull { haversine(lat, lon, it.lat, it.lon) <= LOT_NEAR_METERS }

    /** Where an access leg drops us, and what it cost. Indexed by `seedId`. */
    private data class SeedLeg(
        val mode: AccessMode,
        val stop: Int,
        val seconds: Int,
        val meters: Int,
        val estimated: Boolean,
        val parking: Int,
        val knownLot: Boolean,
    )

    private suspend fun assemble(
        j: Raptor.Journey,
        seedLegs: List<SeedLeg>,
        fromLat: Double, fromLon: Double,
        toLat: Double, toLon: Double,
        opts: Options,
    ): Itinerary? {
        val seed = seedLegs.getOrNull(j.seedId) ?: return null
        val legs = ArrayList<Itinerary.Leg>()
        var modelled = false

        // Access. Only now — once RAPTOR has actually *chosen* this seed — is
        // it worth asking the road router for geometry. Fetching a polyline for
        // all 52 candidate car-drops up front would be 52 requests to draw one line.
        // Walking legs get real road geometry too (snapped to the pedestrian
        // network, offline mesh included via the router's fallback) — a walk
        // drawn through a building is a lie the map must not tell.
        val seedStop = seed.stop
        val geom = if (seed.mode == AccessMode.DRIVE || seed.meters > WALK_GEOM_MIN_M) {
            road.leg(fromLat, fromLon, timetable.stopLat[seedStop], timetable.stopLon[seedStop], seed.mode)
        } else null

        legs += Itinerary.Leg.Access(
            mode = seed.mode,
            fromLat = fromLat, fromLon = fromLon,
            toLat = timetable.stopLat[seedStop], toLon = timetable.stopLon[seedStop],
            toStopName = timetable.stopName[seedStop],
            seconds = seed.seconds,
            meters = geom?.meters ?: seed.meters,
            points = geom?.points.orEmpty(),
            estimated = seed.estimated && geom == null,
            parkingSeconds = seed.parking,
            knownLot = seed.knownLot,
        )

        for (hop in j.hops) {
            when (hop) {
                is Raptor.Hop.Origin -> Unit   // already emitted as the access leg
                is Raptor.Hop.Board -> {
                    val from = timetable.stopAt(hop.route, hop.boardPos)
                    val to = timetable.stopAt(hop.route, hop.alightPos)
                    // Provenance comes from the timetable, never the vehicle
                    // kind: the OSM-synthesized surface network is exactly as
                    // modelled as the metro, and a future real-GTFS metro
                    // would be exactly as scheduled as a real-GTFS bus.
                    val modelledTimes = timetable.routeTimesModelled[hop.route]
                    if (modelledTimes) modelled = true
                    legs += Itinerary.Leg.Ride(
                        routeLabel = timetable.routeLabel[hop.route],
                        mode = timetable.routeMode[hop.route],
                        colour = timetable.routeColour[hop.route],
                        fromStopName = timetable.stopName[from],
                        toStopName = timetable.stopName[to],
                        fromLat = timetable.stopLat[from], fromLon = timetable.stopLon[from],
                        toLat = timetable.stopLat[to], toLon = timetable.stopLon[to],
                        departSec = timetable.departure(hop.route, hop.trip, hop.boardPos),
                        arriveSec = timetable.arrival(hop.route, hop.trip, hop.alightPos),
                        intermediateStops = (hop.alightPos - hop.boardPos - 1).coerceAtLeast(0),
                        // Real track geometry when the feed carries a shape;
                        // the stop chain is the honest fallback, never a
                        // straight slash between the two ends.
                        points = timetable.shapeBetween(hop.route, hop.boardPos, hop.alightPos)
                            ?: (hop.boardPos..hop.alightPos).map { pos ->
                                val s = timetable.stopAt(hop.route, pos)
                                timetable.stopLat[s] to timetable.stopLon[s]
                            },
                        timesAreModelled = modelledTimes,
                        headsign = timetable.stopName[timetable.stopAt(hop.route, timetable.routeStopCount[hop.route] - 1)]
                            .takeIf { it.isNotBlank() && it != timetable.stopName[to] },
                        stops = (hop.boardPos + 1 until hop.alightPos).map { pos ->
                            val s = timetable.stopAt(hop.route, pos)
                            Itinerary.Leg.RideStop(
                                name = timetable.stopName[s],
                                lat = timetable.stopLat[s],
                                lon = timetable.stopLon[s],
                                arriveSec = timetable.arrival(hop.route, hop.trip, pos),
                            )
                        },
                    )
                }
                is Raptor.Hop.Walk -> legs += Itinerary.Leg.Transfer(
                    fromStopName = timetable.stopName[hop.fromStop],
                    toStopName = "",   // filled below, once we know where it landed
                    seconds = hop.seconds,
                )
            }
        }

        // A Transfer's destination is the boarding stop of the ride that follows it.
        for (i in legs.indices) {
            val t = legs[i] as? Itinerary.Leg.Transfer ?: continue
            val next = legs.getOrNull(i + 1) as? Itinerary.Leg.Ride ?: continue
            legs[i] = t.copy(toStopName = next.fromStopName)
        }

        val egressMeters = (j.egressSeconds * opts.walkSpeedMps).toInt()
        val egressGeom = if (egressMeters > WALK_GEOM_MIN_M) {
            road.leg(
                timetable.stopLat[j.egressStop], timetable.stopLon[j.egressStop],
                toLat, toLon, AccessMode.WALK,
            )
        } else null
        legs += Itinerary.Leg.Access(
            mode = AccessMode.WALK,
            fromLat = timetable.stopLat[j.egressStop], fromLon = timetable.stopLon[j.egressStop],
            toLat = toLat, toLon = toLon,
            toStopName = "",
            seconds = j.egressSeconds,
            meters = egressGeom?.meters ?: egressMeters,
            points = egressGeom?.points.orEmpty(),
        )

        return Itinerary(
            legs = legs,
            departSec = opts.departSec,
            arriveSec = j.arrivalSec,
            transfers = j.transfers,
            hasModelledTimes = modelled,
        )
    }

    private companion object {
        /** Park in a declared lot with a known capacity: pull in and walk. */
        const val PARK_KNOWN_SEC = 120
        /** Hunt for a space near a station that has no lot. Optimistic if anything. */
        const val PARK_UNKNOWN_SEC = 300
        const val LOT_NEAR_METERS = 400.0

        /** Below this a straight walk line is visually honest; above it we
         *  pay one router call for real pedestrian geometry. */
        const val WALK_GEOM_MIN_M = 120
    }
}
