package local.phantom.companion.core.nav

import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * M-L01 / M-L03 — Place Context Detector & Adaptive Sentry Engine.
 *
 * Infers semantic operational environment (Home, Transit, Unknown Territory, Danger Sector)
 * and generates situational triggers such as Home Sentry monitoring or Transit HUD.
 */
class PlaceContextEngine {

    enum class SemanticPlaceMode {
        HOME_BASE,
        WORK_OFFICE,
        TRANSIT_ACTIVE,
        UNFAMILIAR_TERRITORY,
        DANGER_SECTOR,
    }

    data class GeoAnchor(
        val name: String,
        val lat: Double,
        val lon: Double,
        val radiusMeters: Double = 150.0,
    )

    data class PlaceContextState(
        val currentLat: Double,
        val currentLon: Double,
        val mode: SemanticPlaceMode,
        val activeAnchorName: String?,
        val speedKmh: Double,
        val homeSentryActive: Boolean,
        val suggestedMapMode: String,
        val summaryUk: String,
    )

    /**
     * Infers place context and sentry posture based on current telemetry, known anchors, and threat state.
     */
    fun evaluateContext(
        currentLat: Double,
        currentLon: Double,
        speedMs: Float = 0f,
        homeAnchor: GeoAnchor? = null,
        workAnchor: GeoAnchor? = null,
        isAlertActiveInArea: Boolean = false,
        distanceToNearestKnownTrackClusterMeters: Double? = null,
    ): PlaceContextState {
        val speedKmh = (speedMs * 3.6).coerceAtLeast(0.0)

        val distToHome = homeAnchor?.let { haversineMeters(currentLat, currentLon, it.lat, it.lon) }
        val distToWork = workAnchor?.let { haversineMeters(currentLat, currentLon, it.lat, it.lon) }

        val isAtHome = distToHome != null && distToHome <= (homeAnchor?.radiusMeters ?: 150.0)
        val isAtWork = distToWork != null && distToWork <= (workAnchor?.radiusMeters ?: 150.0)

        val mode: SemanticPlaceMode
        val activeAnchor: String?
        val mapMode: String
        val homeSentry: Boolean
        val summary: String

        when {
            isAlertActiveInArea -> {
                mode = SemanticPlaceMode.DANGER_SECTOR
                activeAnchor = if (isAtHome) homeAnchor?.name else if (isAtWork) workAnchor?.name else null
                mapMode = "TACTICAL"
                homeSentry = !isAtHome && homeAnchor != null
                summary = "У вашому районі тривога."
            }
            isAtHome -> {
                mode = SemanticPlaceMode.HOME_BASE
                activeAnchor = homeAnchor?.name ?: "Дім"
                mapMode = "ZEN_MINIMAL"
                homeSentry = false
                summary = "Оператор на домашній базі (${homeAnchor?.name ?: "Дім"}). Дистанційна варта вимкнена."
            }
            isAtWork -> {
                mode = SemanticPlaceMode.WORK_OFFICE
                activeAnchor = workAnchor?.name ?: "Робота"
                mapMode = "ZEN_MINIMAL"
                homeSentry = homeAnchor != null
                summary = "Оператор на локації ${workAnchor?.name ?: "Робота"}. Дистанційна варта дому активна."
            }
            speedKmh >= 15.0 -> {
                mode = SemanticPlaceMode.TRANSIT_ACTIVE
                activeAnchor = null
                mapMode = "TRANSIT"
                homeSentry = homeAnchor != null
                summary = "Оператор у транзиті (швидкість ${speedKmh.roundToInt()} км/год). Активовано навігаційний HUD."
            }
            distanceToNearestKnownTrackClusterMeters != null && distanceToNearestKnownTrackClusterMeters > 5000.0 -> {
                mode = SemanticPlaceMode.UNFAMILIAR_TERRITORY
                activeAnchor = null
                mapMode = "RECON"
                homeSentry = homeAnchor != null
                summary = "Незнайома місцевість (> 5 км від звичних маршрутів). Активовано режим розвідки та кешування."
            }
            else -> {
                mode = SemanticPlaceMode.TRANSIT_ACTIVE
                activeAnchor = null
                mapMode = "TRANSIT"
                homeSentry = homeAnchor != null
                summary = "Поточна локація поза базовими якорями. Варта дому активна."
            }
        }

        return PlaceContextState(
            currentLat = currentLat,
            currentLon = currentLon,
            mode = mode,
            activeAnchorName = activeAnchor,
            speedKmh = speedKmh,
            homeSentryActive = homeSentry,
            suggestedMapMode = mapMode,
            summaryUk = summary,
        )
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * kotlin.math.asin(kotlin.math.sqrt(a.coerceIn(0.0, 1.0)))
    }
}
