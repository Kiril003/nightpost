package local.phantom.companion.core.data.spatial

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * R4.1 — persisted spatial layer. Geometry (markers / lines / regions +
 * colour / glyph) is serialized into [geometryJson] rather than exploded
 * into child tables: a layer is written and read whole, never partially
 * queried by element, so one JSON column keeps the schema flat and the
 * read path a single row fetch. The primitive columns are the ones the UI
 * filters/sorts on (thread, origin, visibility, recency, expiry).
 */
@Entity(
    tableName = "spatial_layers",
    indices = [
        Index(value = ["owner_thread_id"]),
        Index(value = ["created_at"]),
        Index(value = ["expires_at"]),
    ],
)
data class SpatialLayerEntity(
    @PrimaryKey val id: String,
    val title: String,
    /** [LayerOrigin] name. */
    val origin: String,
    val kind: String,
    @androidx.room.ColumnInfo(name = "owner_thread_id") val ownerThreadId: String?,
    @androidx.room.ColumnInfo(name = "geometry_json") val geometryJson: String,
    val color: String?,
    val glyph: String?,
    @androidx.room.ColumnInfo(name = "created_at") val createdAt: Long,
    @androidx.room.ColumnInfo(name = "expires_at") val expiresAt: Long?,
    val visible: Boolean,
)
