package local.phantom.companion.core.data.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryConsolidatorTest {

    private class FakeDao : MemoryFactDao {
        val rows = LinkedHashMap<String, MemoryFactEntity>()
        override suspend fun insert(fact: MemoryFactEntity) { rows[fact.id] = fact }
        override suspend fun update(fact: MemoryFactEntity) { rows[fact.id] = fact }
        override suspend fun getAll() = rows.values.toList()
        override suspend fun getByCategory(category: String) = rows.values.filter { it.category == category }
        override suspend fun byEntity(entityId: String) = rows.values.filter { it.entityId == entityId }
        override suspend fun recent(limit: Int) = rows.values.toList()
        override suspend fun count() = rows.size
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
        override suspend fun pruneBelow(floor: Float) = 0
        override suspend fun clear() { rows.clear() }
    }

    private class FakeEntityDao : MemoryEntityDao {
        val rows = LinkedHashMap<String, MemoryEntityEntity>()
        override suspend fun insert(entity: MemoryEntityEntity) { rows[entity.id] = entity }
        override suspend fun update(entity: MemoryEntityEntity) { rows[entity.id] = entity }
        override suspend fun getAll() = rows.values.toList()
        override suspend fun byId(id: String) = rows[id]
        override suspend fun byNameKey(nameKey: String) = rows.values.filter { it.nameKey == nameKey }
        override suspend fun byPeer(peerId: String) = rows.values.firstOrNull { it.peerId == peerId }
        override suspend fun count() = rows.size
        override suspend fun deleteById(id: String) { rows.remove(id) }
        override suspend fun clear() { rows.clear() }
    }

    private fun store() = MemoryFactStore(FakeDao())

    @Test
    fun `extracts and stores durable facts`() = runBlocking {
        val dao = FakeDao(); val store = MemoryFactStore(dao)
        val c = MemoryConsolidator(
            store,
            generate = { """Ось факти: [{"text":"їздить на Skoda Octavia","category":"vehicle"},{"text":"любить WOG на Набережному","category":"preference"}]""" },
            embed = { IntArray(8) { i -> it.hashCode() + i } },
        )
        val n = c.consolidate("Оператор: я на шкоді. PHANTOM: зрозумів.", nowMs = 100)
        assertEquals(2, n)
        assertEquals(2, dao.count())
        assertTrue(dao.getAll().any { it.category == "vehicle" })
    }

    @Test
    fun `garbage model output stores nothing`() = runBlocking {
        val dao = FakeDao(); val store = MemoryFactStore(dao)
        val c = MemoryConsolidator(store, generate = { "не знаю про що ти" }, embed = { null })
        assertEquals(0, c.consolidate("Оператор: привіт.", nowMs = 1))
        assertEquals(0, dao.count())
    }

    @Test
    fun `empty dialogue skips the model`() = runBlocking {
        var called = false
        val c = MemoryConsolidator(store(), generate = { called = true; "[]" }, embed = { null })
        assertEquals(0, c.consolidate("   ", nowMs = 1))
        assertTrue(!called)
    }

    @Test
    fun `факт про людину заводить її і стає на ній`() = runBlocking {
        val dao = FakeDao(); val people = FakeEntityDao()
        val c = MemoryConsolidator(
            MemoryFactStore(dao),
            generate = { """[{"text":"Сестра Марта живе у Львові","category":"person","about":"Марта"}]""" },
            embed = { null },
            entities = MemoryEntityStore(people),
        )
        c.consolidate("Оператор: сестра Марта дзвонила.", nowMs = 100)

        val entity = people.rows.values.single()
        assertEquals("Марта", entity.name)
        assertEquals(MemoryEntityEntity.PERSON, entity.kind)
        assertEquals(entity.id, dao.rows.values.single().entityId)
    }

    @Test
    fun `імені, якого в розмові не звучало, памʼять не заводить`() = runBlocking {
        val dao = FakeDao(); val people = FakeEntityDao()
        val c = MemoryConsolidator(
            MemoryFactStore(dao),
            generate = { """[{"text":"Сестра Марта живе у Львові","category":"person","about":"Марта"}]""" },
            embed = { null },
            entities = MemoryEntityStore(people),
        )
        c.consolidate("Оператор: я купив каву.", nowMs = 100)

        assertEquals(0, people.count())
        assertEquals(null, dao.rows.values.single().entityId)
    }

    @Test
    fun `факт без роду чіпляється до вже знайомої людини, а не заводить другу`() = runBlocking {
        val dao = FakeDao(); val people = FakeEntityDao()
        val store = MemoryFactStore(dao)
        var reply = """[{"text":"Сестра оператора — Марта","category":"person","about":"Марта"}]"""
        val c = MemoryConsolidator(store, generate = { reply }, embed = { null }, entities = MemoryEntityStore(people))
        c.consolidate("Оператор: Марта — моя сестра.", nowMs = 100)

        reply = """[{"text":"любить каву без цукру","category":"preference","about":"Марті"}]"""
        c.consolidate("Оператор: Марті купи каву без цукру.", nowMs = 200)

        val entity = people.rows.values.single()
        assertEquals(2, dao.count())
        assertTrue(dao.getAll().all { it.entityId == entity.id })
    }

    @Test
    fun `re-consolidating the same fact reinforces, not duplicates`() = runBlocking {
        val dao = FakeDao(); val store = MemoryFactStore(dao)
        val emb: suspend (String) -> IntArray? = { IntArray(8) { i -> if (i == 0) 120 else 2 } }
        val c = MemoryConsolidator(store, generate = { """[{"text":"їздить на Skoda","category":"vehicle"}]""" }, embed = emb)
        c.consolidate("розмова 1", nowMs = 100)
        c.consolidate("розмова 2", nowMs = 200)
        assertEquals(1, dao.count())
        assertEquals(2, dao.getAll().single().reinforceCount)
    }
}
