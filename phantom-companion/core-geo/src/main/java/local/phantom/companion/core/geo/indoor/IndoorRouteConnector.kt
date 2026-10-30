package local.phantom.companion.core.geo.indoor

import local.phantom.companion.core.net.map.RouteStep
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * INDOOR-2 — Connects street navigation to building entrances, indoor corridors,
 * elevators, escalators, and floor levels.
 */
class IndoorRouteConnector(
    private val engine: BuildingIndoorEngine = BuildingIndoorEngine(),
) {
    data class IndoorConnection(
        val entrance: BuildingEntrance?,
        val walkApproachPoints: List<Pair<Double, Double>>,
        val indoorPoints: List<Pair<Double, Double>>,
        val level: Int,
        val steps: List<RouteStep>,
    )

    suspend fun connectDestination(
        approachPoint: Pair<Double, Double>,
        destPoint: Pair<Double, Double>,
    ): IndoorConnection? {
        val distToApproach = haversineM(approachPoint.first, approachPoint.second, destPoint.first, destPoint.second)
        // If destination is right on the street curb (< 15m), no indoor connector needed
        if (distToApproach < 15.0) return null

        val plan = engine.fetchBuildingPlan(destPoint.first, destPoint.second, radiusM = 150)
            ?: return fallbackConnection(approachPoint, destPoint)

        // Select the most suitable entrance (closest to approach, prioritizing MAIN and ACCESSIBLE)
        val bestEntrance = plan.entrances.minByOrNull { ent ->
            val d = haversineM(approachPoint.first, approachPoint.second, ent.lat, ent.lon)
            val penalty = when (ent.kind) {
                EntranceKind.MAIN -> 0.0
                EntranceKind.ACCESSIBLE -> 10.0
                EntranceKind.GENERIC -> 20.0
                EntranceKind.SUBWAY -> 15.0
                EntranceKind.SERVICE -> 100.0
                EntranceKind.EMERGENCY -> 500.0
            }
            d + penalty
        }

        if (bestEntrance == null) {
            return fallbackConnection(approachPoint, destPoint)
        }

        val entrancePt = bestEntrance.lat to bestEntrance.lon
        val approachWalk = listOf(approachPoint, entrancePt)

        val targetVenue = plan.venues.minByOrNull { haversineM(destPoint.first, destPoint.second, it.lat, it.lon) }
        val targetLevel = targetVenue?.level ?: bestEntrance.level

        val indoorPath = mutableListOf<Pair<Double, Double>>()
        indoorPath.add(entrancePt)

        val indoorSteps = mutableListOf<RouteStep>()
        val entranceLabel = bestEntrance.name ?: bestEntrance.kind.labelUk

        val walkDist = haversineM(approachPoint.first, approachPoint.second, entrancePt.first, entrancePt.second)
        indoorSteps += RouteStep(
            type = "entrance",
            modifier = "straight",
            roadName = "Вхід: $entranceLabel (поверх ${bestEntrance.level})",
            distanceMeters = walkDist,
            durationSeconds = walkDist / 1.1,
            lat = entrancePt.first,
            lon = entrancePt.second,
        )

        if (targetLevel != bestEntrance.level) {
            val vertical = plan.passages.firstOrNull {
                it.kind == PassageKind.ESCALATOR || it.kind == PassageKind.ELEVATOR || it.kind == PassageKind.STEPS
            }
            if (vertical != null && vertical.points.isNotEmpty()) {
                indoorPath.addAll(vertical.points)
                indoorSteps += RouteStep(
                    type = "indoor_level",
                    modifier = "straight",
                    roadName = "${vertical.kind.labelUk}: перехід на поверх $targetLevel",
                    distanceMeters = 25.0,
                    durationSeconds = 40.0,
                    lat = vertical.points.first().first,
                    lon = vertical.points.first().second,
                )
            }
        }

        indoorPath.add(destPoint)

        return IndoorConnection(
            entrance = bestEntrance,
            walkApproachPoints = approachWalk,
            indoorPoints = indoorPath,
            level = targetLevel,
            steps = indoorSteps,
        )
    }

    private fun fallbackConnection(
        approachPoint: Pair<Double, Double>,
        destPoint: Pair<Double, Double>,
    ): IndoorConnection? {
        val dist = haversineM(approachPoint.first, approachPoint.second, destPoint.first, destPoint.second)
        if (dist <= 20.0) return null
        return IndoorConnection(
            entrance = null,
            walkApproachPoints = listOf(approachPoint, destPoint),
            indoorPoints = emptyList(),
            level = 0,
            steps = listOf(
                RouteStep(
                    type = "arrive",
                    modifier = "straight",
                    roadName = "Прохід до входу обʼєкта",
                    distanceMeters = dist,
                    durationSeconds = dist / 1.1,
                    lat = destPoint.first,
                    lon = destPoint.second,
                ),
            ),
        )
    }

    private fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}
