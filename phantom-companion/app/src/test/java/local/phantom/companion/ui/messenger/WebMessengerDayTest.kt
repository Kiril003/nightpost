package local.phantom.companion.ui.messenger

import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import local.phantom.companion.self.Commitment
import local.phantom.companion.self.CommitmentService
import local.phantom.companion.self.CommitmentState
import local.phantom.companion.self.DayAgenda
import local.phantom.companion.self.InMemoryCommitmentStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** K3: `/messenger/day` over real stores and fake phone ports. Clock: Thu 2026-10-15 10:00 Europe/Kyiv. */
class WebMessengerDayTest {

    private val kyiv: ZoneId = ZoneId.of("Europe/Kyiv")
    private fun d(h: Int, min: Int, day: Int = 15): Long =
        LocalDateTime.of(2026, 10, day, h, min).atZone(kyiv).toInstant().toEpochMilli()

    private val now = d(10, 0)
    private val seg = listOf("messenger", "day")

    private class Reminders(var rows: List<DayAgenda.ReminderLine> = emptyList()) : WebMessengerDay.Reminders {
        override suspend fun pending() = rows
    }

    private class Calendar(
        var granted: Boolean = true,
        var events: List<DayAgenda.CalendarEvent> = emptyList(),
    ) : WebMessengerDay.Calendar {
        val windows = mutableListOf<Pair<Long, Long>>()
        var asked = 0
        override fun granted() = granted
        override fun instances(fromMs: Long, toMs: Long): List<DayAgenda.CalendarEvent> {
            windows += fromMs to toMs
            return events
        }
        override fun ask(): Boolean {
            asked++
            return true
        }
    }

    private val store = InMemoryCommitmentStore()
    private val promises = CommitmentService(store = store, roster = { emptyList() }, now = { now }, zone = { kyiv })
    private val reminders = Reminders()
    private val calendar = Calendar()
    private var plans: List<DayAgenda.PlanLine> = emptyList()
    private val door = WebMessengerDay(reminders, promises, calendar, { plans }, { now }, { kyiv })

    private fun promise(id: String, due: Long?, person: String = "Оля", what: String = "фото") {
        store.add(Commitment(id, "$person: $what", person, "peer_olia", what, due, now - 3_600_000, ""))
    }

    private fun call(method: String, vararg tail: String, body: String? = null, via: WebMessengerDay = door): JsonObject =
        Json.parseToJsonElement(runBlocking { via.handle(method, seg + tail, body) }).jsonObject

    private fun status(o: JsonObject) = o["status"]!!.jsonPrimitive.int
    private fun data(o: JsonObject): JsonObject {
        assertTrue("no data: $o", o["ok"]?.jsonPrimitive?.boolean == true)
        return o["data"]!!.jsonObject
    }
    private fun items(o: JsonObject) = data(o)["items"]!!.jsonArray.map { it.jsonObject }
    private fun code(o: JsonObject) = o["code"]!!.jsonPrimitive.content

    @Test
    fun `GET lists promise, reminder and event for today in time order and nothing from tomorrow`() {
        promise("cmt_1", d(14, 0))
        reminders.rows = listOf(
            DayAgenda.ReminderLine("r1", "Call mom", d(18, 30)),
            DayAgenda.ReminderLine("r2", "Tomorrow thing", d(9, 0, day = 16)),
        )
        calendar.events = listOf(DayAgenda.CalendarEvent("7:1", "Dentist", d(12, 0), d(13, 0), false))

        val res = call("GET")
        assertTrue(res["ok"]!!.jsonPrimitive.boolean)
        assertEquals(200, status(res))
        assertEquals(now, data(res)["now_ms"]!!.jsonPrimitive.long)
        assertEquals("granted", data(res)["calendar"]!!.jsonPrimitive.content)

        val rows = items(res)
        assertEquals(listOf("7:1", "cmt_1", "r1"), rows.map { it["id"]!!.jsonPrimitive.content })
        assertEquals(listOf("event", "promise", "reminder"), rows.map { it["kind"]!!.jsonPrimitive.content })
        val event = rows[0]
        assertEquals("Dentist", event["title"]!!.jsonPrimitive.content)
        assertEquals(d(12, 0), event["at_ms"]!!.jsonPrimitive.long)
        assertEquals(d(13, 0), event["end_ms"]!!.jsonPrimitive.long)
        assertFalse(event["all_day"]!!.jsonPrimitive.boolean)
        assertTrue(event["peer_id"] is JsonNull)
        val promise = rows[1]
        assertEquals("Оля: фото", promise["title"]!!.jsonPrimitive.content)
        assertEquals("peer_olia", promise["peer_id"]!!.jsonPrimitive.content)
        assertTrue(promise["end_ms"] is JsonNull)
        assertFalse(promise["overdue"]!!.jsonPrimitive.boolean)
        assertEquals("Call mom", rows[2]["title"]!!.jsonPrimitive.content)
    }

    @Test
    fun `without calendar access the answer says denied, has no events and never queries the calendar`() {
        reminders.rows = listOf(DayAgenda.ReminderLine("r1", "Call mom", d(18, 30)))
        calendar.granted = false
        calendar.events = listOf(DayAgenda.CalendarEvent("7:1", "Dentist", d(12, 0), d(13, 0), false))

        val res = call("GET")
        assertEquals("denied", data(res)["calendar"]!!.jsonPrimitive.content)
        assertEquals(listOf("reminder"), items(res).map { it["kind"]!!.jsonPrimitive.content })
        assertTrue(calendar.windows.isEmpty())
    }

    @Test
    fun `the calendar is asked for a window that reaches a day past each side of today`() {
        call("GET")
        assertEquals(1, calendar.windows.size)
        val (from, to) = calendar.windows.single()
        assertEquals(d(0, 0, day = 14), from)
        assertEquals(d(0, 0, day = 17), to)
    }

    @Test
    fun `plan rows from the plan source join the list`() {
        plans = listOf(DayAgenda.PlanLine("grp1", "Coffee at Studentska", d(16, 0)))
        val rows = items(call("GET"))
        assertEquals(listOf("plan"), rows.map { it["kind"]!!.jsonPrimitive.content })
        assertEquals("grp1", rows.single()["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `done closes the promise in the store and the next GET leaves it out`() {
        promise("cmt_1", d(14, 0))
        val res = call("POST", "promises", "cmt_1", "done")
        assertEquals(200, status(res))
        assertEquals("done", data(res)["state"]!!.jsonPrimitive.content)
        assertEquals(CommitmentState.DONE, store.get("cmt_1")!!.state)
        assertTrue(items(call("GET")).isEmpty())
    }

    @Test
    fun `drop marks the promise dropped`() {
        promise("cmt_1", d(14, 0))
        val res = call("DELETE", "promises", "cmt_1")
        assertEquals(200, status(res))
        assertEquals("dropped", data(res)["state"]!!.jsonPrimitive.content)
        assertEquals(CommitmentState.DROPPED, store.get("cmt_1")!!.state)
    }

    @Test
    fun `an unknown promise is 404 not_found for done and for drop`() {
        val done = call("POST", "promises", "nope", "done")
        assertEquals(404, status(done))
        assertEquals("not_found", code(done))
        assertEquals(404, status(call("DELETE", "promises", "nope")))
    }

    @Test
    fun `the calendar ask goes to the phone once when access is missing and not at all when held`() {
        calendar.granted = false
        val asked = data(call("POST", "calendar", "ask"))
        assertEquals(1, calendar.asked)
        assertTrue(asked["asked"]!!.jsonPrimitive.boolean)
        assertFalse(asked["granted"]!!.jsonPrimitive.boolean)

        calendar.granted = true
        val held = data(call("POST", "calendar", "ask"))
        assertEquals(1, calendar.asked)
        assertFalse(held["asked"]!!.jsonPrimitive.boolean)
        assertTrue(held["granted"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `only the day paths are claimed, others and wrong methods are 404`() {
        assertTrue(door.claims(seg))
        assertTrue(door.claims(seg + listOf("promises", "x", "done")))
        assertFalse(door.claims(listOf("messenger", "reminders", "r1", "snooze")))
        assertFalse(door.claims(listOf("messenger")))
        assertEquals(404, status(call("GET", "foo")))
        assertEquals(404, status(call("POST")))
        assertEquals("not_found", code(call("PUT", "promises", "x", "done")))
    }

    @Test
    fun `an overdue reminder from the day list snoozes through the reminders door and comes back moved`() {
        class Table : WebMessengerReminders.Store {
            val rows = LinkedHashMap<String, WebMessengerReminders.Row>()
            var seq = 0
            override suspend fun add(text: String, atMs: Long): String {
                val id = "r${++seq}"
                rows[id] = WebMessengerReminders.Row(id, text, atMs)
                return id
            }
            override suspend fun pending() = rows.values.toList()
            override suspend fun remove(id: String) = rows.remove(id) != null
        }
        val table = Table()
        runBlocking { table.add("Call mom", now - 5 * 60_000) }
        val shared = WebMessengerDay(
            { table.rows.values.map { DayAgenda.ReminderLine(it.id, it.text, it.atMs) } },
            promises, calendar, { emptyList() }, { now }, { kyiv },
        )
        val before = items(call("GET", via = shared)).single()
        assertTrue(before["overdue"]!!.jsonPrimitive.boolean)

        val snoozer = WebMessengerReminders(table) { now }
        val moved = Json.parseToJsonElement(
            runBlocking {
                snoozer.handle(
                    "POST",
                    listOf("messenger", "reminders", before["id"]!!.jsonPrimitive.content, "snooze"),
                    """{"minutes":10}""",
                )
            },
        ).jsonObject
        assertEquals(200, status(moved))

        val after = items(call("GET", via = shared)).single()
        assertEquals(now + 10 * 60_000, after["at_ms"]!!.jsonPrimitive.long)
        assertFalse(after["overdue"]!!.jsonPrimitive.boolean)
        assertEquals(data(moved)["id"]!!.jsonPrimitive.content, after["id"]!!.jsonPrimitive.content)
        assertNull(table.rows[before["id"]!!.jsonPrimitive.content])
    }

    private class FakeAlerts(var notif: Boolean?, var exact: Boolean?) : WebMessengerDay.Alerts {
        val opened = mutableListOf<String>()
        override fun notifications() = notif
        override fun exact() = exact
        override fun open(kind: String): Boolean {
            opened += kind
            return true
        }
    }

    @Test
    fun `GET carries notification and exact-alarm state, and alerts-open only takes known kinds`() {
        val alerts = FakeAlerts(notif = false, exact = true)
        val via = WebMessengerDay(reminders, promises, calendar, { plans }, { now }, { kyiv }, alerts)
        val d = data(call("GET", via = via))
        assertFalse(d["notifications"]!!.jsonPrimitive.boolean)
        assertTrue(d["exact_alarms"]!!.jsonPrimitive.boolean)
        val ok = call("POST", "alerts", "open", body = """{"kind":"notifications"}""", via = via)
        assertTrue(data(ok)["opened"]!!.jsonPrimitive.boolean)
        assertEquals(listOf("notifications"), alerts.opened)
        assertEquals(400, status(call("POST", "alerts", "open", body = """{"kind":"wifi"}""", via = via)))
        assertNull(data(call("GET")).get("notifications"))
    }
}
