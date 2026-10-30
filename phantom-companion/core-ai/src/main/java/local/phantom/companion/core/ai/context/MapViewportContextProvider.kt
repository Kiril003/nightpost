package local.phantom.companion.core.ai.context

import java.util.Locale

/**
 * R4.2 — shared gaze. While the map is open, each AI turn gets one line
 * describing where the operator is LOOKING (not where their GPS is): the
 * camera centre, zoom, reverse-geocoded locality, count of visible POIs,
 * and the active PHANTOM layers. So "а що тут?" while panning Відень
 * answers about Відень, not about the operator's position.
 *
 * Pure-Kotlin contract (pattern: [OfflineRegionsContextProvider]). The
 * mutable snapshot is published by the map surface (feature-stream) and the
 * `current()` impl in `:app` just reads the latest value — returning null
 * when the map is closed, so the line vanishes from context.
 */
fun interface MapViewportContextProvider {
    /** @return the live viewport, or null when the map is not open. */
    fun current(): MapViewportSnapshot?
}

/**
 * What the operator's map camera currently frames. All fields are locally
 * available (no network at read time) except [locality], which the map
 * surface fills from its already-throttled reverse geocoder.
 */
data class MapViewportSnapshot(
    val centerLat: Double,
    val centerLon: Double,
    val zoom: Double,
    val locality: String? = null,
    val visiblePoiCount: Int = 0,
    val activeLayerTitles: List<String> = emptyList(),
    val followingOperator: Boolean = false,
    /** M-I02 — a point the operator long-pressed and hasn't dismissed yet. */
    val selectedPointLabel: String? = null,
    /** M-I02 — the active operator-drawn route ("4.2 км · 52 хв, пішки"), if any. */
    val activeRoute: String? = null,
    /** M-I02 — the "поруч" category flooding the map right now, if any. */
    val activeNearbyCategory: String? = null,
    /** M-C01 — the oblast for which the threat brief is currently visible. */
    val activeThreatBriefOblast: String? = null,
    /** NAV-2 — live turn-by-turn guidance state while the operator is moving. */
    val navigation: NavigationSnapshot? = null,
)

/**
 * NAV-2 — what live guidance knows this instant, folded into the shared
 * gaze so the operator can talk to PHANTOM mid-drive and PHANTOM sees the
 * current step, remaining way, ETA and speed without any tool call.
 */
data class NavigationSnapshot(
    /** Short Ukrainian action for the next maneuver («поверніть ліворуч на Хрещатик»). */
    val stepPhrase: String,
    val distanceToManeuverM: Int,
    val remainingDistanceM: Int,
    val remainingDurationS: Int,
    val speedKmh: Int? = null,
    val limitKmh: Int? = null,
    val offRoute: Boolean = false,
)

/**
 * Formats the viewport for the AI prompt preamble.
 *
 * - null → null (map closed; caller drops the line).
 * - open → `[MAP_VIEW: центр 50.4501,30.5234 z14 · Хрещатик · POI 12 ·
 *   шари: 3 кафе, камери · стеження]` (locality / POI / layers / follow all
 *   omitted when absent, so the line never claims data it lacks — Law 1).
 */
object MapViewportContextFormatter {
    fun format(snapshot: MapViewportSnapshot?, english: Boolean = false): String? {
        if (snapshot == null) return null
        if (english) return formatEn(snapshot)
        val parts = buildList {
            add(
                "центр " + String.format(
                    Locale.US, "%.4f,%.4f z%.0f",
                    snapshot.centerLat, snapshot.centerLon, snapshot.zoom,
                ),
            )
            snapshot.locality?.takeIf { it.isNotBlank() }?.let { add(it) }
            if (snapshot.visiblePoiCount > 0) add("POI ${snapshot.visiblePoiCount}")
            if (snapshot.activeLayerTitles.isNotEmpty()) {
                add("шари: " + snapshot.activeLayerTitles.take(4).joinToString(", "))
            }
            if (snapshot.followingOperator) add("стеження")
            snapshot.selectedPointLabel?.takeIf { it.isNotBlank() }?.let { add("обрана точка: $it") }
            snapshot.activeRoute?.takeIf { it.isNotBlank() }?.let { add("маршрут: $it") }
            snapshot.activeNearbyCategory?.takeIf { it.isNotBlank() }?.let { add("поруч: $it") }
            snapshot.activeThreatBriefOblast?.takeIf { it.isNotBlank() }?.let { add("тривога: $it") }
            snapshot.navigation?.let { nav ->
                add(
                    buildString {
                        append("НАВІГАЦІЯ ЖИВА: ").append(nav.stepPhrase)
                        if (nav.distanceToManeuverM > 30) append(" через ${nav.distanceToManeuverM} м")
                        append(" · залишилось ${nav.remainingDistanceM} м · ${nav.remainingDurationS / 60} хв")
                        nav.speedKmh?.let { v ->
                            append(" · швидкість $v км/год")
                            nav.limitKmh?.let { append(" (ліміт $it)") }
                        }
                        if (nav.offRoute) append(" · ПОЗА МАРШРУТОМ")
                    },
                )
            }
        }
        return "[MAP_VIEW: " + parts.joinToString(" · ") + "]"
    }

    private fun formatEn(snapshot: MapViewportSnapshot): String {
        val parts = buildList {
            add(
                "center " + String.format(
                    Locale.US, "%.4f,%.4f z%.0f",
                    snapshot.centerLat, snapshot.centerLon, snapshot.zoom,
                ),
            )
            snapshot.locality?.takeIf { it.isNotBlank() }?.let { add(it) }
            if (snapshot.visiblePoiCount > 0) add("POI ${snapshot.visiblePoiCount}")
            if (snapshot.activeLayerTitles.isNotEmpty()) {
                add("layers: " + snapshot.activeLayerTitles.take(4).joinToString(", "))
            }
            if (snapshot.followingOperator) add("following")
            snapshot.selectedPointLabel?.takeIf { it.isNotBlank() }?.let { add("selected point: $it") }
            snapshot.activeRoute?.takeIf { it.isNotBlank() }?.let { add("route: $it") }
            snapshot.activeNearbyCategory?.takeIf { it.isNotBlank() }?.let { add("nearby: $it") }
            snapshot.activeThreatBriefOblast?.takeIf { it.isNotBlank() }?.let { add("alert: $it") }
            snapshot.navigation?.let { nav ->
                add(
                    buildString {
                        append("NAVIGATION LIVE: ").append(nav.stepPhrase)
                        if (nav.distanceToManeuverM > 30) append(" in ${nav.distanceToManeuverM} m")
                        append(" · ${nav.remainingDistanceM} m left · ${nav.remainingDurationS / 60} min")
                        nav.speedKmh?.let { v ->
                            append(" · speed $v km/h")
                            nav.limitKmh?.let { append(" (limit $it)") }
                        }
                        if (nav.offRoute) append(" · OFF ROUTE")
                    },
                )
            }
        }
        return "[MAP_VIEW: " + parts.joinToString(" · ") + "]"
    }
}
