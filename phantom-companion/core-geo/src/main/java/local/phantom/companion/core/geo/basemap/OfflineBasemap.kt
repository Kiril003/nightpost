package local.phantom.companion.core.geo.basemap

import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * ОФЛАЙН-ОСНОВА — метадані пакета тайлів (`.mbtiles`), спеченого поза
 * пристроєм (`planetiler` на phantom-oracle, див. `styles/OFFLINE.md` у
 * `:feature-stream`). Читаються з таблиці `metadata` самого файла — жодне з
 * цих чисел не вигадане й не підібране, вони кажуть, ЩО саме внесено.
 */
data class OfflineBasemapInfo(
    val path: String,
    val name: String?,
    val minZoom: Int,
    val maxZoom: Int,
    val north: Double,
    val south: Double,
    val east: Double,
    val west: Double,
    val bytes: Long,
)

/** Чи лежить [lat]/[lon] у прямокутнику пакета. За його межами MBTilesFileSource
 *  не має що віддати — мапа має право показати порожнє, але людина мусить
 *  дізнатись чому, а не подумати, що застосунок зламався. */
fun OfflineBasemapInfo.contains(lat: Double, lon: Double): Boolean =
    lat in south..north && lon in west..east

sealed interface OfflineBasemapResult {
    data class Success(val info: OfflineBasemapInfo) : OfflineBasemapResult
    data class Failure(val message: String) : OfflineBasemapResult
}

/**
 * Приймальна сторона файла основи — брат-близнюк
 * `RoadPackImporter` (`:feature-precision`), але з іншою долею байтів: дорожній
 * пакет ВЛИВАЄТЬСЯ у store і сам файл більше не потрібен, а mbtiles має
 * ЛИШИТИСЬ собою назавжди — MapLibre відкриває його напряму під час
 * малювання, тож копія переїжджає у приватний каталог і звідти нікуди не
 * дівається до наступного імпорту.
 */
object OfflineBasemapImporter {

    /** Стабільне імʼя — один слот, як і дорожній пакет: новий файл заміняє
     *  старий, а не накопичується поруч із ним. */
    const val BASEMAP_FILE_NAME = "basemap.mbtiles"

    suspend fun importFrom(context: Context, uri: Uri): OfflineBasemapResult =
        withContext(Dispatchers.IO) {
            val dir = File(context.filesDir, "offline-basemap").apply { mkdirs() }
            val staging = File(dir, "staging-$BASEMAP_FILE_NAME")
            try {
                val copied = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        staging.outputStream().use { input.copyTo(it) }
                    } ?: return@runCatching false
                    true
                }.getOrDefault(false)
                if (!copied || staging.length() == 0L) {
                    return@withContext OfflineBasemapResult.Failure("Не вдалося прочитати файл.")
                }
                val info = readMetadata(staging)
                    ?: return@withContext OfflineBasemapResult.Failure(
                        "Це не пакет тайлів Nightpost: бракує таблиць metadata/tiles, " +
                            "формату pbf або меж покриття.",
                    )
                val dest = File(dir, BASEMAP_FILE_NAME)
                dest.delete()
                if (!staging.renameTo(dest)) {
                    return@withContext OfflineBasemapResult.Failure("Не вдалося зберегти файл.")
                }
                OfflineBasemapResult.Success(info.copy(path = dest.absolutePath, bytes = dest.length()))
            } catch (t: Throwable) {
                OfflineBasemapResult.Failure(t.message ?: "Помилка імпорту.")
            } finally {
                staging.delete()
            }
        }

    /**
     * Та сама приймальна перевірка, що й [importFrom], для файла, який уже
     * лежить локально (завантажений хмарним каталогом і звірений за сумою
     * до виклику цього методу) — без копії через SAF, брат-близнюк
     * `RoadPackImporter.importFromFile` (`:feature-precision`).
     */
    suspend fun importFromFile(context: Context, file: File): OfflineBasemapResult =
        withContext(Dispatchers.IO) {
            if (!file.exists() || file.length() == 0L) {
                return@withContext OfflineBasemapResult.Failure("Файл пакета порожній або відсутній.")
            }
            val info = readMetadata(file)
                ?: return@withContext OfflineBasemapResult.Failure(
                    "Це не пакет тайлів Nightpost: бракує таблиць metadata/tiles, " +
                        "формату pbf або меж покриття.",
                )
            val dir = File(context.filesDir, "offline-basemap").apply { mkdirs() }
            val dest = File(dir, BASEMAP_FILE_NAME)
            dest.delete()
            // rename() can fail across filesystem boundaries (cache vs files
            // partitions differ on some devices) — copy+delete is the fallback.
            val moved = file.renameTo(dest) || runCatching {
                file.copyTo(dest, overwrite = true)
                file.delete()
                true
            }.getOrDefault(false)
            if (!moved) return@withContext OfflineBasemapResult.Failure("Не вдалося зберегти файл.")
            OfflineBasemapResult.Success(info.copy(path = dest.absolutePath, bytes = dest.length()))
        }

    /**
     * Перевірка й розбір `metadata` — чесна відмова, якщо чогось бракує, а не
     * найкращий здогад. `bounds` у форматі mbtiles: "захід,південь,схід,північ".
     */
    private fun readMetadata(file: File): OfflineBasemapInfo? {
        val db = runCatching {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        }.getOrNull() ?: return null
        return try {
            if (!hasTableOrView(db, "metadata") || !hasTableOrView(db, "tiles")) return null
            val meta = HashMap<String, String>()
            db.rawQuery("SELECT name, value FROM metadata", null).use { c ->
                while (c.moveToNext()) meta[c.getString(0)] = c.getString(1) ?: continue
            }
            if (meta["format"] != "pbf") return null
            val bounds = meta["bounds"]?.split(",")?.mapNotNull { it.trim().toDoubleOrNull() }
            if (bounds == null || bounds.size != 4) return null
            val (west, south, east, north) = bounds
            val minZoom = meta["minzoom"]?.toIntOrNull() ?: return null
            val maxZoom = meta["maxzoom"]?.toIntOrNull() ?: return null
            OfflineBasemapInfo(
                path = file.absolutePath,
                name = meta["name"]?.takeIf { it.isNotBlank() },
                minZoom = minZoom,
                maxZoom = maxZoom,
                north = north,
                south = south,
                east = east,
                west = west,
                bytes = file.length(),
            )
        } finally {
            db.close()
        }
    }

    /**
     * `type IN ('table','view')`, а не сама лише таблиця: planetiler у
     * компактному режимі (і tippecanoe так само) кладе `tiles` як VIEW над
     * `tiles_shallow`/`tiles_data`, щоб не тримати однакові тайли двічі. Усі
     * 25 основ із печі саме такі, і кожна відкидалась як «не пакет тайлів» —
     * і з каталогу, і з ручного вибору файла. MapLibre читає їх звичайним
     * `SELECT … FROM tiles`, якому view не заважає.
     */
    private fun hasTableOrView(db: SQLiteDatabase, name: String): Boolean =
        db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type IN ('table','view') AND name=?",
            arrayOf(name),
        ).use { it.moveToFirst() }
}

/**
 * Що з внесеного лишається між запусками — один SharedPreferences-запис, як
 * `MapPreferences` (`:app`), тільки без шифрування: шлях і межі не секрет.
 *
 * Живе в `:core-geo`, бо читають його ДВОЄ, що одне одного не бачать:
 * `:feature-precision` (картка внесення) і `:feature-stream` (полотно мапи).
 * Слухач на самому [prefs] — а не пряме поле — тому що це різні інстанси в
 * різних композиціях: полотно мапи мусить дізнатись про свіжий імпорт, навіть
 * якщо його ніхто не перестворював.
 */
class OfflineBasemapStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _info = MutableStateFlow(load())
    val info: StateFlow<OfflineBasemapInfo?> = _info.asStateFlow()

    private val listener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> _info.value = load() }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun save(info: OfflineBasemapInfo) {
        prefs.edit()
            .putString(KEY_PATH, info.path)
            .putString(KEY_NAME, info.name)
            .putInt(KEY_MINZOOM, info.minZoom)
            .putInt(KEY_MAXZOOM, info.maxZoom)
            .putString(KEY_NORTH, info.north.toString())
            .putString(KEY_SOUTH, info.south.toString())
            .putString(KEY_EAST, info.east.toString())
            .putString(KEY_WEST, info.west.toString())
            .putLong(KEY_BYTES, info.bytes)
            .apply()
        _info.value = info
    }

    private fun load(): OfflineBasemapInfo? {
        val path = prefs.getString(KEY_PATH, null) ?: return null
        if (!File(path).isFile) return null
        val north = prefs.getString(KEY_NORTH, null)?.toDoubleOrNull() ?: return null
        val south = prefs.getString(KEY_SOUTH, null)?.toDoubleOrNull() ?: return null
        val east = prefs.getString(KEY_EAST, null)?.toDoubleOrNull() ?: return null
        val west = prefs.getString(KEY_WEST, null)?.toDoubleOrNull() ?: return null
        return OfflineBasemapInfo(
            path = path,
            name = prefs.getString(KEY_NAME, null),
            minZoom = prefs.getInt(KEY_MINZOOM, 0),
            maxZoom = prefs.getInt(KEY_MAXZOOM, 0),
            north = north,
            south = south,
            east = east,
            west = west,
            bytes = prefs.getLong(KEY_BYTES, 0L),
        )
    }

    companion object {
        private const val PREFS_NAME = "phantom_offline_basemap"
        private const val KEY_PATH = "path"
        private const val KEY_NAME = "name"
        private const val KEY_MINZOOM = "minzoom"
        private const val KEY_MAXZOOM = "maxzoom"
        private const val KEY_NORTH = "north"
        private const val KEY_SOUTH = "south"
        private const val KEY_EAST = "east"
        private const val KEY_WEST = "west"
        private const val KEY_BYTES = "bytes"
    }
}
