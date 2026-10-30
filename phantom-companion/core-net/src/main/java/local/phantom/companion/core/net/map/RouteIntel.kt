package local.phantom.companion.core.net.map

import local.phantom.companion.core.net.geosrc.GeoResult
import local.phantom.companion.core.net.geosrc.SourceUnavailable
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

/**
 * NAV-1 — RouteIntel: the corridor analyzer. Samples the route polyline,
 * enriches the corridor from Overpass (road class, surface, lighting,
 * posted limits, speed cameras) and open-elevation (ascent/descent), and
 * reports honest shares: every fraction is over MATCHED samples, and
 * [RouteIntelReport.coverage] says how much of the route the data actually
 * covers — 0.0 coverage renders as «дані коридору недоступні», never as a
 * confident-looking guess (Law 3).
 *
 * Shared by the compare sheet AND the `route.compare` tool-verb — one code
 * path for finger and phantom (architecture rule 2).
 */

/** A speed camera near the corridor, with its posted limit tag when mapped. */
data class CameraSighting(val lat: Double, val lon: Double, val maxspeed: String?)

/**
 * A barrier, gate or checkpoint along the corridor.
 *
 * [bearingDeg] — курс ШЛЯХУ крізь вузол, компасні градуси, або null. Сам вузол
 * `barrier=*` напрямку не несе: він займає точку на дорозі, а орієнтацію дає
 * лише дорога. Null тут — це «курсу немає», і знак тоді малює саму фігурку,
 * без бруса: брус без курсу збрехав би про те, як шлагбаум стоїть.
 */
data class BarrierSighting(
    val lat: Double,
    val lon: Double,
    val kind: String,
    val access: String?,
    val bearingDeg: Double? = null,
)

data class RouteIntelReport(
    /** Human road-class label → share of matched samples, descending. */
    val classShares: List<Pair<String, Double>>,
    /** Human surface label → share of samples that carry a surface tag. */
    val surfaceShares: List<Pair<String, Double>>,
    /** Fraction of lit=* samples that are lit; null when nothing is tagged. */
    val litFraction: Double?,
    /** Highest posted limit (km/h) seen on matched ways; null if untagged. */
    val maxspeedMax: Int?,
    /**
     * NAV-2 — posted limits along the corridor: (fraction along 0..1 →
     * km/h) for every sample whose matched way carries a maxspeed tag.
     * Live guidance reads the limit at the operator's position from this;
     * empty = honestly untagged, the HUD shows speed without a limit.
     */
    val limitSamples: List<Pair<Double, Int>> = emptyList(),
    /** Fraction of samples matched to any highway way (data coverage). */
    val coverage: Double,
    val cameras: List<CameraSighting>,
    /** Barriers (lift_gate, gate, bollard, toll_booth) sighted along corridor. */
    val barriers: List<BarrierSighting> = emptyList(),
    /** Metres a.s.l. along the sampled route; null = no profile (see [elevationOutage]). */
    val elevations: List<Double>?,
    val ascentMeters: Double?,
    val descentMeters: Double?,
    /**
     * Why there is no profile. Non-null = «джерело мовчить»; null with a null
     * [ascentMeters] = the route genuinely has no climb worth reporting.
     */
    val elevationOutage: SourceUnavailable? = null,
)

class RouteIntel(
    private val overpass: OverpassClient = OverpassClient(),
    private val elevation: ElevationClient = ElevationClient(),
) {
    suspend fun analyze(points: List<Pair<Double, Double>>): RouteIntelReport? {
        if (points.size < 2) return null
        val samples = samplePolyline(points, maxSamples = CORRIDOR_SAMPLES)

        val elements = overpass.fetchCorridor(
            polyline = samples,
            wayRadiusMeters = WAY_RADIUS_M,
            cameraRadiusMeters = CAMERA_RADIUS_M,
        )
        val ways = elements.filter {
            it.type == "way" && it.tags?.get("highway") != null && !it.geometry.isNullOrEmpty()
        }
        val cameras = elements
            .filter { it.type == "node" && it.tags?.get("highway") == "speed_camera" }
            .mapNotNull { n ->
                val la = n.lat ?: return@mapNotNull null
                val lo = n.lon ?: return@mapNotNull null
                CameraSighting(la, lo, n.tags?.get("maxspeed"))
            }
        val barrierNodes = elements.filter { it.type == "node" && it.tags?.get("barrier") != null }
        // Радіус шляхів (30 м) вужчий за радіус барʼєрів (150 м), тож дорога
        // під шлагбаумом у коридорну відповідь могла й не потрапити. Один
        // додатковий запит на всіх сліпих — і лише коли такі справді є.
        val blindNodes = barrierNodes.filter { barrierBearingDeg(it, ways) == null }.map { it.id }
        val aimedWays = if (blindNodes.isEmpty()) ways else ways + overpass.fetchWaysThroughNodes(blindNodes)
        val barriers = barrierNodes.mapNotNull { n ->
            val la = n.lat ?: return@mapNotNull null
            val lo = n.lon ?: return@mapNotNull null
            val kind = n.tags?.get("barrier") ?: return@mapNotNull null
            BarrierSighting(la, lo, kind, n.tags?.get("access"), barrierBearingDeg(n, aimedWays))
        }

        // Attribute each equidistant sample to its nearest way: sample share
        // ≈ distance share, without paying for exact per-way lengths.
        val matchedByIndex = samples.mapIndexed { i, (la, lo) -> i to nearestWay(la, lo, ways, MATCH_MAX_M) }
        val matched = matchedByIndex.mapNotNull { it.second }
        val denom = (samples.size - 1).coerceAtLeast(1).toDouble()
        val limitSamples = matchedByIndex.mapNotNull { (i, w) ->
            w?.tags?.get("maxspeed")?.filter(Char::isDigit)?.toIntOrNull()
                ?.let { (i / denom) to it }
        }
        val litValues = matched.mapNotNull { it.tags?.get("lit") }
        val report = RouteIntelReport(
            classShares = tallyShares(matched.mapNotNull { it.tags?.get("highway")?.let(::roadClassLabel) }),
            surfaceShares = tallyShares(matched.mapNotNull { it.tags?.get("surface")?.let(::surfaceLabel) }),
            litFraction = litValues.takeIf { it.isNotEmpty() }
                ?.let { vals -> vals.count { it != "no" }.toDouble() / vals.size },
            maxspeedMax = matched
                .mapNotNull { it.tags?.get("maxspeed")?.filter(Char::isDigit)?.toIntOrNull() }
                .maxOrNull(),
            limitSamples = limitSamples,
            coverage = if (samples.isEmpty()) 0.0 else matched.size.toDouble() / samples.size,
            cameras = cameras,
            barriers = barriers,
            elevations = null,
            ascentMeters = null,
            descentMeters = null,
        )

        return when (val els = elevation.lookupResult(samples)) {
            is GeoResult.Ok -> {
                val (ascent, descent) = elevationGain(els.value)
                report.copy(elevations = els.value, ascentMeters = ascent, descentMeters = descent)
            }
            is GeoResult.Unavailable -> report.copy(elevationOutage = els.reason)
        }
    }

    private companion object {
        const val CORRIDOR_SAMPLES = 48 // bounds both the Overpass URL and lookups
        const val WAY_RADIUS_M = 30
        const val CAMERA_RADIUS_M = 150
        const val MATCH_MAX_M = 35.0
    }
}

/** Human (uk) label for an OSM highway class — shared by sheet and tool. */
fun roadClassLabel(cls: String): String = when (cls) {
    "motorway", "motorway_link", "trunk", "trunk_link" -> "магістраль"
    "primary", "primary_link" -> "головна"
    "secondary", "secondary_link" -> "другорядна"
    "tertiary", "tertiary_link" -> "третинна"
    "residential", "living_street" -> "житлова"
    "service" -> "проїзд"
    "unclassified" -> "місцева"
    "track" -> "ґрунтівка"
    "path", "footway", "pedestrian", "steps", "bridleway" -> "стежка/тротуар"
    "cycleway" -> "велодоріжка"
    else -> cls
}

/** Human (uk) label for an OSM surface tag — shared by sheet and tool. */
fun surfaceLabel(s: String): String = when (s) {
    "asphalt", "paved", "concrete", "concrete:plates", "paving_stones", "sett" -> "тверде покриття"
    "unpaved", "ground", "dirt", "gravel", "fine_gravel", "compacted", "grass", "sand", "mud" -> "ґрунт/гравій"
    "cobblestone" -> "бруківка"
    else -> s
}

/**
 * Equidistant samples along [points], endpoints always included, at most
 * [maxSamples] and never denser than [minStepMeters].
 */
internal fun samplePolyline(
    points: List<Pair<Double, Double>>,
    maxSamples: Int,
    minStepMeters: Double = 25.0,
): List<Pair<Double, Double>> {
    if (points.size <= 2 || maxSamples <= 2) return listOf(points.first(), points.last())
    val total = points.zipWithNext().sumOf { (a, b) ->
        haversineMeters(a.first, a.second, b.first, b.second)
    }
    if (total <= 0.0) return listOf(points.first(), points.last())
    val step = max(minStepMeters, total / (maxSamples - 1))
    val out = mutableListOf(points.first())
    var travelled = 0.0
    var nextAt = step
    for ((a, b) in points.zipWithNext()) {
        val segLen = haversineMeters(a.first, a.second, b.first, b.second)
        if (segLen <= 0.0) continue
        while (nextAt <= travelled + segLen && out.size < maxSamples - 1) {
            val t = (nextAt - travelled) / segLen
            out += (a.first + (b.first - a.first) * t) to (a.second + (b.second - a.second) * t)
            nextAt += step
        }
        travelled += segLen
    }
    out += points.last()
    return out
}

/**
 * Курс дороги крізь вузол барʼєра, компасні градуси, або null.
 *
 * Спершу за ІДЕНТИФІКАТОРОМ вершини (`out geom` віддає і `nodes`, і
 * `geometry`) — це точна відповідь. Якщо жоден шлях вузла не називає, лишається
 * найближчий сегмент у межах [BARRIER_ON_WAY_M]: шлагбаум стоїть на дорозі, а
 * не поруч, тож поріг тут вузький навмисно. Далі — чесне null.
 */
internal fun barrierBearingDeg(
    node: OverpassElement,
    ways: List<OverpassElement>,
    maxMeters: Double = BARRIER_ON_WAY_M,
): Double? {
    for (w in ways) {
        val g = w.geometry ?: continue
        val at = w.nodes?.indexOf(node.id) ?: -1
        if (at < 0 || at >= g.size || g.size < 2) continue
        val a = g[(at - 1).coerceAtLeast(0)]
        val b = g[(at + 1).coerceAtMost(g.size - 1)]
        if (a.lat == b.lat && a.lon == b.lon) continue
        return bearingDegBetween(a.lat, a.lon, b.lat, b.lon)
    }
    val lat = node.lat ?: return null
    val lon = node.lon ?: return null
    var best: Pair<OverpassCenter, OverpassCenter>? = null
    var bestD = maxMeters
    for (w in ways) {
        val g = w.geometry ?: continue
        for (i in 0 until g.size - 1) {
            val d = distanceToSegmentMeters(lat, lon, g[i].lat, g[i].lon, g[i + 1].lat, g[i + 1].lon)
            if (d < bestD) {
                bestD = d
                best = g[i] to g[i + 1]
            }
        }
    }
    val (a, b) = best ?: return null
    return bearingDegBetween(a.lat, a.lon, b.lat, b.lon)
}

/** Ближче за це до сегмента — барʼєр СТОЇТЬ на цьому шляху. */
internal const val BARRIER_ON_WAY_M = 8.0

internal fun bearingDegBetween(
    lat1: Double,
    lon1: Double,
    lat2: Double,
    lon2: Double,
): Double {
    val p1 = Math.toRadians(lat1)
    val p2 = Math.toRadians(lat2)
    val dl = Math.toRadians(lon2 - lon1)
    val y = kotlin.math.sin(dl) * cos(p2)
    val x = cos(p1) * kotlin.math.sin(p2) - kotlin.math.sin(p1) * cos(p2) * cos(dl)
    return (Math.toDegrees(kotlin.math.atan2(y, x)) + 360.0) % 360.0
}

/** Nearest highway way within [maxMeters] of the sample, else null. */
internal fun nearestWay(
    lat: Double,
    lon: Double,
    ways: List<OverpassElement>,
    maxMeters: Double,
): OverpassElement? {
    var best: OverpassElement? = null
    var bestD = maxMeters
    for (w in ways) {
        val g = w.geometry ?: continue
        for (i in 0 until g.size - 1) {
            val d = distanceToSegmentMeters(lat, lon, g[i].lat, g[i].lon, g[i + 1].lat, g[i + 1].lon)
            if (d < bestD) {
                bestD = d
                best = w
            }
        }
    }
    return best
}

/** Planar (equirectangular) point-to-segment distance — fine at ≤50 m scales. */
internal fun distanceToSegmentMeters(
    pLat: Double, pLon: Double,
    aLat: Double, aLon: Double,
    bLat: Double, bLon: Double,
): Double {
    val kx = cos(Math.toRadians((aLat + bLat) / 2)) * 111_320.0
    val ky = 110_540.0
    val px = (pLon - aLon) * kx
    val py = (pLat - aLat) * ky
    val bx = (bLon - aLon) * kx
    val by = (bLat - aLat) * ky
    val len2 = bx * bx + by * by
    val t = if (len2 <= 0.0) 0.0 else ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
    val dx = px - bx * t
    val dy = py - by * t
    return sqrt(dx * dx + dy * dy)
}

/** value → share of the list, descending; empty in = empty out. */
internal fun tallyShares(values: List<String>): List<Pair<String, Double>> {
    if (values.isEmpty()) return emptyList()
    return values.groupingBy { it }.eachCount()
        .map { (k, n) -> k to n.toDouble() / values.size }
        .sortedByDescending { it.second }
}

/**
 * Total ascent/descent over an elevation profile, ignoring jitter under
 * [noiseMeters] (open-elevation SRTM data wobbles ±1–2 m between samples).
 */
internal fun elevationGain(els: List<Double>, noiseMeters: Double = 2.0): Pair<Double, Double> {
    var ascent = 0.0
    var descent = 0.0
    var lastAccepted = els.firstOrNull() ?: return 0.0 to 0.0
    for (e in els.drop(1)) {
        val d = e - lastAccepted
        if (abs(d) < noiseMeters) continue
        if (d > 0) ascent += d else descent -= d
        lastAccepted = e
    }
    return ascent to descent
}

internal fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
        kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
    return 2 * r * asin(sqrt(a.coerceIn(0.0, 1.0)))
}
