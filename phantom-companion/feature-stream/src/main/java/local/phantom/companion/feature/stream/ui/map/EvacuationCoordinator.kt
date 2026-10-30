package local.phantom.companion.feature.stream.ui.map

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import local.phantom.companion.core.data.evac.ShelterRegistryVerdict
import local.phantom.companion.core.data.evac.ShelterRepository
import local.phantom.companion.core.data.evac.ShelterScanResult
import local.phantom.companion.core.data.evac.ShelterToken
import local.phantom.companion.core.data.evac.ShelterTruth
import local.phantom.companion.core.data.poi.PoiRepository
import local.phantom.companion.core.net.map.RouteProfile
import local.phantom.companion.feature.stream.R
import local.phantom.companion.feature.stream.ui.widget.MapPayload

/**
 * M-C05/C06 — the Tactical Evacuation Engine's driver, extracted verbatim
 * from the MapViewModel monolith (500-line rule). Owns the [EvacuationState]
 * machine; the route it produces is published through [MapRouteCoordinator],
 * because an evacuation corridor IS an active route («по дорозі» to a
 * shelter is the one moment that matters most).
 */
internal class EvacuationCoordinator(
    private val scope: CoroutineScope,
    private val shelterRepository: ShelterRepository?,
    private val poiRepository: PoiRepository,
    private val nav: MapRouteCoordinator,
    private val routingService: SpatialRoutingService,
    private val emitCameraBounds: (MapViewModel.CameraBounds) -> Unit,
) {
    private val _state = MutableStateFlow<EvacuationState>(EvacuationState.Idle)
    val state: StateFlow<EvacuationState> = _state.asStateFlow()

    /**
     * Clicking "Скласти маршрут до найближчого укриття" drives the engine
     * Idle → Scanning (offline-first, radially-expanding, tag-broadening
     * shelter lookup) → TacticalRouting (OSRM) → ActiveGuidance. Every
     * failure mode is a state, not a system notice: no assets anywhere
     * within the expanded search lands on InPlaceDefense, a route that
     * can't be drawn lands on Compromised. The EvacuationCard renders all
     * of it — no NoticePill involved.
     */
    fun routeToNearestShelter(fromLat: Double, fromLon: Double, radiusMeters: Int = 3000) {
        if (_state.value == EvacuationState.Scanning || _state.value == EvacuationState.TacticalRouting) return
        scope.launch {
            nav.setRouting(true)
            _state.value = EvacuationState.Scanning

            val scan = shelterRepository?.findNearest(fromLat, fromLon, radiusMeters)
                ?: legacyShelterScan(fromLat, fromLon, radiusMeters)

            val nearest: ShelterToken
            val degradedScan: Boolean
            when (scan) {
                is ShelterScanResult.NoAssetsFound -> {
                    _state.value = EvacuationState.InPlaceDefense(inPlaceReason())
                    nav.setRouting(false)
                    return@launch
                }
                is ShelterScanResult.SourceDown -> {
                    compromise(MapText.s(R.string.mapl_h_shelter_source_down))
                    nav.setRouting(false)
                    return@launch
                }
                is ShelterScanResult.Found -> {
                    nearest = scan.tokens.first()
                    degradedScan = scan.offline ||
                        !ShelterTruth.tier(nearest.assetType).certified ||
                        scan.radiusMeters > radiusMeters
                }
            }

            _state.value = EvacuationState.TacticalRouting
            val route = routingService.routeTo(fromLat, fromLon, nearest.lat, nearest.lon, RouteProfile.FOOT)
            if (route == null) {
                compromise(MapText.s(R.string.mapl_h_evac_route_failed, nearest.name))
                nav.setRouting(false)
                return@launch
            }

            nav.stopPlanSession() // the evacuation route replaces any plan session
            nav.publishExternalRoute(
                line = MapPayload.PolylineData(points = route.points, color = RouteRole.ROUTE.id, width = 7f),
                info = MapViewModel.RouteInfo(
                    distanceMeters = route.distanceMeters,
                    durationSeconds = route.durationSeconds,
                    profile = route.profile,
                    destLat = nearest.lat,
                    destLon = nearest.lon,
                ),
                corridor = route.points.map { it.lat to it.lon },
            )
            // LEVIATHAN-1 — an offline-routed corridor is a degraded corridor:
            // the card must say the path is approximate before anyone runs it.
            _state.value =
                EvacuationState.ActiveGuidance(nearest, route, degraded = degradedScan || route.offline)

            val lats = route.points.map { it.lat } + listOf(fromLat, nearest.lat)
            val lons = route.points.map { it.lon } + listOf(fromLon, nearest.lon)
            emitCameraBounds(
                MapViewModel.CameraBounds(lats.min(), lons.min(), lats.max(), lons.max()),
            )

            nav.setRouting(false)
        }
    }

    /** Falls back to a plain (non-cached, no tag-broadening) shelter-only query when no offline-first repo is wired. */
    private suspend fun legacyShelterScan(fromLat: Double, fromLon: Double, radiusMeters: Int): ShelterScanResult {
        val sheltersList = poiRepository
            .getNearby(fromLat, fromLon, radiusMeters, ShelterTruth.BOMB_SHELTER_SELECTOR)
            .filter { ShelterTruth.isBombShelter(it.tags) }
        if (sheltersList.isEmpty()) return ShelterScanResult.NoAssetsFound
        val tokens = sheltersList
            .map { ShelterToken(it.id, it.name, it.lat, it.lon, haversine(fromLat, fromLon, it.lat, it.lon)) }
            .sortedBy { it.distanceMeters }
        return ShelterScanResult.Found(tokens, offline = false, radiusMeters = radiusMeters)
    }

    /**
     * «Сховищ нема» можна казати лише коли довідник умів їх упізнати. Пак без
     * тега-розрізнювача не бачить різниці між бомбосховищем і альтанкою, і
     * мовчати про це — та сама впевнена вигадка, лише навиворіт.
     */
    private fun inPlaceReason(): Int =
        if (poiRepository.offlineShelterRegistry() == ShelterRegistryVerdict.PRESENT) {
            R.string.map_evac_in_place_protocol
        } else {
            R.string.map_evac_registry_blind
        }

    private fun compromise(reason: String) {
        _state.value = EvacuationState.Compromised(reason)
    }

    /** A precondition failed before scanning could even start (e.g. no operator fix) — same card, no toast. */
    fun reportBlocked(reason: String) {
        compromise(reason)
    }

    /** Live-tracking: called as the operator's fix moves while guidance is active. */
    fun updateGuidanceProgress(lat: Double, lon: Double) {
        val guidance = _state.value as? EvacuationState.ActiveGuidance ?: return
        val distanceToShelter = haversine(lat, lon, guidance.shelter.lat, guidance.shelter.lon)
        if (distanceToShelter <= ARRIVAL_RADIUS_METERS_THRESHOLD) {
            _state.value = EvacuationState.Arrived(guidance.shelter)
        }
    }

    /** Cancel/clear an in-progress or finished evacuation, dropping the route overlay too. */
    fun clear() {
        _state.value = EvacuationState.Idle
        nav.clearRoute()
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return 2 * r * Math.asin(Math.sqrt(a.coerceIn(0.0, 1.0)))
    }

    private companion object {
        /** Operator arrives within this radius of the guidance shelter → Arrived. */
        const val ARRIVAL_RADIUS_METERS_THRESHOLD = 40.0
    }
}
