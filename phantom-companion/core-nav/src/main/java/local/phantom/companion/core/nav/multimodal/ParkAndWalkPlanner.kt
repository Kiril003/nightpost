package local.phantom.companion.core.nav.multimodal

import local.phantom.companion.core.nav.worstOrigin
import local.phantom.companion.core.net.map.OsrmClient
import local.phantom.companion.core.net.map.OverpassClient
import local.phantom.companion.core.net.map.ParkAndWalkLeg
import local.phantom.companion.core.net.map.RouteProfile
import local.phantom.companion.core.net.map.RouteStep
import local.phantom.companion.core.net.map.RouteVariant
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * NAV-M1 — Park & Walk Multimodal Routing.
 *
 * When navigating by car to an indoor destination, shopping mall, park, or
 * pedestrian square, pure car routing stops abruptly on the roadway. This planner:
 * 1. Discovers verified parking facilities near the destination (Overpass amenity=parking).
 * 2. Routes CAR from origin to the parking lot.
 * 3. Routes FOOT from parking lot through building entrances to the target.
 * 4. Fuses them into an honest single multimodal itinerary with parking buffer.
 */
class ParkAndWalkPlanner(
    private val osrm: OsrmClient = OsrmClient(),
    private val overpass: OverpassClient = OverpassClient(),
) {

    data class ParkingCandidate(
        val lat: Double,
        val lon: Double,
        val name: String? = null,
        val distanceToDestM: Double,
    )

    suspend fun planParkAndWalk(
        start: Pair<Double, Double>,
        destination: Pair<Double, Double>,
    ): RouteVariant? {
        val (destLat, destLon) = destination
        val parkingCandidates = findNearbyParking(destLat, destLon, radiusM = 350)
        val bestParking = parkingCandidates.minByOrNull { it.distanceToDestM } ?: return null

        val parkingPoint = bestParking.lat to bestParking.lon
        val driveVariants = osrm.routeDetailed(RouteProfile.CAR, listOf(start, parkingPoint))
        val drive = driveVariants?.firstOrNull() ?: return null

        val walkVariants = osrm.routeDetailed(RouteProfile.FOOT, listOf(parkingPoint, destination))
        val walk = walkVariants?.firstOrNull() ?: return null

        val parkingName = bestParking.name ?: "Паркінг біля місця призначення"
        val parkStep = RouteStep(
            type = "arrive",
            modifier = "parking",
            roadName = parkingName,
            distanceMeters = 0.0,
            durationSeconds = 120.0, // 2 minutes average parking & exit buffer
            lat = bestParking.lat,
            lon = bestParking.lon,
        )

        val combinedSteps = buildList {
            addAll(drive.steps)
            add(parkStep)
            addAll(walk.steps.map { s ->
                s.copy(roadName = if (s.roadName.isBlank()) "пішохідний прохід" else s.roadName)
            })
        }

        val parkAndWalkLeg = ParkAndWalkLeg(
            parkingLat = bestParking.lat,
            parkingLon = bestParking.lon,
            parkingName = parkingName,
            driveDistanceMeters = drive.distanceMeters,
            driveDurationSeconds = drive.durationSeconds,
            walkDistanceMeters = walk.distanceMeters,
            walkDurationSeconds = walk.durationSeconds,
            walkPoints = walk.points,
            walkSteps = walk.steps,
        )

        return RouteVariant(
            points = drive.points + walk.points,
            distanceMeters = drive.distanceMeters + walk.distanceMeters,
            durationSeconds = drive.durationSeconds + 120.0 + walk.durationSeconds,
            steps = combinedSteps,
            speedsMps = drive.speedsMps,
            profile = RouteProfile.CAR,
            speedBasis = drive.speedBasis,
            postedShare = drive.postedShare,
            trunkShoulderMeters = walk.trunkShoulderMeters,
            // Обидві ноги, бо переправа на підʼїзді машиною важить не менше за
            // переправу на останніх метрах пішки — а склейка показує ОДИН шлях.
            fordCrossings = drive.fordCrossings + walk.fordCrossings,
            parkAndWalk = parkAndWalkLeg,
            // Стикування двох ніг не додає знання про заборони: якщо будь-яка
            // з них офлайнова, вся склейка успадковує її незнання.
            origin = worstOrigin(drive.origin, walk.origin),
        )
    }

    private suspend fun findNearbyParking(
        lat: Double,
        lon: Double,
        radiusM: Int,
    ): List<ParkingCandidate> {
        val query = """
            [out:json][timeout:15];
            (
              node["amenity"="parking"](around:$radiusM,$lat,$lon);
              way["amenity"="parking"](around:$radiusM,$lat,$lon);
              node["parking"](around:$radiusM,$lat,$lon);
            );
            out center 15;
        """.trimIndent()

        val elements = runCatching { overpass.query(query) }.getOrNull() ?: emptyList()
        return elements.mapNotNull { e ->
            val pLat = e.effectiveLat ?: return@mapNotNull null
            val pLon = e.effectiveLon ?: return@mapNotNull null
            val dist = haversineM(lat, lon, pLat, pLon)
            val name = e.tags?.get("name") ?: when (e.tags?.get("parking")) {
                "underground" -> "Підземний паркінг"
                "multi-storey" -> "Багаторівневий паркінг"
                else -> "Парковка"
            }
            ParkingCandidate(pLat, pLon, name, dist)
        }
    }

    private fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return 6371000.0 * c
    }
}
