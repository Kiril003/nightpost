package local.phantom.companion.feature.stream.ui.map.nav

import kotlin.math.floor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import local.phantom.companion.core.ai.context.LocationFix
import local.phantom.companion.core.sensor.nav.RoadGraph
import local.phantom.companion.core.sensor.nav.RoadMatcher
import local.phantom.companion.core.sensor.nav.RoadWay

/**
 * ATLAS-2 — the live half of the map-matcher: keeps a small LRU of road
 * tiles (Overpass-fetched, TTL'd) around the operator, feeds each pumped fix
 * through the pure [RoadMatcher], and publishes an ADVISORY snapped position
 * for the displayed dot.
 *
 * The policy for when a snap may be shown, in one place, in order:
 *
 *  1. Never a fix that is not navigation-grade — a coarse fix has no lane.
 *  2. Never a dead-reckoned fix — inertia has no business claiming asphalt.
 *  3. Only above [MIN_CONFIDENCE] — «на дорозі, мабуть» is not a thing the
 *     dot is allowed to whisper; either the network claims him or it stays
 *     out of the way.
 *
 * When any rule fails, [snapped] is null and the dot falls back to the fused
 * fix — the map NEVER goes blank because the matcher went quiet.
 *
 * Battery discipline: the whole apparatus is [setActive]-gated by world
 * visibility (same doctrine as MapLiveSession's pump), and a tile refetch
 * fires only when the operator enters a tile the cache does not hold.
 */
internal class RoadTruth(
    private val scope: CoroutineScope,
    private val operatorFix: StateFlow<LocationFix?>,
    /** (minLat, minLon, maxLat, maxLon) → ways, or null on failure. Null
     *  fetcher (tests/previews/no container) disables tile fetching; the OSRM
     *  primary can still snap without any Overpass tiles at all. */
    private val fetchRoads: (suspend (Double, Double, Double, Double) -> List<RoadWay>?)?,
    /** ATLAS-2b — the PRIMARY road-snap: a recent fix trace → OSRM `/match`
     *  snap on the local geo stack, or null on failure. Null (tests/previews/no
     *  container) leaves only the local tile-matcher fallback below. */
    private val matchTrace: (suspend (List<LocationFix>) -> RoadSnapFix?)? = null,
    /** Дороги з уже намальованих векторних тайлів — див. [RenderedRoads].
     *  Читається на головному потоці власником мапи; null у тестах і превʼю. */
    private val renderedWays: (() -> List<RoadWay>)? = null,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val matcher = RoadMatcher()

    /** Sliding trace for OSRM `/match`; a lone point cannot be map-matched. */
    private val recentFixes = ArrayDeque<LocationFix>(MATCH_WINDOW)

    private val _snapped = MutableStateFlow<LocationFix?>(null)
    /** The road's version of the operator dot, or null when off/uncertain. */
    val snapped: StateFlow<LocationFix?> = _snapped.asStateFlow()

    private val _wayName = MutableStateFlow<String?>(null)
    /** The matched street's name, for chrome that wants to say it. */
    val wayName: StateFlow<String?> = _wayName.asStateFlow()

    private class Tile(val ways: List<RoadWay>, val fetchedAtMs: Long)

    private val tiles = LinkedHashMap<Long, Tile>()
    private val inFlight = HashSet<Long>()
    private var graph: RoadGraph? = null

    private var job: Job? = null

    /**
     * LEVIATHAN-1 — the loaded road world, for the offline router. Whatever
     * tiles the matcher has already earned double as the evacuation
     * fallback's routing graph; null before the first tile lands.
     */
    fun graphSnapshot(): RoadGraph? = graph

    fun setActive(active: Boolean) {
        if (active) start() else stop()
    }

    private fun start() {
        // Alive if EITHER snapper exists: OSRM `/match` (the primary) needs no
        // Overpass tiles at all, so a null fetcher no longer disables us — only
        // the total absence of both does.
        if (job != null || (fetchRoads == null && matchTrace == null)) return
        job = scope.launch {
            operatorFix.filterNotNull().collect { fix -> onFix(fix) }
        }
    }

    private fun stop() {
        job?.cancel()
        job = null
        matcher.reset()
        recentFixes.clear()
        clearSnap()
    }

    private suspend fun onFix(fix: LocationFix) {
        if (!fix.isNavigationGrade || fix.dead) {
            recentFixes.clear()
            clearSnap()
            return
        }
        recentFixes.addLast(fix)
        while (recentFixes.size > MATCH_WINDOW) recentFixes.removeFirst()

        // Tiles keep loading regardless of which snapper wins — they double as
        // the offline evac router's graph (LEVIATHAN-1) and back the fallback.
        ensureTiles(fix.lat, fix.lon)

        // PRIMARY — OSRM `/match` on the local geo stack. Overpass is
        // unreachable exactly while driving (the owner's field report), so the
        // network road-snap leads; the tile matcher is only the fallback.
        val snapFn = matchTrace
        if (snapFn != null && recentFixes.size >= MATCH_MIN_POINTS) {
            val snap = snapFn(recentFixes.toList())
            if (snap != null && snap.confidence >= MIN_CONFIDENCE) {
                _snapped.value = fix.copy(
                    lat = snap.lat,
                    lon = snap.lon,
                    // OSRM `/match` carries no per-point heading here; keep the
                    // fused fix's own bearing, which the BearingFuser already
                    // steadies.
                    bearing = snap.bearingDeg ?: fix.bearing,
                )
                _wayName.value = snap.wayName
                return
            }
        }

        // FALLBACK — the local tile matcher, when OSRM is silent, unsure, or
        // absent (null fetcher). Needs ground under the fix.
        //
        // Ґрунт беремо спершу з екрана: намальовані тайли є завжди, коли є
        // мапа, і не залежать від мережі — на відміну від Overpass, який у
        // дорозі мовчить рівно тоді, коли снап потрібен.
        val g = renderedGraphFor(fix.lat, fix.lon) ?: graph
        if (g == null || g.wayCount == 0) {
            clearSnap()
            return
        }
        val match = matcher.onFix(
            graph = g,
            lat = fix.lat,
            lon = fix.lon,
            accuracyM = fix.accuracyM ?: DEFAULT_ACCURACY_M,
            bearingDeg = fix.bearing,
            nowMs = nowMs(),
        )
        if (match == null || match.confidence < MIN_CONFIDENCE) {
            clearSnap()
            return
        }
        val moving = (fix.speedMps ?: 0f) > MOVING_SPEED_MPS
        _snapped.value = fix.copy(
            lat = match.lat,
            lon = match.lon,
            // The way's direction is steadier than the chip's bearing — but
            // only motion earns it; a standing operator keeps his own compass.
            bearing = if (moving) match.headingDeg else fix.bearing,
        )
        _wayName.value = match.wayName
    }

    private fun clearSnap() {
        _snapped.value = null
        _wayName.value = null
    }

    // ── Tile plumbing ─────────────────────────────────────────────────────

    private suspend fun ensureTiles(lat: Double, lon: Double) {
        val fetch = fetchRoads ?: return
        val now = nowMs()
        // Evict stale + overflow (oldest-first: LinkedHashMap keeps order).
        tiles.entries.removeAll { now - it.value.fetchedAtMs > TILE_TTL_MS }
        while (tiles.size > MAX_TILES) {
            tiles.remove(tiles.keys.first())
        }

        val wanted = wantedTiles(lat, lon)
        var changed = false
        for (key in wanted) {
            if (tiles.containsKey(key) || !inFlight.add(key)) continue
            val (s, w, n, e) = tileBounds(key)
            // Fetched inline for the operator's own tile (the matcher cannot
            // run without ground under the fix); neighbours ride async.
            if (key == tileKey(lat, lon)) {
                val ways = runCatching { fetch(s, w, n, e) }.getOrNull()
                inFlight.remove(key)
                if (ways != null) {
                    tiles[key] = Tile(ways, now)
                    changed = true
                }
            } else {
                scope.launch {
                    val ways = runCatching { fetch(s, w, n, e) }.getOrNull()
                    inFlight.remove(key)
                    if (ways != null) {
                        tiles[key] = Tile(ways, nowMs())
                        rebuildGraph()
                    }
                }
            }
        }
        if (changed) rebuildGraph()
    }

    private var renderedGraph: RoadGraph? = null
    private var renderedAtLat = 0.0
    private var renderedAtLon = 0.0

    /**
     * Граф із намальованих доріг, перезнятий, коли оператор відʼїхав далі
     * [RENDERED_REFRESH_M] від місця останнього знімка. Знімати щофікса
     * дорого — `queryRenderedFeatures` йде через рендерер на головному потоці.
     */
    private fun renderedGraphFor(lat: Double, lon: Double): RoadGraph? {
        val provider = renderedWays ?: return null
        val cached = renderedGraph
        if (cached != null) {
            val moved = local.phantom.companion.core.nav.haversineM(
                renderedAtLat to renderedAtLon,
                lat to lon,
            )
            if (moved < RENDERED_REFRESH_M) return cached
        }
        val ways = runCatching { provider() }.getOrNull().orEmpty()
        if (ways.isEmpty()) return cached
        renderedAtLat = lat
        renderedAtLon = lon
        return RoadGraph(ways).also { renderedGraph = it }
    }

    private fun rebuildGraph() {
        val byId = LinkedHashMap<Long, RoadWay>()
        for (tile in tiles.values) for (w in tile.ways) byId[w.id] = w
        graph = RoadGraph(byId.values.toList())
    }

    /** The operator's tile, plus the neighbour ahead when near an edge. */
    private fun wantedTiles(lat: Double, lon: Double): List<Long> {
        val out = ArrayList<Long>(3)
        out.add(tileKey(lat, lon))
        val latCell = floor(lat / TILE_DEG)
        val lonCell = floor(lon / TILE_DEG)
        val latFrac = lat / TILE_DEG - latCell
        val lonFrac = lon / TILE_DEG - lonCell
        if (latFrac > 1 - EDGE_FRAC) out.add(key(latCell + 1, lonCell))
        if (latFrac < EDGE_FRAC) out.add(key(latCell - 1, lonCell))
        if (lonFrac > 1 - EDGE_FRAC) out.add(key(latCell, lonCell + 1))
        if (lonFrac < EDGE_FRAC) out.add(key(latCell, lonCell - 1))
        return out
    }

    private fun tileKey(lat: Double, lon: Double): Long =
        key(floor(lat / TILE_DEG), floor(lon / TILE_DEG))

    private fun key(latCell: Double, lonCell: Double): Long =
        (latCell.toLong() shl 32) xor (lonCell.toLong() and 0xFFFFFFFFL)

    /** minLat, minLon, maxLat, maxLon of one tile, with a small skirt so a
     *  way clipped at the border still projects cleanly. */
    private fun tileBounds(key: Long): Array<Double> {
        val latCell = (key shr 32).toDouble()
        val lonCell = (key.toInt()).toDouble()
        return arrayOf(
            latCell * TILE_DEG - SKIRT_DEG,
            lonCell * TILE_DEG - SKIRT_DEG,
            (latCell + 1) * TILE_DEG + SKIRT_DEG,
            (lonCell + 1) * TILE_DEG + SKIRT_DEG,
        )
    }

    private companion object {
        /** ~2.2 km of latitude per tile — a city district per fetch. */
        const val TILE_DEG = 0.02

        /** Way-clipping skirt: ~110 m. */
        const val SKIRT_DEG = 0.001

        /** Within this fraction of a tile edge, prefetch the neighbour. */
        const val EDGE_FRAC = 0.15

        const val MAX_TILES = 6
        const val TILE_TTL_MS = 30 * 60_000L

        const val MIN_CONFIDENCE = 0.65f
        const val MOVING_SPEED_MPS = 1.5f
        const val DEFAULT_ACCURACY_M = 25f

        /** Recent fixes fed to OSRM `/match`; ~6 s of trace at 1 Hz gives the
         *  matcher enough shape to tell parallel roads apart. */
        const val MATCH_WINDOW = 6

        /** OSRM cannot map-match a single point. */
        const val MATCH_MIN_POINTS = 2

        /** Наскільки треба відʼїхати, щоб перезняти дороги з екрана. На 90
         *  км/год це раз на ~10 с — рендерер за цей час устигає підтягти
         *  наступні тайли, а головний потік не смикаємо щосекунди. */
        const val RENDERED_REFRESH_M = 250.0
    }
}

/**
 * ATLAS-2b — one road-snap from the OSRM `/match` primary path. Kept in
 * feature-stream (not core-net) so [RoadTruth] never depends on ktor: the
 * container maps its [RoadMatchResult] into this on the way in.
 */
data class RoadSnapFix(
    val lat: Double, // snapped position of the newest trace point
    val lon: Double,
    val wayName: String?, // matched street name, when OSRM names the way
    val confidence: Float, // the matching's confidence, [0,1]
    val bearingDeg: Float? = null, // usually null — `/match` carries no heading here
)
