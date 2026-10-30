package local.phantom.companion.data

import local.phantom.companion.core.data.transit.AccessMode
import local.phantom.companion.core.data.transit.RoadLeg
import local.phantom.companion.core.data.transit.RoadRouter
import local.phantom.companion.core.net.map.OsrmClient
import local.phantom.companion.core.net.map.RouteProfile

class OsrmRoadRouter(
    private val client: OsrmClient,
    /**
     * TRANSIT GEOMETRY — the permanent road mesh as the offline fallback for
     * leg geometry: (minLat, minLon, maxLat, maxLon) → graph, or null. When
     * OSRM is unreachable, a walking leg still snaps to real streets instead
     * of flying through buildings.
     */
    private val meshGraph: ((Double, Double, Double, Double) -> local.phantom.companion.core.sensor.nav.RoadGraph?)? = null,
    /**
     * HORIZON II.1 — вивчена тінь глушилки, ТОЙ САМИЙ екземпляр, що годує
     * головний маршрут і евакуацію ([AppContainer.gnssIntegrity]). Пішки до
     * зупинки — теж дорога, і вона теж має право обійти квартал, де небо вже
     * виявилось ворожим. Null у тестах і превʼю: тоді плече рахується як
     * рахувалось — найкоротшим.
     *
     * Сітки укриття тут навмисно немає: [CoverGrid] існує лише після запиту в
     * Overpass, а пересадкове плече — це сотні метрів у місті, де такий запит
     * коштував би дорожче за саму поправку.
     */
    private val integrity: local.phantom.companion.core.sensor.nav.GnssIntegrityMap? = null,
) : RoadRouter {

    private val localRouter = local.phantom.companion.core.sensor.nav.LocalRouter()
    override suspend fun durations(
        fromLat: Double,
        fromLon: Double,
        to: List<Pair<Double, Double>>,
        mode: AccessMode
    ): List<Int?>? {
        val profile = when (mode) {
            AccessMode.DRIVE -> RouteProfile.CAR
            AccessMode.WALK -> RouteProfile.FOOT
        }
        return client.table(profile, fromLat, fromLon, to)
    }

    override suspend fun leg(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        mode: AccessMode
    ): RoadLeg? {
        val profile = when (mode) {
            AccessMode.DRIVE -> RouteProfile.CAR
            AccessMode.WALK -> RouteProfile.FOOT
        }
        val result = client.route(profile, fromLat, fromLon, toLat, toLon)
            ?: return meshLeg(fromLat, fromLon, toLat, toLon, mode)
        return RoadLeg(
            seconds = result.durationSeconds.toInt(),
            meters = result.distanceMeters.toInt(),
            points = result.points
        )
    }

    private fun meshLeg(
        fromLat: Double, fromLon: Double,
        toLat: Double, toLon: Double,
        mode: AccessMode,
    ): RoadLeg? {
        val pad = 0.01 // ~1.1 km skirt so the local graph holds the whole leg
        val graph = meshGraph?.invoke(
            minOf(fromLat, toLat) - pad, minOf(fromLon, toLon) - pad,
            maxOf(fromLat, toLat) + pad, maxOf(fromLon, toLon) + pad,
        ) ?: return null
        // `AccessMode` несе рівно дві дороги — пішки й авто, — бо транзитна
        // пересадка іншого й не знає. Велосипеда тут немає не через недогляд,
        // і `when` над переліком це закріплює: якби він зʼявився, компілятор
        // привів би сюди, а не мовчки зарахував би його в авто.
        val route = localRouter.route(
            graph, fromLat, fromLon, toLat, toLon,
            when (mode) {
                AccessMode.WALK -> local.phantom.companion.core.sensor.nav.RoadProfile.TravelMode.FOOT
                AccessMode.DRIVE -> local.phantom.companion.core.sensor.nav.RoadProfile.TravelMode.CAR
            },
            integrity = integrity,
            jammingAvoidance =
                if (integrity != null) {
                    local.phantom.companion.core.sensor.nav.RouteCaution.EVERYDAY.jammingAvoidance
                } else {
                    0.0
                },
        ) ?: return null
        return RoadLeg(
            seconds = route.durationSeconds.toInt(),
            meters = route.distanceMeters.toInt(),
            points = route.points,
        )
    }
}
