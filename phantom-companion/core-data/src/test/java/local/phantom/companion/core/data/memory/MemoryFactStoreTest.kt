package local.phantom.companion.core.data.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryFactStoreTest {

    /** In-memory DAO — the store's logic is what we exercise, not Room. */
    private class FakeDao : MemoryFactDao {
        val rows = LinkedHashMap<String, MemoryFactEntity>()
        override suspend fun insert(fact: MemoryFactEntity) { rows[fact.id] = fact }
        override suspend fun update(fact: MemoryFactEntity) { rows[fact.id] = fact }
        override suspend fun getAll() = rows.values.toList()
        override suspend fun getByCategory(category: String) = rows.values.filter { it.category == category }
        override suspend fun byEntity(entityId: String) = rows.values.filter { it.entityId == entityId }
        override suspend fun recent(limit: Int) = rows.values.sortedByDescending { it.lastConfirmedMs }.take(limit)
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
        override suspend fun pruneBelow(floor: Float): Int {
            val gone = rows.values.filter { it.confidence < floor }.map { it.id }
            gone.forEach { rows.remove(it) }; return gone.size
        }
        override suspend fun clear() { rows.clear() }
    }

    private fun vec(vararg v: Int) = IntArray(8) { v.getOrElse(it) { 0 } }

    @Test
    fun `near-duplicate reinforces instead of duplicating`() = runBlocking {
        val dao = FakeDao(); val store = MemoryFactStore(dao)
        val e = vec(120, 40, 0, 0, 0, 0, 0, 0)
        store.remember("їздить на Skoda Octavia", "vehicle", "conversation", e, nowMs = 1000)
        store.remember("їздить на шкоді октавія", "vehicle", "conversation", vec(118, 44, 3, 0, 0, 0, 0, 0), nowMs = 2000)
        assertEquals("must collapse to one fact", 1, dao.count())
        val f = dao.getAll().single()
        assertEquals(2, f.reinforceCount)
        assertTrue("confidence rises on reinforcement", f.confidence > 0.6f)
        assertEquals(2000, f.lastConfirmedMs)
    }

    @Test
    fun `distinct facts coexist`() = runBlocking {
        val dao = FakeDao(); val store = MemoryFactStore(dao)
        store.remember("любить каву на Набережному", "preference", "conversation", vec(0, 0, 120, 30), nowMs = 1)
        store.remember("їздить на Skoda", "vehicle", "conversation", vec(120, 30, 0, 0), nowMs = 1)
        assertEquals(2, dao.count())
    }

    @Test
    fun `recall ranks the semantically closest fact first`() = runBlocking {
        val dao = FakeDao(); val store = MemoryFactStore(dao)
        store.remember("любить WOG", "preference", "conversation", vec(0, 0, 125, 20), nowMs = 1)
        store.remember("їздить на Skoda", "vehicle", "conversation", vec(125, 20, 0, 0), nowMs = 1)
        val top = store.recall(vec(0, 0, 127, 10), nowMs = 2, k = 2)
        assertEquals("WOG fact must lead for a fuel-ish query", "любить WOG", top.first().text)
    }

    @Test
    fun `prune drops a stale low-confidence fact`() = runBlocking {
        val dao = FakeDao(); val store = MemoryFactStore(dao)
        val now = 400L * 86_400_000L // day 400
        store.remember("випадкова згадка", "fact", "episodic", vec(1, 2, 3), nowMs = 1) // ancient, count 1
        store.remember("важливе про авто", "vehicle", "conversation", vec(120, 0, 0), nowMs = now)
        val removed = store.decayAndPrune(nowMs = now, cap = 1500, floor = 0.12f, halfLifeDays = 45.0)
        assertTrue("the ancient fact decays out", removed >= 1)
        assertTrue(dao.getAll().any { it.text == "важливе про авто" })
    }

    @Test
    fun `cap evicts the lowest-retained surplus`() = runBlocking {
        val dao = FakeDao(); val store = MemoryFactStore(dao)
        // Orthogonal vectors (120 on a distinct axis each) so none dedup into another.
        repeat(5) { i -> store.remember("факт $i", "fact", "conversation", IntArray(8) { if (it == i) 120 else 0 }, nowMs = 1000L + i) }
        val removed = store.decayAndPrune(nowMs = 2000, cap = 3, floor = 0f, halfLifeDays = 45.0)
        assertEquals(2, removed)
        assertEquals(3, dao.count())
    }

    @Test
    fun `a fact the person asked to remember does not decay away`() = runBlocking {
        val dao = FakeDao(); val store = MemoryFactStore(dao)
        val now = 400L * 86_400_000L
        store.remember("Тебе звати Кирило", "self.name", "operator", null, nowMs = 1)
        store.remember("випадкова згадка", "fact", "episodic", null, nowMs = 1)
        store.decayAndPrune(nowMs = now, cap = 1500, floor = 0.12f, halfLifeDays = 45.0)
        assertEquals(listOf("Тебе звати Кирило"), dao.getAll().map { it.text })
    }
}
