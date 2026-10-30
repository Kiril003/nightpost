package local.phantom.companion.feature.stream.ui.map.crystal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Crystal City C1 — disk-cached access to per-tile building lists.
 *
 * Stores the raw Overpass JSON (not the parsed model) so parser
 * improvements in later stages re-apply to already-cached tiles for
 * free. Cache lives under the app cacheDir, so the OS may reclaim it —
 * a reclaimed tile is just a re-fetch, never an error.
 */
data class CityTileData(
    val buildings: List<CityBuilding>,
    val greenery: CityGreenery,
)

class BuildingTileStore(
    cacheRoot: File,
    private val client: OverpassBuildingsClient = OverpassBuildingsClient(),
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val dir = File(cacheRoot, "crystal_tiles")

    fun isCached(tile: CrystalTileKey): Boolean = fileOf(tile).let { it.isFile && isFresh(it) }

    /**
     * The tile's content — from disk when fresh, otherwise fetched and
     * persisted. Returns null only when the tile is uncached AND the
     * network fetch failed (caller retries on a later viewport idle).
     */
    suspend fun tile(tile: CrystalTileKey): CityTileData? = withContext(Dispatchers.IO) {
        val file = fileOf(tile)
        val cached = if (file.isFile && isFresh(file)) runCatching { file.readText() }.getOrNull() else null
        val body = cached ?: client.fetchTile(tile)?.also { fetched ->
            runCatching {
                dir.mkdirs()
                file.writeText(fetched)
                trim()
            }
        }
        body?.let { CityTileData(CityBuildingParser.parse(it), CityGreenParser.parse(it)) }
    }

    private fun fileOf(tile: CrystalTileKey): File = File(dir, "${tile.fileStem}.json")

    private fun isFresh(file: File): Boolean = nowMs() - file.lastModified() < TTL_MS

    /** Oldest-first eviction so a cross-country pan doesn't grow the cache unbounded. */
    private fun trim() {
        val files = dir.listFiles { f -> f.extension == "json" } ?: return
        if (files.size <= MAX_TILES) return
        files.sortedBy { it.lastModified() }
            .take(files.size - MAX_TILES)
            .forEach { it.delete() }
    }

    companion object {
        private const val TTL_MS = 7L * 24 * 60 * 60 * 1_000
        private const val MAX_TILES = 256
    }
}
