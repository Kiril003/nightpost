package local.phantom.companion.core.data.places

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One operator-saved place — a personal gazetteer entry (home/work/favourite/…).
 * Distinct from `poi_cache` (the OSM world cache keyed by OSM id): this is the
 * operator's own named locations, authored by hand or by the phantom on request.
 */
@Entity(
    tableName = "saved_places",
    indices = [Index("category"), Index(value = ["lat", "lon"])],
)
data class SavedPlaceEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "label") val label: String,
    /** home · work · favourite · shelter · custom · … */
    @ColumnInfo(name = "category") val category: String,
    @ColumnInfo(name = "lat") val lat: Double,
    @ColumnInfo(name = "lon") val lon: Double,
    @ColumnInfo(name = "address") val address: String?,
    @ColumnInfo(name = "note") val note: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "last_used_ms") val lastUsedMs: Long,
)
