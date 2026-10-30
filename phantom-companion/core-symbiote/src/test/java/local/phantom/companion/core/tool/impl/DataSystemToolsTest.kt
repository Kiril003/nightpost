package local.phantom.companion.core.tool.impl

import android.app.Application
import java.util.Calendar
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import local.phantom.companion.core.data.calendar.CalendarEventDao
import local.phantom.companion.core.data.calendar.CalendarEventEntity
import local.phantom.companion.core.data.calendar.CalendarStore
import local.phantom.companion.core.data.memory.MemoryFactDao
import local.phantom.companion.core.data.memory.MemoryFactEntity
import local.phantom.companion.core.data.memory.MemoryFactStore
import local.phantom.companion.core.data.places.SavedPlaceDao
import local.phantom.companion.core.data.places.SavedPlaceEntity
import local.phantom.companion.core.data.places.SavedPlaceStore
import local.phantom.companion.core.data.reminders.ReminderDao
import local.phantom.companion.core.data.reminders.ReminderEntity
import local.phantom.companion.core.data.reminders.ReminderStore
import local.phantom.companion.core.data.tasks.TaskDao
import local.phantom.companion.core.data.tasks.TaskEntity
import local.phantom.companion.core.data.tasks.TaskStore
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The four data-systems have to be operable by voice: the operator never sees a
 * UUID, so every removal verb answers to the words on the card, and no wall-clock
 * time is ever handed to the model to convert.
 */
class DataSystemToolsTest {

    private val ctx = ToolContext(
        appContext = Application(),
        operatorId = "tester",
        requestId = "req-1",
        approvedPermissions = emptySet(),
    )

    private suspend fun run(tool: local.phantom.companion.core.tool.Tool, args: JsonObject): ToolResult =
        tool.execute(args, ctx) {}

    private fun at(y: Int, mo: Int, d: Int, h: Int, min: Int): Long =
        Calendar.getInstance().apply { clear(); set(y, mo - 1, d, h, min) }.timeInMillis

    private fun fieldsOf(ms: Long): List<Int> = Calendar.getInstance().apply { timeInMillis = ms }.let {
        listOf(
            it.get(Calendar.YEAR), it.get(Calendar.MONTH) + 1, it.get(Calendar.DAY_OF_MONTH),
            it.get(Calendar.HOUR_OF_DAY), it.get(Calendar.MINUTE),
        )
    }

    // --- reminder.remove ------------------------------------------------

    @Test
    fun `reminder is removed by id`() = runTest {
        val dao = FakeReminderDao()
        val store = ReminderStore(dao)
        val id = store.add("полити квіти", 1L)
        val result = run(ReminderRemoveTool(store), buildJsonObject { put("id", id) })
        assertTrue(result is ToolResult.Ok)
        assertEquals(0, dao.rows.size)
    }

    @Test
    fun `reminder is removed by the words the operator said`() = runTest {
        val dao = FakeReminderDao()
        val store = ReminderStore(dao)
        store.add("полити квіти", 1L)
        val keep = store.add("забрати посилку", 2L)
        val result = run(ReminderRemoveTool(store), buildJsonObject { put("text", "КВІТИ") })
        assertTrue(result is ToolResult.Ok)
        assertEquals(listOf(keep), dao.rows.keys.toList())
    }

    @Test
    fun `several matching reminders are listed instead of guessed`() = runTest {
        val store = ReminderStore(FakeReminderDao())
        store.add("полити квіти вдома", 1L)
        store.add("полити квіти в офісі", 2L)
        val result = run(ReminderRemoveTool(store), buildJsonObject { put("text", "полити квіти") })
        assertTrue(result is ToolResult.Err)
        assertEquals("ambiguous", (result as ToolResult.Err).code)
        assertTrue(result.message.contains("вдома") && result.message.contains("в офісі"))
    }

    @Test
    fun `an exact reminder text wins over a longer substring match`() = runTest {
        val dao = FakeReminderDao()
        val store = ReminderStore(dao)
        val exact = store.add("хліб", 1L)
        val longer = store.add("хліб чорний", 2L)
        val result = run(ReminderRemoveTool(store), buildJsonObject { put("text", "хліб") })
        assertTrue(result is ToolResult.Ok)
        assertNull(dao.rows[exact])
        assertEquals(listOf(longer), dao.rows.keys.toList())
    }

    @Test
    fun `words sent as id still find the reminder`() = runTest {
        val dao = FakeReminderDao()
        val store = ReminderStore(dao)
        store.add("забрати посилку", 1L)
        val result = run(ReminderRemoveTool(store), buildJsonObject { put("id", "посилку") })
        assertTrue(result is ToolResult.Ok)
        assertEquals(0, dao.rows.size)
    }

    @Test
    fun `a reminder that is not there is reported, not faked`() = runTest {
        val store = ReminderStore(FakeReminderDao())
        store.add("полити квіти", 1L)
        val result = run(ReminderRemoveTool(store), buildJsonObject { put("text", "купити молоко") })
        assertEquals("not_found", (result as ToolResult.Err).code)
        val empty = run(ReminderRemoveTool(store), buildJsonObject { })
        assertEquals("bad_args", (empty as ToolResult.Err).code)
    }

    // --- calendar.add / calendar.remove ---------------------------------

    @Test
    fun `calendar takes wall-clock text and resolves it on the device`() = runTest {
        val dao = FakeCalendarDao()
        val result = run(
            CalendarAddTool(CalendarStore(dao)),
            buildJsonObject {
                put("title", "Стоматолог")
                put("local_time", "2026-07-27 09:00")
                put("local_end", "2026-07-27 10:30")
            },
        )
        assertTrue(result is ToolResult.Ok)
        val row = dao.rows.values.single()
        assertEquals(listOf(2026, 7, 27, 9, 0), fieldsOf(row.startMs))
        assertEquals(listOf(2026, 7, 27, 10, 30), fieldsOf(row.endMs!!))
    }

    @Test
    fun `a bare end time belongs to the day the event starts`() = runTest {
        val dao = FakeCalendarDao()
        run(
            CalendarAddTool(CalendarStore(dao)),
            buildJsonObject {
                put("title", "Зміна")
                put("local_time", "2026-07-27 23:00")
                put("local_end", "01:00")
            },
        )
        val row = dao.rows.values.single()
        assertEquals(listOf(2026, 7, 28, 1, 0), fieldsOf(row.endMs!!))
    }

    @Test
    fun `epoch still works and a missing time is refused`() = runTest {
        val dao = FakeCalendarDao()
        val tool = CalendarAddTool(CalendarStore(dao))
        val fixed = at(2026, 7, 27, 9, 0)
        assertTrue(run(tool, buildJsonObject { put("title", "Зустріч"); put("start_ms", fixed) }) is ToolResult.Ok)
        assertEquals(fixed, dao.rows.values.single().startMs)
        val bare = run(tool, buildJsonObject { put("title", "Без часу") })
        assertEquals("bad_args", (bare as ToolResult.Err).code)
    }

    @Test
    fun `an event is removed by its title`() = runTest {
        val dao = FakeCalendarDao()
        val store = CalendarStore(dao)
        store.add("Стоматолог", at(2126, 7, 27, 9, 0))
        store.add("Батьківські збори", at(2126, 7, 28, 18, 0))
        val result = run(CalendarRemoveTool(store), buildJsonObject { put("title", "стоматолог") })
        assertTrue(result is ToolResult.Ok)
        assertEquals(listOf("Батьківські збори"), dao.rows.values.map { it.title })
    }

    // --- task.add / task.done / task.remove ------------------------------

    @Test
    fun `task deadline comes as wall-clock text`() = runTest {
        val dao = FakeTaskDao()
        val result = run(
            TaskAddTool(TaskStore(dao)),
            buildJsonObject { put("title", "здати звіт"); put("local_due", "2026-07-27 18:00") },
        )
        assertTrue(result is ToolResult.Ok)
        assertEquals(listOf(2026, 7, 27, 18, 0), fieldsOf(dao.rows.values.single().dueMs!!))
    }

    @Test
    fun `a task is ticked off by its title, ignoring the already-done ones`() = runTest {
        val dao = FakeTaskDao()
        val store = TaskStore(dao)
        val ticked = store.add("купити хліб")
        store.setDone(ticked, true)
        val open = store.add("купити хліб на завтра")
        val result = run(TaskDoneTool(store), buildJsonObject { put("title", "купити хліб") })
        assertTrue(result is ToolResult.Ok)
        assertTrue(dao.rows[open]!!.done)
    }

    @Test
    fun `a wrongly added task can be deleted by title`() = runTest {
        val dao = FakeTaskDao()
        val store = TaskStore(dao)
        store.add("купити цемент")
        val keep = store.add("купити хліб")
        val result = run(TaskRemoveTool(store), buildJsonObject { put("title", "цемент") })
        assertTrue(result is ToolResult.Ok)
        assertEquals(listOf(keep), dao.rows.keys.toList())
    }

    @Test
    fun `task verbs refuse to act without a handle`() = runTest {
        val store = TaskStore(FakeTaskDao())
        assertEquals("bad_args", (run(TaskDoneTool(store), buildJsonObject { }) as ToolResult.Err).code)
        assertEquals("bad_args", (run(TaskRemoveTool(store), buildJsonObject { }) as ToolResult.Err).code)
        assertEquals("not_found", (run(TaskRemoveTool(store), buildJsonObject { put("title", "нема") }) as ToolResult.Err).code)
    }

    // --- place.remove -----------------------------------------------------

    @Test
    fun `a saved place is forgotten by its label`() = runTest {
        val dao = FakeSavedPlaceDao()
        val store = SavedPlaceStore(dao)
        store.save("Дім", 50.45, 30.52)
        val work = store.save("Робота", 50.44, 30.51)
        val result = run(PlaceRemoveTool(store), buildJsonObject { put("label", "дім") })
        assertTrue(result is ToolResult.Ok)
        assertEquals(listOf(work), dao.rows.keys.toList())
    }

    @Test
    fun `two places under one word are handed back for the operator to pick`() = runTest {
        val store = SavedPlaceStore(FakeSavedPlaceDao())
        store.save("Схрон північ", 50.45, 30.52)
        store.save("Схрон південь", 50.40, 30.50)
        val result = run(PlaceRemoveTool(store), buildJsonObject { put("label", "схрон") })
        assertEquals("ambiguous", (result as ToolResult.Err).code)
        assertTrue(result.message.contains("північ") && result.message.contains("південь"))
    }

    // --- memory tools (memory.save, memory.list, memory.forget) ---------

    @Test
    fun `memory fact is saved, listed, and forgotten`() = runTest {
        val dao = FakeMemoryFactDao()
        val store = MemoryFactStore(dao)

        val saveTool = MemorySaveTool(store)
        val listTool = MemoryListTool(store)
        val forgetTool = MemoryForgetTool(store)

        val saveRes = run(
            saveTool,
            buildJsonObject {
                put("text", "Оператор пʼє чорну каву без цукру")
                put("category", "preference")
            },
        )
        assertTrue(saveRes is ToolResult.Ok)
        assertEquals(1, dao.count())

        val listRes = run(listTool, buildJsonObject {})
        assertTrue(listRes is ToolResult.Ok)
        assertTrue((listRes as ToolResult.Ok).output.contains("чорну каву"))

        val forgetRes = run(forgetTool, buildJsonObject { put("query", "каву") })
        assertTrue(forgetRes is ToolResult.Ok)
        assertEquals(0, dao.count())
    }
}

private class FakeMemoryFactDao : MemoryFactDao {
    val rows = LinkedHashMap<String, MemoryFactEntity>()
    override suspend fun insert(fact: MemoryFactEntity) { rows[fact.id] = fact }
    override suspend fun update(fact: MemoryFactEntity) { rows[fact.id] = fact }
    override suspend fun getAll(): List<MemoryFactEntity> = rows.values.toList()
    override suspend fun getByCategory(category: String): List<MemoryFactEntity> =
        rows.values.filter { it.category == category }
    override suspend fun recent(limit: Int): List<MemoryFactEntity> =
        rows.values.sortedByDescending { it.lastConfirmedMs }.take(limit)
    override suspend fun byEntity(entityId: String): List<MemoryFactEntity> =
        rows.values.filter { it.entityId == entityId }
    override suspend fun count(): Int = rows.size
    override suspend fun setConfirmed(id: String, confirmed: Int) {
        rows[id]?.let { rows[id] = it.copy(confirmed = confirmed) }
    }
    override suspend fun setEntity(id: String, entityId: String?) {
        rows[id]?.let { rows[id] = it.copy(entityId = entityId) }
    }
    override suspend fun deleteById(id: String) { rows.remove(id) }
    override suspend fun deleteByEntity(entityId: String) {
        rows.values.filter { it.entityId == entityId }.forEach { rows.remove(it.id) }
    }
    override suspend fun pruneBelow(floor: Float): Int {
        val before = rows.size
        rows.entries.removeAll { it.value.confidence < floor }
        return before - rows.size
    }
    override suspend fun clear() { rows.clear() }
}

private class FakeReminderDao : ReminderDao {
    val rows = LinkedHashMap<String, ReminderEntity>()
    override suspend fun insert(reminder: ReminderEntity) { rows[reminder.id] = reminder }
    override suspend fun update(reminder: ReminderEntity) { rows[reminder.id] = reminder }
    override suspend fun getAll(): List<ReminderEntity> = rows.values.sortedBy { it.triggerAtMs }
    override suspend fun pending(limit: Int): List<ReminderEntity> =
        rows.values.filter { !it.fired }.sortedBy { it.triggerAtMs }.take(limit)
    override fun pendingFlow(limit: Int): Flow<List<ReminderEntity>> = flowOf(rows.values.filter { !it.fired }.take(limit))
    override suspend fun due(nowMs: Long): List<ReminderEntity> = rows.values.filter { !it.fired && it.triggerAtMs <= nowMs }
    override suspend fun byId(id: String): ReminderEntity? = rows[id]
    override suspend fun markFired(id: String): Int =
        rows[id]?.let { rows[id] = it.copy(fired = true); 1 } ?: 0
    override suspend fun deleteById(id: String) { rows.remove(id) }
    override suspend fun count(): Int = rows.size
}

private class FakeCalendarDao : CalendarEventDao {
    val rows = LinkedHashMap<String, CalendarEventEntity>()
    override suspend fun insert(event: CalendarEventEntity) { rows[event.id] = event }
    override suspend fun update(event: CalendarEventEntity) { rows[event.id] = event }
    override suspend fun getAll(): List<CalendarEventEntity> = rows.values.sortedBy { it.startMs }
    override suspend fun inRange(fromMs: Long, toMs: Long): List<CalendarEventEntity> =
        rows.values.filter { it.startMs in fromMs until toMs }.sortedBy { it.startMs }
    override suspend fun upcoming(fromMs: Long, limit: Int): List<CalendarEventEntity> =
        rows.values.filter { it.startMs >= fromMs }.sortedBy { it.startMs }.take(limit)
    override fun upcomingFlow(fromMs: Long, limit: Int): Flow<List<CalendarEventEntity>> = flowOf(upcomingNow(fromMs, limit))
    private fun upcomingNow(fromMs: Long, limit: Int) =
        rows.values.filter { it.startMs >= fromMs }.sortedBy { it.startMs }.take(limit)
    override suspend fun byId(id: String): CalendarEventEntity? = rows[id]
    override suspend fun deleteById(id: String) { rows.remove(id) }
    override suspend fun count(): Int = rows.size
}

private class FakeTaskDao : TaskDao {
    val rows = LinkedHashMap<String, TaskEntity>()
    override suspend fun insert(task: TaskEntity) { rows[task.id] = task }
    override suspend fun update(task: TaskEntity) { rows[task.id] = task }
    override suspend fun getAll(): List<TaskEntity> = rows.values.toList()
    override suspend fun byList(listName: String): List<TaskEntity> = rows.values.filter { it.listName == listName }
    override suspend fun pending(limit: Int): List<TaskEntity> = rows.values.filter { !it.done }.take(limit)
    override fun byListFlow(listName: String): Flow<List<TaskEntity>> = flowOf(rows.values.filter { it.listName == listName })
    override fun pendingFlow(limit: Int): Flow<List<TaskEntity>> = flowOf(rows.values.filter { !it.done }.take(limit))
    override suspend fun byId(id: String): TaskEntity? = rows[id]
    override suspend fun maxPosition(listName: String): Int =
        rows.values.filter { it.listName == listName }.maxOfOrNull { it.position } ?: -1
    override suspend fun setDone(id: String, done: Boolean, completedAt: Long?): Int =
        rows[id]?.let { rows[id] = it.copy(done = done, completedAt = completedAt); 1 } ?: 0
    override suspend fun deleteById(id: String) { rows.remove(id) }
    override suspend fun count(): Int = rows.size
}

private class FakeSavedPlaceDao : SavedPlaceDao {
    val rows = LinkedHashMap<String, SavedPlaceEntity>()
    override suspend fun insert(place: SavedPlaceEntity) { rows[place.id] = place }
    override suspend fun update(place: SavedPlaceEntity) { rows[place.id] = place }
    override suspend fun getAll(): List<SavedPlaceEntity> = rows.values.toList()
    override suspend fun byCategory(category: String): List<SavedPlaceEntity> = rows.values.filter { it.category == category }
    override fun allFlow(): Flow<List<SavedPlaceEntity>> = flowOf(rows.values.toList())
    override fun byCategoryFlow(category: String): Flow<List<SavedPlaceEntity>> = flowOf(rows.values.filter { it.category == category })
    override suspend fun byLabel(label: String): SavedPlaceEntity? = rows.values.firstOrNull { it.label.equals(label, true) }
    override suspend fun byId(id: String): SavedPlaceEntity? = rows[id]
    override suspend fun deleteById(id: String) { rows.remove(id) }
    override suspend fun count(): Int = rows.size
}
