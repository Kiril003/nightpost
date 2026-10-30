package local.phantom.companion.core.data.calendar

import java.util.UUID
import kotlinx.coroutines.flow.Flow

/** Advanced local event calendar the phantom reads and writes. */
class CalendarStore(private val dao: CalendarEventDao) {

    suspend fun add(
        title: String,
        startMs: Long,
        endMs: Long? = null,
        allDay: Boolean = false,
        category: String = "event",
        location: String? = null,
        lat: Double? = null,
        lon: Double? = null,
        notes: String? = null,
        rrule: String? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): String {
        val clean = title.trim()
        if (clean.isEmpty()) return ""
        val id = UUID.randomUUID().toString()
        dao.insert(
            CalendarEventEntity(
                id = id,
                title = clean,
                notes = notes?.trim()?.takeIf { it.isNotEmpty() },
                startMs = startMs,
                endMs = endMs,
                allDay = allDay,
                location = location?.trim()?.takeIf { it.isNotEmpty() },
                lat = lat,
                lon = lon,
                category = category.ifBlank { "event" },
                rrule = rrule?.trim()?.takeIf { it.isNotEmpty() },
                createdAt = nowMs,
                updatedAt = nowMs,
            ),
        )
        return id
    }

    suspend fun upcoming(fromMs: Long = System.currentTimeMillis(), limit: Int = 20): List<CalendarEventEntity> =
        dao.upcoming(fromMs, limit)

    /** Live upcoming window for the agenda card. */
    fun upcomingFlow(fromMs: Long = System.currentTimeMillis(), limit: Int = 30): Flow<List<CalendarEventEntity>> =
        dao.upcomingFlow(fromMs, limit)

    suspend fun inRange(fromMs: Long, toMs: Long): List<CalendarEventEntity> = dao.inRange(fromMs, toMs)

    suspend fun get(id: String): CalendarEventEntity? = dao.byId(id)

    suspend fun remove(id: String): Boolean {
        val exists = dao.byId(id) != null
        if (exists) dao.deleteById(id)
        return exists
    }

    suspend fun count(): Int = dao.count()
}
