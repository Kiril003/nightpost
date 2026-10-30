package local.phantom.companion.core.nav

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Unified Spatial Search Engine for PHANTOM Companion.
 * Fuses category intent, tactical resilience assets, Vault saved pins, and coordinates.
 */
class SpatialSearchEngine {

    enum class ResultKind {
        CATEGORY_INTENT,
        SAVED_VAULT,
        COORDINATE_TARGET,
        TACTICAL_ASSET,
        RECENT_QUERY,
    }

    data class SavedTarget(
        val id: String,
        val name: String,
        val lat: Double,
        val lon: Double,
        val category: String? = null,
    )

    data class SearchItem(
        val id: String,
        val title: String,
        val subtitle: String,
        val icon: String,
        val kind: ResultKind,
        val lat: Double? = null,
        val lon: Double? = null,
        val distanceMeters: Int? = null,
        val bearingDeg: Int? = null,
        val compassDirectionUk: String? = null,
        val clockDirectionUk: String? = null,
        val overpassSelector: String? = null,
        val tacticalReasonUk: String? = null,
        val isWartimeCritical: Boolean = false,
    )

    data class SearchResult(
        val query: String,
        val directCategoryMatch: OsmSpatialUniverse.SpatialCategory?,
        val items: List<SearchItem>,
        val summaryUk: String,
    )

    /**
     * Executes multi-tiered ranked spatial search.
     */
    fun search(
        query: String,
        operatorLat: Double? = null,
        operatorLon: Double? = null,
        operatorHeadingDeg: Float? = null,
        savedTargets: List<SavedTarget> = emptyList(),
        recentQueries: List<String> = emptyList(),
    ): SearchResult {
        val q = query.trim()
        if (q.isEmpty()) {
            // Empty query: return quick filters + saved targets
            val quickItems = OsmSpatialUniverse.quickFilters.map { cat ->
                SearchItem(
                    id = "cat_${cat.id}",
                    title = cat.labelUk,
                    subtitle = cat.tacticalUsageReasonUk,
                    icon = cat.emoji,
                    kind = ResultKind.CATEGORY_INTENT,
                    overpassSelector = cat.selector,
                    tacticalReasonUk = cat.tacticalUsageReasonUk,
                    isWartimeCritical = cat.isWartimeCritical,
                )
            }

            val savedItems = savedTargets.map { target ->
                val dist = if (operatorLat != null && operatorLon != null) {
                    haversineMeters(operatorLat, operatorLon, target.lat, target.lon).roundToInt()
                } else null
                val bearing = if (operatorLat != null && operatorLon != null) {
                    calculateBearing(operatorLat, operatorLon, target.lat, target.lon)
                } else null
                val compass = bearing?.let { compassQuadrantUk(it) }
                val clock = if (bearing != null && operatorHeadingDeg != null) {
                    calculateClockPosition(operatorHeadingDeg.toDouble(), bearing.toDouble())
                } else null

                val sub = if (dist != null && compass != null) {
                    "$dist м · $compass ${clock ?: ""}".trim()
                } else "Збережено у Vault"

                SearchItem(
                    id = "vault_${target.id}",
                    title = target.name,
                    subtitle = sub,
                    icon = "📍",
                    kind = ResultKind.SAVED_VAULT,
                    lat = target.lat,
                    lon = target.lon,
                    distanceMeters = dist,
                    bearingDeg = bearing,
                    compassDirectionUk = compass,
                    clockDirectionUk = clock,
                )
            }

            return SearchResult(
                query = "",
                directCategoryMatch = null,
                items = quickItems + savedItems,
                summaryUk = "Оберіть швидку категорію стійкості або збережене місце з Vault.",
            )
        }

        // 1. Direct coordinate check (e.g. "50.4501, 30.5201")
        val coordMatch = parseCoordinates(q)
        if (coordMatch != null) {
            val (cLat, cLon) = coordMatch
            val dist = if (operatorLat != null && operatorLon != null) {
                haversineMeters(operatorLat, operatorLon, cLat, cLon).roundToInt()
            } else null
            val bearing = if (operatorLat != null && operatorLon != null) {
                calculateBearing(operatorLat, operatorLon, cLat, cLon)
            } else null
            val compass = bearing?.let { compassQuadrantUk(it) }

            val item = SearchItem(
                id = "coord_${cLat}_$cLon",
                title = "Координати: ${"%.5f".format(cLat)}, ${"%.5f".format(cLon)}",
                subtitle = if (dist != null) "Дистанція: $dist м · ${compass ?: ""}" else "Пряма географічна точка",
                icon = "🎯",
                kind = ResultKind.COORDINATE_TARGET,
                lat = cLat,
                lon = cLon,
                distanceMeters = dist,
                bearingDeg = bearing,
                compassDirectionUk = compass,
            )

            return SearchResult(
                query = q,
                directCategoryMatch = null,
                items = listOf(item),
                summaryUk = "Знайдено прямі геокоординати (${item.title}).",
            )
        }

        // 2. Category intent matching
        val matchedCategories = OsmSpatialUniverse.findMatchingCategories(q)
        val directCategory = OsmSpatialUniverse.findByKeyword(q)

        val categoryItems = matchedCategories.map { cat ->
            SearchItem(
                id = "cat_${cat.id}",
                title = cat.labelUk,
                subtitle = cat.tacticalUsageReasonUk,
                icon = cat.emoji,
                kind = ResultKind.CATEGORY_INTENT,
                overpassSelector = cat.selector,
                tacticalReasonUk = cat.tacticalUsageReasonUk,
                isWartimeCritical = cat.isWartimeCritical,
            )
        }

        // 3. Match saved places
        val normQ = OsmSpatialUniverse.normalize(q)
        val matchedSaved = savedTargets.filter { target ->
            val nName = OsmSpatialUniverse.normalize(target.name)
            nName.contains(normQ) || (target.category != null && OsmSpatialUniverse.normalize(target.category).contains(normQ))
        }.map { target ->
            val dist = if (operatorLat != null && operatorLon != null) {
                haversineMeters(operatorLat, operatorLon, target.lat, target.lon).roundToInt()
            } else null
            val bearing = if (operatorLat != null && operatorLon != null) {
                calculateBearing(operatorLat, operatorLon, target.lat, target.lon)
            } else null
            val compass = bearing?.let { compassQuadrantUk(it) }
            val clock = if (bearing != null && operatorHeadingDeg != null) {
                calculateClockPosition(operatorHeadingDeg.toDouble(), bearing.toDouble())
            } else null

            val sub = if (dist != null && compass != null) {
                "$dist м · $compass ${clock ?: ""}".trim()
            } else "Збережено у Vault"

            SearchItem(
                id = "vault_${target.id}",
                title = target.name,
                subtitle = sub,
                icon = "📍",
                kind = ResultKind.SAVED_VAULT,
                lat = target.lat,
                lon = target.lon,
                distanceMeters = dist,
                bearingDeg = bearing,
                compassDirectionUk = compass,
                clockDirectionUk = clock,
            )
        }

        val allItems = categoryItems + matchedSaved
        val summary = if (allItems.isNotEmpty()) {
            "Знайдено ${allItems.size} варіантів (категорії стійкості та збережені точки)."
        } else {
            "За запитом «$q» результатів у локальному індексі не знайдено."
        }

        return SearchResult(
            query = q,
            directCategoryMatch = directCategory,
            items = allItems,
            summaryUk = summary,
        )
    }

    private fun parseCoordinates(text: String): Pair<Double, Double>? {
        val parts = text.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size == 2) {
            val lat = parts[0].toDoubleOrNull()
            val lon = parts[1].toDoubleOrNull()
            if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) {
                return lat to lon
            }
        }
        return null
    }

    internal fun calculateBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Int {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val deltaLambda = Math.toRadians(lon2 - lon1)
        val y = sin(deltaLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(deltaLambda)
        val theta = atan2(y, x)
        return ((Math.toDegrees(theta) + 360.0) % 360.0).roundToInt()
    }

    internal fun compassQuadrantUk(bearingDeg: Int): String = when (((bearingDeg + 22) % 360) / 45) {
        0 -> "Північ"
        1 -> "Північний Схід"
        2 -> "Схід"
        3 -> "Південний Схід"
        4 -> "Південь"
        5 -> "Південний Захід"
        6 -> "Захід"
        7 -> "Північний Захід"
        else -> "Північ"
    }

    internal fun calculateClockPosition(headingDeg: Double, bearingDeg: Double): String {
        val diff = ((bearingDeg - headingDeg + 360.0) % 360.0)
        val hour = (((diff + 15.0) / 30.0).toInt() % 12).let { if (it == 0) 12 else it }
        return "на $hour год"
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
