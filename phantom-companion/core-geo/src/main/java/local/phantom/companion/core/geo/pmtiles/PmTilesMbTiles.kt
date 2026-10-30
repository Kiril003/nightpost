package local.phantom.companion.core.geo.pmtiles

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

sealed interface PmTilesConversion {
    data class Success(val tiles: Int, val metadata: Map<String, String>) : PmTilesConversion
    data class Failure(val message: String) : PmTilesConversion
}

/**
 * PMTiles кузні → MBTiles, який уміє малювати цей MapLibre.
 *
 * Рушій 11.5.1 не має жодної згадки `pmtiles` (перевірено в `styles/OFFLINE.md`),
 * зате має `MBTilesFileSource`. Тому телефон не будує другої дороги поруч із
 * основою: він приводить пакет до того самого `mbtiles://`, яким уже малюється
 * офлайн-основа, а читає архів уже написаним [PmTilesReader].
 */
object PmTilesMbTiles {

    private const val BATCH = 256

    suspend fun convert(
        source: File,
        dest: File,
        name: String,
        attribution: String? = null,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): PmTilesConversion {
        if (!source.isFile) return PmTilesConversion.Failure("файла пакета немає")
        val reader = runCatching { PmTilesReader.open(FileRangeSource(source)) }
            .getOrElse { return PmTilesConversion.Failure("архів не читається: ${it.message}") }

        if (dest.exists() && !dest.delete()) {
            reader.close()
            return PmTilesConversion.Failure("не зміг звільнити місце під ${dest.name}")
        }
        dest.parentFile?.mkdirs()

        val header = reader.header
        val metadata = PmTilesPyramid.mbTilesMetadata(header, name, attribution)
        val rects = (header.minZoom..header.maxZoom).map { PmTilesPyramid.tileRect(header, it) }
        val total = rects.sumOf { it.count }

        val db = runCatching { SQLiteDatabase.openOrCreateDatabase(dest, null) }
            .getOrElse {
                reader.close()
                return PmTilesConversion.Failure("не зміг створити ${dest.name}: ${it.message}")
            }
        var written = 0
        try {
            db.execSQL("CREATE TABLE metadata (name text, value text)")
            db.execSQL("CREATE TABLE tiles (zoom_level integer, tile_column integer, tile_row integer, tile_data blob)")
            db.execSQL("CREATE UNIQUE INDEX tile_index on tiles (zoom_level, tile_column, tile_row)")
            db.beginTransaction()
            for ((key, value) in metadata) {
                db.insert("metadata", null, ContentValues().apply { put("name", key); put("value", value) })
            }
            db.setTransactionSuccessful()
            db.endTransaction()

            var seen = 0
            db.beginTransaction()
            for (rect in rects) {
                for (x in rect.x0..rect.x1) {
                    for (y in rect.y0..rect.y1) {
                        currentCoroutineContext().ensureActive()
                        seen++
                        val bytes = reader.tile(rect.z, x, y)
                        if (bytes != null && bytes.isNotEmpty()) {
                            db.insert(
                                "tiles", null,
                                ContentValues().apply {
                                    put("zoom_level", rect.z)
                                    put("tile_column", x)
                                    put("tile_row", PmTilesPyramid.tmsRow(rect.z, y))
                                    put("tile_data", bytes)
                                },
                            )
                            written++
                        }
                        if (seen % BATCH == 0) {
                            db.setTransactionSuccessful()
                            db.endTransaction()
                            onProgress(seen, total)
                            db.beginTransaction()
                        }
                    }
                }
            }
            db.setTransactionSuccessful()
            db.endTransaction()
            onProgress(total, total)
        } catch (t: Throwable) {
            runCatching { if (db.inTransaction()) db.endTransaction() }
            db.close()
            reader.close()
            dest.delete()
            throw t
        }
        db.close()
        reader.close()
        if (written == 0) {
            dest.delete()
            return PmTilesConversion.Failure("в архіві не знайшлося жодного тайла")
        }
        return PmTilesConversion.Success(written, metadata)
    }
}
