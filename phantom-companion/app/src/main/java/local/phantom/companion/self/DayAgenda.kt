package local.phantom.companion.self

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

object DayAgenda {
    enum class Kind(val wire: String) { PROMISE("promise"), REMINDER("reminder"), PLAN("plan"), EVENT("event") }

    data class ReminderLine(val id: String, val text: String, val atMs: Long)

    data class PlanLine(val id: String, val title: String, val atMs: Long)

    data class CalendarEvent(val id: String, val title: String, val beginMs: Long, val endMs: Long, val allDay: Boolean)

    data class DayItem(
        val kind: Kind,
        val id: String,
        val title: String,
        val atMs: Long,
        val endMs: Long? = null,
        val allDay: Boolean = false,
        val peerId: String? = null,
        val overdue: Boolean = false,
    )

    fun today(
        nowMs: Long,
        zone: ZoneId,
        reminders: List<ReminderLine>,
        promises: List<Commitment>,
        events: List<CalendarEvent>?,
        plans: List<PlanLine> = emptyList(),
    ): List<DayItem> {
        val localToday = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val dayStart = localToday.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = localToday.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val out = ArrayList<DayItem>()

        for (r in reminders) {
            if (r.atMs < dayEnd) {
                out += DayItem(
                    kind = Kind.REMINDER,
                    id = r.id,
                    title = r.text,
                    atMs = r.atMs,
                    overdue = r.atMs < nowMs,
                )
            }
        }

        for (p in promises) {
            val due = p.dueMs ?: continue
            if (p.state == CommitmentState.OPEN && due < dayEnd) {
                val title = if (p.what.isBlank()) p.personName else "${p.personName}: ${p.what}"
                out += DayItem(
                    kind = Kind.PROMISE,
                    id = p.id,
                    title = title,
                    atMs = due,
                    peerId = p.peerId,
                    overdue = due < nowMs,
                )
            }
        }

        for (pl in plans) {
            if (pl.atMs in dayStart until dayEnd) {
                out += DayItem(
                    kind = Kind.PLAN,
                    id = pl.id,
                    title = pl.title,
                    atMs = pl.atMs,
                )
            }
        }

        if (events != null) {
            for (e in events) {
                if (e.allDay) {
                    val beginDate = Instant.ofEpochMilli(e.beginMs).atZone(ZoneOffset.UTC).toLocalDate()
                    val endDate = Instant.ofEpochMilli(e.endMs).atZone(ZoneOffset.UTC).toLocalDate()
                    if (!localToday.isBefore(beginDate) && localToday.isBefore(endDate)) {
                        out += DayItem(
                            kind = Kind.EVENT,
                            id = e.id,
                            title = e.title,
                            atMs = dayStart,
                            allDay = true,
                        )
                    }
                } else if (e.beginMs < dayEnd && e.endMs > dayStart) {
                    out += DayItem(
                        kind = Kind.EVENT,
                        id = e.id,
                        title = e.title,
                        atMs = maxOf(e.beginMs, dayStart),
                        endMs = e.endMs,
                    )
                }
            }
        }

        return out.sortedWith(compareBy<DayItem>({ it.atMs }, { it.kind.ordinal }, { it.id }))
    }
}
