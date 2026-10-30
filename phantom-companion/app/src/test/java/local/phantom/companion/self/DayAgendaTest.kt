package local.phantom.companion.self

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import local.phantom.companion.self.DayAgenda.CalendarEvent
import local.phantom.companion.self.DayAgenda.Kind
import local.phantom.companion.self.DayAgenda.PlanLine
import local.phantom.companion.self.DayAgenda.ReminderLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** K3: one list for the day. Clock: Thu 2026-10-15 10:00 Europe/Kyiv. */
class DayAgendaTest {

    private val kyiv: ZoneId = ZoneId.of("Europe/Kyiv")
    private val newYork: ZoneId = ZoneId.of("America/New_York")

    private fun at(zone: ZoneId, y: Int, m: Int, d: Int, h: Int, min: Int): Long =
        LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()

    private fun d(m: Int, day: Int, h: Int, min: Int): Long = at(kyiv, 2026, m, day, h, min)

    private fun utc(s: String): Long = Instant.parse(s).toEpochMilli()

    private val now = d(10, 15, 10, 0)
    private val dayStart = d(10, 15, 0, 0)

    private fun reminder(id: String, atMs: Long) = ReminderLine(id, "text $id", atMs)

    private fun plan(id: String, atMs: Long) = PlanLine(id, "plan $id", atMs)

    private fun promise(
        id: String,
        due: Long?,
        person: String = "Оля",
        what: String = "фото",
        state: CommitmentState = CommitmentState.OPEN,
    ) = Commitment(id, "$person: $what", person, "peer_$id", what, due, now - 3_600_000, "", state)

    private fun event(id: String, begin: Long, end: Long, allDay: Boolean = false, title: String = "event $id") =
        CalendarEvent(id, title, begin, end, allDay)

    private fun today(
        reminders: List<ReminderLine> = emptyList(),
        promises: List<Commitment> = emptyList(),
        events: List<CalendarEvent>? = emptyList(),
        plans: List<PlanLine> = emptyList(),
        nowMs: Long = now,
        zone: ZoneId = kyiv,
    ) = DayAgenda.today(nowMs, zone, reminders, promises, events, plans)

    private fun ids(items: List<DayAgenda.DayItem>) = items.map { it.id }

    @Test
    fun `items come in time order across all five sources`() {
        val items = today(
            reminders = listOf(reminder("r", d(10, 15, 18, 30))),
            promises = listOf(promise("p", d(10, 15, 14, 0))),
            events = listOf(
                event("timed", d(10, 15, 9, 30), d(10, 15, 10, 0)),
                event("allday", utc("2026-10-15T00:00:00Z"), utc("2026-10-16T00:00:00Z"), allDay = true),
            ),
            plans = listOf(plan("g", d(10, 15, 16, 0))),
        )
        assertEquals(listOf("allday", "timed", "p", "g", "r"), ids(items))
        assertEquals(
            listOf(Kind.EVENT, Kind.EVENT, Kind.PROMISE, Kind.PLAN, Kind.REMINDER),
            items.map { it.kind },
        )
        assertEquals(dayStart, items.first().atMs)
        assertTrue(items.first().allDay)
    }

    @Test
    fun `the day ends at local midnight, tomorrow and undated items stay out`() {
        val items = today(
            reminders = listOf(
                reminder("last-minute", d(10, 15, 23, 59)),
                reminder("tomorrow", d(10, 16, 0, 0)),
            ),
            promises = listOf(
                promise("undated", null),
                promise("next-day", d(10, 16, 9, 0)),
                promise("done", d(10, 15, 12, 0), state = CommitmentState.DONE),
                promise("dropped", d(10, 15, 13, 0), state = CommitmentState.DROPPED),
            ),
            plans = listOf(plan("g-today", d(10, 15, 19, 0)), plan("g-tomorrow", d(10, 16, 19, 0))),
        )
        assertEquals(listOf("g-today", "last-minute"), ids(items))
    }

    @Test
    fun `reminders and promises whose time has passed are flagged overdue and come first`() {
        val items = today(
            reminders = listOf(
                reminder("r-late", d(10, 14, 9, 0)),
                reminder("r-earlier", d(10, 15, 9, 55)),
                reminder("r-now", d(10, 15, 11, 0)),
            ),
            promises = listOf(promise("p-late", d(10, 14, 18, 0))),
            plans = listOf(plan("g-yesterday", d(10, 14, 12, 0))),
        )
        assertEquals(listOf("r-late", "p-late", "r-earlier", "r-now"), ids(items))
        assertEquals(listOf(true, true, true, false), items.map { it.overdue })
    }

    @Test
    fun `no calendar access means no event items, and an empty calendar looks the same`() {
        val reminders = listOf(reminder("r", d(10, 15, 18, 30)))
        val promises = listOf(promise("p", d(10, 15, 14, 0)))
        val denied = today(reminders, promises, events = null)
        val empty = today(reminders, promises, events = emptyList())
        assertEquals(listOf("p", "r"), ids(denied))
        assertEquals(denied, empty)
        assertTrue(denied.none { it.kind == Kind.EVENT })
    }

    @Test
    fun `all-day events follow the local date, not the UTC midnight instant`() {
        val ny = at(newYork, 2026, 10, 15, 10, 0)
        val nyStart = at(newYork, 2026, 10, 15, 0, 0)
        val items = today(
            events = listOf(
                event("today", utc("2026-10-15T00:00:00Z"), utc("2026-10-16T00:00:00Z"), allDay = true),
                event("tomorrow", utc("2026-10-16T00:00:00Z"), utc("2026-10-17T00:00:00Z"), allDay = true),
                event("ended", utc("2026-10-14T00:00:00Z"), utc("2026-10-15T00:00:00Z"), allDay = true),
            ),
            nowMs = ny,
            zone = newYork,
        )
        assertEquals(listOf("today"), ids(items))
        assertEquals(nyStart, items.single().atMs)
    }

    @Test
    fun `the day of the clock change is 25 hours long`() {
        val sunday = at(kyiv, 2026, 10, 25, 12, 0)
        val items = today(
            reminders = listOf(
                reminder("in", utc("2026-10-25T21:30:00Z")),
                reminder("out", utc("2026-10-25T22:30:00Z")),
            ),
            nowMs = sunday,
        )
        assertEquals(listOf("in"), ids(items))
    }

    @Test
    fun `a timed event running through midnight starts at the day start, ties keep kind order`() {
        val items = today(
            events = listOf(
                event("night", d(10, 14, 22, 0), d(10, 15, 1, 0), title = ""),
                event("over", d(10, 14, 8, 0), dayStart),
                event("noon", d(10, 15, 12, 0), d(10, 15, 13, 0)),
                event("next", d(10, 16, 0, 0), d(10, 16, 1, 0)),
            ),
            reminders = listOf(reminder("r", d(10, 15, 12, 0))),
            promises = listOf(promise("p", d(10, 15, 12, 0))),
            plans = listOf(plan("g", d(10, 15, 12, 0))),
        )
        assertEquals(listOf("night", "p", "r", "g", "noon"), ids(items))
        assertEquals(dayStart, items.first().atMs)
        assertEquals("", items.first().title)
        assertEquals(d(10, 15, 1, 0), items.first().endMs)
    }

    @Test
    fun `a promise carries the person and the peer, and drops the colon when there is no thing`() {
        val items = today(
            promises = listOf(
                promise("a", d(10, 15, 11, 0), person = "Оля", what = "фото"),
                promise("b", d(10, 15, 12, 0), person = "Петро", what = ""),
            ),
        )
        assertEquals(listOf("Оля: фото", "Петро"), items.map { it.title })
        assertEquals(listOf("peer_a", "peer_b"), items.map { it.peerId })
        assertNull(today(reminders = listOf(reminder("r", d(10, 15, 11, 0)))).single().peerId)
    }
}
