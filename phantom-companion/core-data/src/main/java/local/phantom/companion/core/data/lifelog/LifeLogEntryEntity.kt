package local.phantom.companion.core.data.lifelog

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity table representing an embedded episode of the operator's life log.
 */
@Entity(tableName = "life_log_entries")
data class LifeLogEntryEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "timestamp_ms") val timestampMs: Long,
    @ColumnInfo(name = "event_type") val eventType: String,
    @ColumnInfo(name = "event_data") val eventData: String,
    @ColumnInfo(name = "embedding_blob") val embeddingBlob: FloatArray,
    @ColumnInfo(name = "tags") val tags: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as LifeLogEntryEntity
        if (id != other.id) return false
        if (timestampMs != other.timestampMs) return false
        if (eventType != other.eventType) return false
        if (eventData != other.eventData) return false
        if (!embeddingBlob.contentEquals(other.embeddingBlob)) return false
        if (tags != other.tags) return false
        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + timestampMs.hashCode()
        result = 31 * result + eventType.hashCode()
        result = 31 * result + eventData.hashCode()
        result = 31 * result + embeddingBlob.contentHashCode()
        result = 31 * result + (tags?.hashCode() ?: 0)
        return result
    }
}
