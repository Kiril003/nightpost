package local.phantom.companion.feature.stream.ui.map.crystal

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Crystal City C1 — viewport-driven building prefetcher.
 *
 * MapScreen calls [onViewport] from its camera-idle listener; the feed
 * fetches the covering tiles (cache-first, centre-out, bounded per idle
 * so a fast pan can't queue dozens of Overpass requests) and publishes
 * loaded tiles on [buildings]. C2/C3 subscribe to that flow to mesh and
 * render; until then the logcat line per tile is the C1 acceptance
 * signal (`adb logcat -s CrystalCity`).
 */
class CrystalCityFeed(
    private val store: BuildingTileStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = mutableSetOf<CrystalTileKey>()
    private val failedAtMs = mutableMapOf<CrystalTileKey, Long>()

    private val _tiles = MutableStateFlow<Map<CrystalTileKey, CityTileData>>(emptyMap())
    val tiles: StateFlow<Map<CrystalTileKey, CityTileData>> = _tiles

    private val _visibleTiles = MutableStateFlow<List<CrystalTileKey>>(emptyList())

    /**
     * The tiles the settled viewport covers, centre-out — empty below
     * [MIN_ZOOM]. [buildings] only ever grows (it is the loaded-tile cache);
     * this is what C3's renderer keeps on the GPU, and emptying it as the
     * operator zooms out is what retires the 3D city.
     */
    val visibleTiles: StateFlow<List<CrystalTileKey>> = _visibleTiles

    /**
     * Called from the map's camera-idle listener — i.e. on the MAIN THREAD.
     * Only the pure geometry runs here; the planning pass below stats the tile
     * cache (two syscalls per tile, up to [MAX_TILES_PER_VIEWPORT] of them) and
     * takes a lock the IO workers also hold, so it is dispatched off. A camera
     * idle must never wait on the filesystem: that lands as a stalled frame the
     * moment the operator starts panning again.
     */
    fun onViewport(north: Double, east: Double, south: Double, west: Double, zoom: Double) {
        if (zoom < MIN_ZOOM) {
            _visibleTiles.value = emptyList()
            return
        }
        val wanted = CrystalGeo.tilesCovering(north, east, south, west).take(MAX_TILES_PER_VIEWPORT)
        _visibleTiles.value = wanted
        scope.launch { plan(wanted) }
    }

    private fun plan(wanted: List<CrystalTileKey>) {
        var budget = MAX_NEW_FETCHES_PER_IDLE
        synchronized(inFlight) {
            for (tile in wanted) {
                if (_tiles.value.containsKey(tile) || tile in inFlight) continue
                val lastFail = failedAtMs[tile]
                if (lastFail != null && System.currentTimeMillis() - lastFail < FAIL_RETRY_MS) continue
                val cached = store.isCached(tile)
                if (!cached) {
                    if (budget == 0) continue
                    budget--
                }
                inFlight += tile
                load(tile)
            }
        }
    }

    private fun load(tile: CrystalTileKey) {
        scope.launch {
            val result = store.tile(tile)
            synchronized(inFlight) { inFlight -= tile }
            if (result == null) {
                synchronized(inFlight) { failedAtMs[tile] = System.currentTimeMillis() }
                Log.w(TAG, "tile ${tile.fileStem}: fetch failed (retry in ${FAIL_RETRY_MS / 1000}s)")
                return@launch
            }
            _tiles.update { it + (tile to result) }
            Log.i(
                TAG,
                "tile ${tile.fileStem}: ${result.buildings.size} buildings, " +
                    "${result.greenery.areas.size} green areas, ${result.greenery.trees.size} trees",
            )
        }
    }

    fun destroy() = scope.cancel()

    companion object {
        private const val TAG = "CrystalCity"
        /** Matches TACTICAL_PITCH_ZOOM_THRESHOLD — buildings load exactly when the 3D view engages. */
        private const val MIN_ZOOM = 15.5
        /** 3×3 full-detail core + a far ring rendered at LOD 1 (C4). */
        private const val MAX_TILES_PER_VIEWPORT = 15
        private const val MAX_NEW_FETCHES_PER_IDLE = 3
        private const val FAIL_RETRY_MS = 60_000L
    }
}
