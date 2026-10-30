package local.phantom.companion.ui.messenger

import java.io.File
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Нагадування з листа мусить лягти в ТУ САМУ таблицю, яку ReminderAlarmSync
 * дзеркалить у AlarmManager, — інакше воно дзвонить лише поки відкрита сторінка.
 */
class WebMessengerRemindersTest {

    private class Table : WebMessengerReminders.Store {
        val rows = LinkedHashMap<String, WebMessengerReminders.Row>()
        val calls = mutableListOf<String>()
        var seq = 0
        var rejectAdd = false
        override suspend fun add(text: String, atMs: Long): String {
            calls += "add:$text:$atMs"
            if (rejectAdd) return ""
            val id = "r${++seq}"
            rows[id] = WebMessengerReminders.Row(id, text, atMs)
            return id
        }
        override suspend fun pending() = rows.values.toList()
        override suspend fun remove(id: String): Boolean {
            calls += "remove:$id"
            return rows.remove(id) != null
        }
    }

    private val now = 1_790_000_000_000L
    private val seg = listOf("messenger", "reminders")
    private fun json(s: String) = Json.parseToJsonElement(s).jsonObject
    private fun h(r: WebMessengerReminders, m: String, seg: List<String>, body: String?) =
        kotlinx.coroutines.runBlocking { r.handle(m, seg, body) }

    @Test
    fun `POST кладе рядок у таблицю, GET його віддає, DELETE прибирає`() {
        val table = Table()
        val route = WebMessengerReminders(table) { now }
        val at = now + 20 * 60_000
        val put = json(h(route, "POST", seg, """{"text":"Оксана: Посилка чекає до 20:00","at_ms":$at}"""))
        assertEquals(true, put["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(WebMessengerReminders.Row("r1", "Оксана: Посилка чекає до 20:00", at), table.rows["r1"])

        val list = json(h(route, "GET", seg, null))["data"]!!.jsonArray
        assertEquals(1, list.size)
        assertEquals(at, list[0].jsonObject["at_ms"]!!.jsonPrimitive.long)

        val del = json(h(route, "DELETE", seg + "r1", null))
        assertEquals(200, del["status"]!!.jsonPrimitive.content.toInt())
        assertTrue(table.rows.isEmpty())
        assertEquals(404, json(h(route, "DELETE", seg + "r1", null))["status"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `минулий момент, порожній текст і сміття — відмова, таблиця чиста`() {
        val table = Table()
        val route = WebMessengerReminders(table) { now }
        assertEquals(422, json(h(route, "POST", seg, """{"text":"x","at_ms":${now - 1}}"""))["status"]!!.jsonPrimitive.content.toInt())
        assertEquals(400, json(h(route, "POST", seg, """{"text":"  ","at_ms":${now + 60_000}}"""))["status"]!!.jsonPrimitive.content.toInt())
        assertEquals(400, json(h(route, "POST", seg, "not json"))["status"]!!.jsonPrimitive.content.toInt())
        assertTrue(table.rows.isEmpty())
    }

    @Test
    fun `міст віддає шлях саме цьому класу, а той пише в reminderStore`() {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        val bridge = File(dir, "app/src/main/java/local/phantom/companion/ui/messenger/WebMessengerBridge.kt").readText()
        assertTrue(bridge.contains("if (reminders.claims(seg)) return reminders.handle(method, seg, body)"))
        assertTrue(bridge.contains("container.reminderStore.add(text = text, triggerAtMs = atMs)"))
    }

    @Test
    fun `snooze без тіла відкладає на 10 хвилин, а з minutes — на вказаний час`() {
        val table = Table()
        val route = WebMessengerReminders(table) { now }
        val origAt = now + 20 * 60_000
        h(route, "POST", seg, """{"text":"Оксана: Посилка","at_ms":$origAt}""")
        table.calls.clear()

        val snoozed = json(h(route, "POST", listOf("messenger", "reminders", "r1", "snooze"), null))
        assertEquals(200, snoozed["status"]!!.jsonPrimitive.content.toInt())
        val data = snoozed["data"]!!.jsonObject
        val expected10 = now + 10 * 60_000
        assertEquals("r2", data["id"]!!.jsonPrimitive.content)
        assertEquals(expected10, data["at_ms"]!!.jsonPrimitive.long)
        assertEquals(listOf("add:Оксана: Посилка:$expected10", "remove:r1"), table.calls)
        assertEquals(listOf(WebMessengerReminders.Row("r2", "Оксана: Посилка", expected10)), table.rows.values.toList())

        table.calls.clear()
        val snoozed45 = json(h(route, "POST", listOf("messenger", "reminders", "r2", "snooze"), """{"minutes":45}"""))
        assertEquals(200, snoozed45["status"]!!.jsonPrimitive.content.toInt())
        val expected45 = now + 45 * 60_000
        assertEquals("r3", snoozed45["data"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals(expected45, snoozed45["data"]!!.jsonObject["at_ms"]!!.jsonPrimitive.long)
        assertEquals(listOf("add:Оксана: Посилка:$expected45", "remove:r2"), table.calls)
    }

    @Test
    fun `snooze відхиляє хибні хвилини, невідомий id і порожню вставку без видалення старого рядка`() {
        val table = Table()
        val route = WebMessengerReminders(table) { now }
        val origAt = now + 20 * 60_000
        h(route, "POST", seg, """{"text":"Оксана: Посилка","at_ms":$origAt}""")
        table.calls.clear()

        for (bad in listOf("""{"minutes":0}""", """{"minutes":1441}""", """{"minutes":"x"}""", """{"minutes":1.5}""")) {
            val res = json(h(route, "POST", listOf("messenger", "reminders", "r1", "snooze"), bad))
            assertEquals("for $bad", 400, res["status"]!!.jsonPrimitive.content.toInt())
            assertEquals("for $bad", "bad_body", res["code"]!!.jsonPrimitive.content)
        }
        assertTrue(table.calls.isEmpty())
        assertEquals(1, table.rows.size)

        val gone = json(h(route, "POST", listOf("messenger", "reminders", "r999", "snooze"), null))
        assertEquals(404, gone["status"]!!.jsonPrimitive.content.toInt())
        assertEquals("gone", gone["code"]!!.jsonPrimitive.content)
        assertTrue(table.calls.isEmpty())

        table.rejectAdd = true
        val empty = json(h(route, "POST", listOf("messenger", "reminders", "r1", "snooze"), null))
        assertEquals(400, empty["status"]!!.jsonPrimitive.content.toInt())
        assertEquals("empty", empty["code"]!!.jsonPrimitive.content)
        assertEquals(listOf("add:Оксана: Посилка:${now + 10 * 60_000}"), table.calls)
        assertTrue("старий рядок мусить лишитись, коли add повернув порожній id", table.rows.containsKey("r1"))
    }

    @Test
    fun `таблиця змінилась — один поштовх, перше читання й повтор без змін мовчать`() {
        val a = "a" to 100L
        val b = "b" to 200L
        val ticks = kotlinx.coroutines.runBlocking {
            WebMessengerReminders.changes(flowOf(listOf(a), listOf(a), listOf(a, b), listOf(a, b), listOf(b), emptyList())).toList()
        }
        assertEquals(3, ticks.size)
    }

    @Test
    fun `поштовх кличе гачок, який бандл оголошує, і хост підʼєднує його до таблиці`() {
        val mono = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "phantom-os/src/frontend/src").isDirectory }
        val hook = "__phantomRemindersChanged"
        assertEquals("window.$hook && window.$hook();", WebMessengerReminders.CHANGED_JS)
        val bus = File(mono, "phantom-os/src/frontend/src/services/remindersBus.ts").readText()
        assertTrue("бандл не оголошує гачок", "window as unknown as { $hook?" in bus)
        val host = File(mono, "phantom-companion/app/src/main/java/local/phantom/companion/ui/messenger/WebMessengerHost.kt").readText()
        assertTrue("хост не слухає таблицю нагадувань", "WebMessengerReminders.changes(" in host && "reminderStore.pendingFlow" in host)
        assertTrue("хост не штовхає гачок", "evaluateJavascript(WebMessengerReminders.CHANGED_JS" in host)
    }
}
