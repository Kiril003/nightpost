package local.phantom.companion.core.data.calendar

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** One operator-authored calendar event. Offline, zero-config; times are epoch ms. */
@Entity(
    tableName = "calendar_events",
    indices = [Index("start_ms"), Index("category")],
)
data class CalendarEventEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "notes") val notes: String?,
    @ColumnInfo(name = "start_ms") val startMs: Long,
    @ColumnInfo(name = "end_ms") val endMs: Long?,
    @ColumnInfo(name = "all_day") val allDay: Boolean,
    @ColumnInfo(name = "location") val location: String?,
    @ColumnInfo(name = "lat") val lat: Double?,
    @ColumnInfo(name = "lon") val lon: Double?,
    /** meeting · birthday · reminder-event · task · trip · … */
    @ColumnInfo(name = "category") val category: String,
    /** iCal-style RRULE for recurring events (null = one-off). */
    @ColumnInfo(name = "rrule") val rrule: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
