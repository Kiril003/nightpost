package local.phantom.companion.core.geo.terrain

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import local.phantom.companion.core.geo.pmtiles.PmTilesConversion
import local.phantom.companion.core.geo.pmtiles.PmTilesMbTiles

/**
 * Рельєф, який лежить у телефоні. `terrarium` тут не випадкове слово: пакет
 * кузні кодує висоту точнісінько тими самими байтами, що й публічні тайли
 * AWS, тож шар малювання лишається той самий — міняється тільки джерело.
 */
data class TerrainPackInfo(
    val path: String,
    val minZoom: Int,
    val maxZoom: Int,
    val tiles: Int,
    val bytes: Long,
    val attribution: String,
)

sealed interface TerrainImport {
    data class Success(val info: TerrainPackInfo) : TerrainImport
    data class Failure(val message: String) : TerrainImport
}

object TerrainPackImporter {

    const val FILE_NAME = "terrain.mbtiles"
    const val ATTRIBUTION = "© ESA / Copernicus DEM GLO-30"

    fun installDir(context: Context): File = File(context.filesDir, "forge-terrain")

    suspend fun importFromFile(context: Context, file: File): TerrainImport {
        val dest = File(installDir(context).apply { mkdirs() }, FILE_NAME)
        val result = runCatching {
            PmTilesMbTiles.convert(file, dest, name = "phantom-terrain", attribution = ATTRIBUTION)
        }.getOrElse { return TerrainImport.Failure("рельєф не перетворився: ${it.message}") }

        return when (result) {
            is PmTilesConversion.Failure -> TerrainImport.Failure(result.message)
            is PmTilesConversion.Success -> TerrainImport.Success(
                TerrainPackInfo(
                    path = dest.absolutePath,
                    minZoom = result.metadata["minzoom"]?.toIntOrNull() ?: 0,
                    maxZoom = result.metadata["maxzoom"]?.toIntOrNull() ?: 0,
                    tiles = result.tiles,
                    bytes = dest.length(),
                    attribution = result.metadata["attribution"] ?: ATTRIBUTION,
                ),
            )
        }
    }
}

class TerrainPackStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _info = MutableStateFlow(load())
    val info: StateFlow<TerrainPackInfo?> = _info.asStateFlow()

    private val listener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> _info.value = load() }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun save(info: TerrainPackInfo) {
        prefs.edit()
            .putString(KEY_PATH, info.path)
            .putInt(KEY_MINZOOM, info.minZoom)
            .putInt(KEY_MAXZOOM, info.maxZoom)
            .putInt(KEY_TILES, info.tiles)
            .putLong(KEY_BYTES, info.bytes)
            .putString(KEY_ATTRIBUTION, info.attribution)
            .apply()
        _info.value = info
    }

    private fun load(): TerrainPackInfo? {
        val path = prefs.getString(KEY_PATH, null) ?: return null
        if (!File(path).isFile) return null
        return TerrainPackInfo(
            path = path,
            minZoom = prefs.getInt(KEY_MINZOOM, 0),
            maxZoom = prefs.getInt(KEY_MAXZOOM, 0),
            tiles = prefs.getInt(KEY_TILES, 0),
            bytes = prefs.getLong(KEY_BYTES, 0L),
            attribution = prefs.getString(KEY_ATTRIBUTION, null) ?: TerrainPackImporter.ATTRIBUTION,
        )
    }

    companion object {
        private const val PREFS_NAME = "phantom_forge_terrain"
        private const val KEY_PATH = "path"
        private const val KEY_MINZOOM = "minzoom"
        private const val KEY_MAXZOOM = "maxzoom"
        private const val KEY_TILES = "tiles"
        private const val KEY_BYTES = "bytes"
        private const val KEY_ATTRIBUTION = "attribution"
    }
}
