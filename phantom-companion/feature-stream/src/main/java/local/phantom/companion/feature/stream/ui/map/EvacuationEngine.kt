package local.phantom.companion.feature.stream.ui.map

import androidx.annotation.StringRes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import local.phantom.companion.core.data.evac.ShelterToken
import local.phantom.companion.core.net.map.OsrmClient
import local.phantom.companion.core.net.map.RouteProfile
import local.phantom.companion.feature.stream.ui.widget.MapPayload

/**
 * M-C05/C06 — the tactical-evacuation lifecycle. Idle → Scanning →
 * TacticalRouting → ActiveGuidance, terminating in Arrived, Compromised, or
 * InPlaceDefense (Law 3: a degraded/failed scan or route is a state,
 * rendered in the Obsidian-Glass EvacuationCard — never a silent no-op and
 * never a bare system notice).
 */
sealed interface EvacuationState {
    data object Idle : EvacuationState
    data object Scanning : EvacuationState
    data object TacticalRouting : EvacuationState

    /** [degraded] — offline cache, improvised cover, or an expanded radius was used to get here. */
    data class ActiveGuidance(val shelter: ShelterToken, val route: TacticalRouteGeometry, val degraded: Boolean = false) : EvacuationState
    data class Arrived(val shelter: ShelterToken) : EvacuationState
    data class Compromised(val reason: String) : EvacuationState

    /**
     * Radial expansion + tag broadening both exhausted — no structure at all
     * nearby. [reasonRes] — «нічого нема» vs «довідник не вміє відрізнити»:
     * два різні речення, і обидва мусять бути обома мовами.
     */
    data class InPlaceDefense(@StringRes val reasonRes: Int) : EvacuationState
}

/** Normalized walking-route geometry plus tactical metadata for the evacuation card. */
data class TacticalRouteGeometry(
    val points: List<MapPayload.PointData>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val exposureRiskIndex: Double, // 0.0 (safe/quick) .. 1.0 (long exposure)
    val profile: RouteProfile,
    /** LEVIATHAN-1 — true when routed over the local tile graph (no server):
     *  flat-speed estimate, honest «приблизно, офлайн» in the card. */
    val offline: Boolean = false,
)

/**
 * Adapts [OsrmClient] to tactical evacuation routing: same keyless OSRM
 * backend, normalized into [TacticalRouteGeometry] with an abstract exposure
 * index derived from time-on-foot outdoors. Runs on [Dispatchers.IO]; a
 * failed/rate-limited response returns null (never a fabricated straight line).
 */
class SpatialRoutingService(
    private val osrm: OsrmClient = OsrmClient(),
    /**
     * LEVIATHAN-1 — the offline fallback: the road graph the map-matcher has
     * already cached around the operator, or null when none is wired
     * (previews/tests). The no-network moment IS the air-raid moment; a
     * shelter route must not die with the connection.
     */
    private val localGraph: (() -> local.phantom.companion.core.sensor.nav.RoadGraph?)? = null,
    /**
     * HORIZON II.1 — the learned GNSS-integrity prior. When present, the
     * offline shelter route prefers clean-sky corridors: the moment jamming
     * forces the OSRM fallback is exactly the moment routing around the
     * jammer's shadow matters most.
     */
    private val integrity: (() -> local.phantom.companion.core.sensor.nav.GnssIntegrityMap?)? = null,
    private val localRouter: local.phantom.companion.core.sensor.nav.LocalRouter =
        local.phantom.companion.core.sensor.nav.LocalRouter(),
    /**
     * УКРИТТЯ — kinetic-cover grid from real building footprints for a bbox,
     * or null (no data / no network / previews). When present, the offline
     * route hugs walls and the exposure index measures actual open ground
     * instead of guessing from walking time alone.
     */
    private val coverFetch: (suspend (Double, Double, Double, Double) -> local.phantom.companion.core.sensor.nav.CoverGrid?)? = null,
) {
    private var cachedCover: Pair<Long, local.phantom.companion.core.sensor.nav.CoverGrid>? = null

    suspend fun routeTo(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        profile: RouteProfile = RouteProfile.FOOT,
    ): TacticalRouteGeometry? = withContext(Dispatchers.IO) {
        val cover = coverFor(fromLat, fromLon, toLat, toLon)
        val res = osrm.route(profile, fromLat, fromLon, toLat, toLon)
        if (res != null) {
            return@withContext TacticalRouteGeometry(
                points = res.points.map { MapPayload.PointData(it.first, it.second) },
                distanceMeters = res.distanceMeters,
                durationSeconds = res.durationSeconds,
                exposureRiskIndex = exposureRiskIndex(res.durationSeconds, res.points, cover),
                profile = res.profile,
            )
        }
        // OSRM unreachable — route over the locally-cached road tiles. The
        // result is honest about itself: `offline = true` flows into the
        // evacuation card's degraded banner.
        val graph = localGraph?.invoke() ?: return@withContext null
        val prior = integrity?.invoke()
        // ТРИ ПРОФІЛІ, А НЕ ДВА. Тут стояло `profile != CAR`, тобто велосипед
        // евакуювався ПІШИМИ правилами: ігнорував односторонній рух і йшов
        // пішохідним темпом 1,3 м/с — тобто обіцяв утричі довшу дорогу до
        // укриття, ніж є. Це та сама помилка, що в `AppContainer`, лише
        // перекинута в інший бік.
        val local = localRouter.route(
            graph, fromLat, fromLon, toLat, toLon,
            when (profile) {
                RouteProfile.BIKE -> local.phantom.companion.core.sensor.nav.RoadProfile.TravelMode.BIKE
                RouteProfile.CAR -> local.phantom.companion.core.sensor.nav.RoadProfile.TravelMode.CAR
                RouteProfile.FOOT, RouteProfile.TRANSIT ->
                    local.phantom.companion.core.sensor.nav.RoadProfile.TravelMode.FOOT
            },
            integrity = prior,
            // Evacuation leans hard toward clean sky — a jammed corridor to a
            // shelter is worth a real detour to avoid. Сила більше не живе в
            // цьому файлі: [RouteCaution] — один тумблер, спільний зі
            // звичайним маршрутом, щоб два шляхи не розповзалися нарізно.
            jammingAvoidance = if (prior != null) CAUTION.jammingAvoidance else 0.0,
            // …and toward walls: open squares cost extra, the reported
            // numbers stay the real ones for the path chosen.
            extraCostFactor = cover?.let { g ->
                { lat, lon -> g.costFactorAt(lat, lon, CAUTION.coverAvoidance) }
            },
        ) ?: return@withContext null
        TacticalRouteGeometry(
            points = local.points.map { MapPayload.PointData(it.first, it.second) },
            distanceMeters = local.distanceMeters,
            durationSeconds = local.durationSeconds,
            exposureRiskIndex = exposureRiskIndex(local.durationSeconds, local.points, cover),
            profile = profile,
            offline = true,
        )
    }

    /**
     * Time-on-foot capped at 15 min, blended 50/50 with the MEASURED open
     * fraction of the path when a cover grid exists; time-only otherwise.
     */
    private fun exposureRiskIndex(
        durationSeconds: Double,
        points: List<Pair<Double, Double>>,
        cover: local.phantom.companion.core.sensor.nav.CoverGrid?,
    ): Double {
        val time = (durationSeconds / 900.0).coerceIn(0.0, 1.0)
        val grid = cover ?: return time
        if (grid.cellCount == 0 || points.size < 2) return time
        return (0.5 * time + 0.5 * grid.openFractionAlong(points)).coerceIn(0.0, 1.0)
    }

    private suspend fun coverFor(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
    ): local.phantom.companion.core.sensor.nav.CoverGrid? {
        val fetch = coverFetch ?: return null
        val pad = COVER_PAD_DEG
        val minLat = minOf(fromLat, toLat) - pad
        val maxLat = maxOf(fromLat, toLat) + pad
        val minLon = minOf(fromLon, toLon) - pad
        val maxLon = maxOf(fromLon, toLon) + pad
        // A shelter search spanning a whole oblast has no meaningful cover
        // story at building granularity — skip rather than hammer Overpass.
        if (maxLat - minLat > MAX_COVER_SPAN_DEG || maxLon - minLon > MAX_COVER_SPAN_DEG) return null
        val key = (Math.round(minLat * 500) shl 32) xor
            (Math.round(minLon * 500) and 0xFFFFFFFFL) xor
            (Math.round(maxLat * 500) * 31) xor Math.round(maxLon * 500)
        cachedCover?.let { (k, g) -> if (k == key) return g }
        val grid = runCatching { fetch(minLat, minLon, maxLat, maxLon) }.getOrNull() ?: return null
        cachedCover = key to grid
        return grid
    }

    private companion object {
        /**
         * Наскільки сильно евакуація ухиляється від глушилки й відкритої
         * землі. Числа не тут — вони в [RouteCaution], бо той самий тумблер
         * тепер тримає й головний маршрут («проведи мене туди»), і власникові
         * має вистачити одного рядка, щоб передумати за обидва.
         */
        val CAUTION = local.phantom.companion.core.sensor.nav.RouteCaution.EVACUATION
        const val COVER_PAD_DEG = 0.004
        const val MAX_COVER_SPAN_DEG = 0.06
    }
}
