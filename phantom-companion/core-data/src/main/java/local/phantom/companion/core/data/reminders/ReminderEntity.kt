package local.phantom.companion.core.data.reminders

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** One time-based reminder. Fires at [triggerAtMs]; `fired` flips once surfaced. */
@Entity(
    tableName = "reminders",
    indices = [Index("trigger_at_ms"), Index("fired")],
)
data class ReminderEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "text") val text: String,
    @ColumnInfo(name = "trigger_at_ms") val triggerAtMs: Long,
    @ColumnInfo(name = "lead_minutes") val leadMinutes: Int,
    /** iCal-style RRULE for repeating reminders (null = one-shot). */
    @ColumnInfo(name = "recurrence") val recurrence: String?,
    @ColumnInfo(name = "fired") val fired: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
