package local.phantom.companion.core.data.systems

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import local.phantom.companion.core.data.calendar.CalendarEventEntity
import local.phantom.companion.core.data.calendar.CalendarEventDao
import local.phantom.companion.core.data.calendar.CalendarStore
import local.phantom.companion.core.data.places.SavedPlaceEntity
import local.phantom.companion.core.data.places.SavedPlaceDao
import local.phantom.companion.core.data.places.SavedPlaceStore
import local.phantom.companion.core.data.reminders.ReminderEntity
import local.phantom.companion.core.data.reminders.ReminderDao
import local.phantom.companion.core.data.reminders.ReminderStore
import local.phantom.companion.core.data.tasks.TaskEntity
import local.phantom.companion.core.data.tasks.TaskDao
import local.phantom.companion.core.data.tasks.TaskStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM guards for the non-trivial store logic (no Room / no Android). */
class DataSystemStoresTest {

    private class FakeCalendarDao : CalendarEventDao {
        val rows = LinkedHashMap<String, CalendarEventEntity>()
        override suspend fun insert(event: CalendarEventEntity) { rows[event.id] = event }
        override suspend fun update(event: CalendarEventEntity) { rows[event.id] = event }
        override suspend fun getAll() = rows.values.sortedBy { it.startMs }
        override suspend fun inRange(fromMs: Long, toMs: Long) =
            rows.values.filter { it.startMs in fromMs until toMs }.sortedBy { it.startMs }
        override suspend fun upcoming(fromMs: Long, limit: Int) =
            rows.values.filter { it.startMs >= fromMs }.sortedBy { it.startMs }.take(limit)
        override fun upcomingFlow(fromMs: Long, limit: Int) =
            flowOf(rows.values.filter { it.startMs >= fromMs }.sortedBy { it.startMs }.take(limit))
        override suspend fun byId(id: String) = rows[id]
        override suspend fun deleteById(id: String) { rows.remove(id) }
        override suspend fun count() = rows.size
    }

    private class FakeTaskDao : TaskDao {
        val rows = LinkedHashMap<String, TaskEntity>()
        override suspend fun insert(task: TaskEntity) { rows[task.id] = task }
        override suspend fun update(task: TaskEntity) { rows[task.id] = task }
        override suspend fun getAll() = rows.values.toList()
        override suspend fun byList(listName: String) = rows.values.filter { it.listName == listName }
        override fun byListFlow(listName: String) = flowOf(rows.values.filter { it.listName == listName })
        override suspend fun pending(limit: Int) = rows.values.filter { !it.done }.take(limit)
        override fun pendingFlow(limit: Int) = flowOf(rows.values.filter { !it.done }.take(limit))
        override suspend fun byId(id: String) = rows[id]
        override suspend fun maxPosition(listName: String) =
            rows.values.filter { it.listName == listName }.maxOfOrNull { it.position } ?: -1
        override suspend fun setDone(id: String, done: Boolean, completedAt: Long?): Int {
            val t = rows[id] ?: return 0
            rows[id] = t.copy(done = done, completedAt = completedAt); return 1
        }
        override suspend fun deleteById(id: String) { rows.remove(id) }
        override suspend fun count() = rows.size
    }

    private class FakeReminderDao : ReminderDao {
        val rows = LinkedHashMap<String, ReminderEntity>()
        override suspend fun insert(reminder: ReminderEntity) { rows[reminder.id] = reminder }
        override suspend fun update(reminder: ReminderEntity) { rows[reminder.id] = reminder }
        override suspend fun getAll() = rows.values.sortedBy { it.triggerAtMs }
        override suspend fun pending(limit: Int) = rows.values.filter { !it.fired }.sortedBy { it.triggerAtMs }.take(limit)
        override fun pendingFlow(limit: Int) =
            flowOf(rows.values.filter { !it.fired }.sortedBy { it.triggerAtMs }.take(limit))
        override suspend fun due(nowMs: Long) = rows.values.filter { !it.fired && it.triggerAtMs <= nowMs }
        override suspend fun byId(id: String) = rows[id]
        override suspend fun markFired(id: String): Int {
            val r = rows[id] ?: return 0; rows[id] = r.copy(fired = true); return 1
        }
        override suspend fun deleteById(id: String) { rows.remove(id) }
        override suspend fun count() = rows.size
    }

    private class FakePlaceDao : SavedPlaceDao {
        val rows = LinkedHashMap<String, SavedPlaceEntity>()
        override suspend fun insert(place: SavedPlaceEntity) { rows[place.id] = place }
        override suspend fun update(place: SavedPlaceEntity) { rows[place.id] = place }
        override suspend fun getAll() = rows.values.sortedByDescending { it.lastUsedMs }
        override fun allFlow() = flowOf(rows.values.sortedByDescending { it.lastUsedMs })
        override suspend fun byCategory(category: String) = rows.values.filter { it.category == category }
        override fun byCategoryFlow(category: String) = flowOf(rows.values.filter { it.category == category })
        override suspend fun byLabel(label: String) = rows.values.firstOrNull { it.label.equals(label, true) }
        override suspend fun byId(id: String) = rows[id]
        override suspend fun deleteById(id: String) { rows.remove(id) }
        override suspend fun count() = rows.size
    }

    @Test
    fun `tasks in a list get incrementing positions`() = runBlocking {
        val dao = FakeTaskDao(); val store = TaskStore(dao)
        store.add("перше", listName = "покупки")
        store.add("друге", listName = "покупки")
        val positions = dao.byList("покупки").map { it.position }.sorted()
        assertEquals(listOf(0, 1), positions)
    }

    @Test
    fun `task done stamps completion and pending drops it`() = runBlocking {
        val dao = FakeTaskDao(); val store = TaskStore(dao)
        val id = store.add("подзвонити")
        assertTrue(store.setDone(id, true, nowMs = 500))
        val t = store.get(id)!!
        assertTrue(t.done); assertEquals(500L, t.completedAt)
        assertTrue(store.pending().isEmpty())
        assertFalse(store.setDone("nope"))
    }

    @Test
    fun `saving a place with the same label updates in place`() = runBlocking {
        val dao = FakePlaceDao(); val store = SavedPlaceStore(dao)
        val first = store.save("Дім", 50.4, 30.5, category = "home")
        val second = store.save("дім", 50.41, 30.52)
        assertEquals(1, store.count())
        assertEquals(first, second)
        val place = store.byLabel("Дім")!!
        assertEquals(50.41, place.lat, 1e-9)
        assertEquals("home", place.category) // blank category keeps the old one
    }

    @Test
    fun `calendar upcoming filters past events`() = runBlocking {
        val dao = FakeCalendarDao(); val store = CalendarStore(dao)
        store.add("минуле", startMs = 100)
        store.add("майбутнє", startMs = 1000)
        val up = store.upcoming(fromMs = 500)
        assertEquals(1, up.size)
        assertEquals("майбутнє", up.single().title)
    }

    @Test
    fun `reminder due surfaces only fired-eligible rows`() = runBlocking {
        val dao = FakeReminderDao(); val store = ReminderStore(dao)
        val soon = store.add("зараз", triggerAtMs = 100)
        store.add("пізніше", triggerAtMs = 10_000)
        val due = store.due(nowMs = 500)
        assertEquals(1, due.size)
        assertEquals(soon, due.single().id)
        assertTrue(store.markFired(soon))
        assertTrue(store.due(nowMs = 500).isEmpty())
    }

    @Test
    fun `blank titles and labels are rejected`() = runBlocking {
        assertEquals("", TaskStore(FakeTaskDao()).add("   "))
        assertEquals("", CalendarStore(FakeCalendarDao()).add("  ", startMs = 1))
        assertEquals("", SavedPlaceStore(FakePlaceDao()).save("  ", 1.0, 2.0))
        assertEquals("", ReminderStore(FakeReminderDao()).add("  ", triggerAtMs = 1))
    }

    @Test
    fun `remove reports existence`() = runBlocking {
        val dao = FakeCalendarDao(); val store = CalendarStore(dao)
        val id = store.add("подія", startMs = 1)
        assertNotNull(store.get(id))
        assertTrue(store.remove(id))
        assertFalse(store.remove(id))
        assertNull(store.get(id))
    }

    // Якір один: другий «дім» вів би «додому» в попереднє життя, бо
    // transit.home бере ПЕРШИЙ рядок категорії.
    @Test
    fun `saving a second home replaces the anchor, not duplicates it`() = runBlocking {
        val dao = FakePlaceDao(); val store = SavedPlaceStore(dao)
        store.save("Стара хата", 50.40, 30.50, category = "home")
        store.save("Нова хата", 49.99, 36.23, category = "home")
        val homes = store.list("home")
        assertEquals(1, homes.size)
        assertEquals("Нова хата", homes.single().label)
    }

    @Test
    fun `recategorize to home evicts the previous home`() = runBlocking {
        val dao = FakePlaceDao(); val store = SavedPlaceStore(dao)
        store.save("Дім", 50.40, 30.50, category = "home")
        val cafeId = store.save("Кавʼярня", 50.41, 30.51, category = "custom")
        assertTrue(store.recategorize(cafeId, "дім"))
        val homes = store.list("home")
        assertEquals(1, homes.size)
        assertEquals("Кавʼярня", homes.single().label)
        assertFalse(store.recategorize("nope", "home"))
    }

    @Test
    fun `custom marks with the same category coexist`() = runBlocking {
        val dao = FakePlaceDao(); val store = SavedPlaceStore(dao)
        store.save("Схрон А", 50.1, 30.1, category = "custom")
        store.save("Схрон Б", 50.2, 30.2, category = "custom")
        assertEquals(2, store.count())
    }
}
