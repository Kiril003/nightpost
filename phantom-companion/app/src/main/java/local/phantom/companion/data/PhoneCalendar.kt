package local.phantom.companion.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import local.phantom.companion.self.DayAgenda

class PhoneCalendar(private val context: Context) {
    fun granted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    fun instances(fromMs: Long, toMs: Long): List<DayAgenda.CalendarEvent> {
        if (!granted()) return emptyList()
        return runCatching {
            val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(builder, fromMs)
            ContentUris.appendId(builder, toMs)
            context.contentResolver.query(
                builder.build(),
                PROJECTION,
                "${CalendarContract.Instances.VISIBLE} = 1",
                null,
                "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { rows(it) } ?: emptyList()
        }.getOrElse { emptyList() }
    }

    companion object {
        private const val MAX_ROWS = 200

        private val PROJECTION = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
        )

        internal fun rows(c: Cursor): List<DayAgenda.CalendarEvent> {
            val idCol = c.getColumnIndex(CalendarContract.Instances.EVENT_ID)
            val titleCol = c.getColumnIndex(CalendarContract.Instances.TITLE)
            val beginCol = c.getColumnIndex(CalendarContract.Instances.BEGIN)
            val endCol = c.getColumnIndex(CalendarContract.Instances.END)
            val allDayCol = c.getColumnIndex(CalendarContract.Instances.ALL_DAY)
            if (idCol < 0 || beginCol < 0 || endCol < 0) return emptyList()
            val out = ArrayList<DayAgenda.CalendarEvent>()
            while (c.moveToNext() && out.size < MAX_ROWS) {
                val eventId = c.getLong(idCol)
                val begin = c.getLong(beginCol)
                val end = c.getLong(endCol)
                val title = if (titleCol >= 0 && !c.isNull(titleCol)) c.getString(titleCol).orEmpty() else ""
                val allDay = allDayCol >= 0 && !c.isNull(allDayCol) && c.getInt(allDayCol) == 1
                out += DayAgenda.CalendarEvent(
                    id = "$eventId:$begin",
                    title = title,
                    beginMs = begin,
                    endMs = end,
                    allDay = allDay,
                )
            }
            return out
        }
    }
}

object CalendarPermit {
    private val asks = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val requests: SharedFlow<Unit> = asks.asSharedFlow()

    fun ask(): Boolean = asks.tryEmit(Unit)

    const val PERMISSION = "android.permission.READ_CALENDAR"
}
