package local.phantom.companion.core.geo.layerpack

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * One PC-baked layer extract, kept on the phone.
 *
 * [fetchedAtMs] is this device's own clock at the moment the transfer
 * finished — NOT the PC's bake time. The PC's `/map/hazards/…` response
 * carries no bake timestamp today (the underlying `terrain_hazards_meta` /
 * `power_towers_meta` tables have one, but no route returns it yet), so
 * showing anything labelled "спечено" here would be a claim this phone
 * cannot back up. "Отримано <fetchedAtMs>" is the honest statement: exactly
 * how old the copy on THIS device is, always true regardless of what the PC
 * knows about its own bake.
 */
data class StoredLayerPack(
    val layerId: String,
    val labelUa: String,
    val category: String,
    val northLat: Double,
    val southLat: Double,
    val eastLon: Double,
    val westLon: Double,
    val featureCount: Int,
    val bytes: Long,
    val fetchedAtMs: Long,
    val geoJson: String,
)

/**
 * Map-symbiosis — what the phone holds from the PC's national bakes
 * (cliff/scree, power towers, drains/ditches, culverts, fords, and whatever
 * else lands behind the same shape), one row per layer id.
 *
 * Mirrors `RoadMeshStore`'s idiom (`:core-sensor`): a raw SQLiteOpenHelper,
 * its own file, no Room. The SQLite shell here is deliberately thin and
 * untested directly — `RoadMeshStoreTest` states the house rule ("the
 * SQLite shell... is exercised on-device; it is deliberately too thin to
 * hide logic") and this store follows it: every branch that matters (row
 * merge, age/size formatting, state derivation) lives in pure functions in
 * `:feature-stream`'s `PcLayerPacks`, tested on the JVM.
 *
 * Deliberately NOT keyed by AOI: the PC answers per viewport, and the phone
 * keeps only its most recent pull per layer — one slot per layer id, same
 * model as the road-pack card. A second pull for a different area REPLACES
 * the row rather than growing an unbounded set of overlapping extracts,
 * which is also what keeps "how much room it takes" a single honest number
 * per layer instead of an accumulating pile.
 */
class MapLayerPackStore(
    context: Context,
    databaseName: String = DB_NAME,
) : SQLiteOpenHelper(context.applicationContext, databaseName, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE layer_packs(" +
                "layer_id TEXT PRIMARY KEY, " +
                "label_ua TEXT NOT NULL, " +
                "category TEXT NOT NULL, " +
                "north_lat REAL NOT NULL, " +
                "south_lat REAL NOT NULL, " +
                "east_lon REAL NOT NULL, " +
                "west_lon REAL NOT NULL, " +
                "feature_count INTEGER NOT NULL, " +
                "bytes INTEGER NOT NULL, " +
                "fetched_at INTEGER NOT NULL, " +
                "geojson TEXT NOT NULL)",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS layer_packs")
        onCreate(db)
    }

    /**
     * Replaces any existing row for [pack]'s layer. The caller builds the
     * whole row in memory first (parses the PC's response, counts features,
     * measures bytes) and only then calls this — a parse failure midway
     * through a refresh never touches what was already stored, so a failed
     * refresh cannot make a working layer look gone.
     */
    fun upsert(pack: StoredLayerPack) {
        val values = ContentValues(11).apply {
            put("layer_id", pack.layerId)
            put("label_ua", pack.labelUa)
            put("category", pack.category)
            put("north_lat", pack.northLat)
            put("south_lat", pack.southLat)
            put("east_lon", pack.eastLon)
            put("west_lon", pack.westLon)
            put("feature_count", pack.featureCount)
            put("bytes", pack.bytes)
            put("fetched_at", pack.fetchedAtMs)
            put("geojson", pack.geoJson)
        }
        writableDatabase.insertWithOnConflict(
            "layer_packs", null, values, SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun delete(layerId: String) {
        writableDatabase.delete("layer_packs", "layer_id = ?", arrayOf(layerId))
    }

    fun get(layerId: String): StoredLayerPack? =
        readableDatabase.rawQuery(SELECT_COLUMNS + " WHERE layer_id = ?", arrayOf(layerId))
            .use { c -> if (c.moveToFirst()) c.toStoredLayerPack() else null }

    /** Everything on the phone right now — the "what do I have" list. */
    fun list(): List<StoredLayerPack> =
        readableDatabase.rawQuery("$SELECT_COLUMNS ORDER BY layer_id", null).use { c ->
            val out = ArrayList<StoredLayerPack>(c.count)
            while (c.moveToNext()) out.add(c.toStoredLayerPack())
            out
        }

    /** Total bytes across every stored PC layer — storage is finite, and
     *  this is the number the removal UI has to be honest against. */
    fun totalBytes(): Long =
        readableDatabase.rawQuery("SELECT COALESCE(SUM(bytes), 0) FROM layer_packs", null).use { c ->
            if (c.moveToFirst()) c.getLong(0) else 0L
        }

    companion object {
        const val DB_NAME = "phantom_pc_layer_packs.db"
        const val DB_VERSION = 1

        private const val SELECT_COLUMNS =
            "SELECT layer_id, label_ua, category, north_lat, south_lat, east_lon, west_lon, " +
                "feature_count, bytes, fetched_at, geojson FROM layer_packs"

        private fun Cursor.toStoredLayerPack() = StoredLayerPack(
            layerId = getString(0),
            labelUa = getString(1),
            category = getString(2),
            northLat = getDouble(3),
            southLat = getDouble(4),
            eastLon = getDouble(5),
            westLon = getDouble(6),
            featureCount = getInt(7),
            bytes = getLong(8),
            fetchedAtMs = getLong(9),
            geoJson = getString(10),
        )
    }
}
