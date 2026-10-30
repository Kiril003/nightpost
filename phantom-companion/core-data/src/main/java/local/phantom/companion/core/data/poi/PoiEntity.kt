package local.phantom.companion.core.data.poi

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index

@Entity(
    tableName = "poi_cache",
    indices = [
        Index(value = ["lat", "lon"]),
        Index(value = ["category"])
    ]
)
data class PoiEntity(
    @PrimaryKey val id: Long, // OSM ID
    val lat: Double,
    val lon: Double,
    val name: String,
    val category: String, // e.g. "amenity", "shop", "tourism"
    val subcategory: String?, // e.g. "cafe", "supermarket"
    val tags: Map<String, String>, // Serialized as JSON or separate table? Room needs a converter.
    val timestamp: Long = System.currentTimeMillis()
)
