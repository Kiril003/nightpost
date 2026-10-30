package local.phantom.companion.core.data.spatial

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R4.1 — the spatial store's serialization + TTL + scoping logic, exercised
 * over a pure in-memory fake DAO (no Room / Robolectric needed).
 */
class SpatialLayerRepositoryTest {

    private class FakeDao : SpatialLayerDao {
        val rows = mutableListOf<SpatialLayerEntity>()
        private val flow = MutableStateFlow<List<SpatialLayerEntity>>(emptyList())
        private fun sync() { flow.value = rows.sortedByDescending { it.createdAt } }
        override fun observeAll(): Flow<List<SpatialLayerEntity>> = flow
        override suspend fun getAll() = rows.sortedByDescending { it.createdAt }
        override suspend fun getForThread(threadId: String) =
            rows.filter { it.ownerThreadId == threadId }.sortedByDescending { it.createdAt }
        override suspend fun upsert(layer: SpatialLayerEntity) {
            rows.removeAll { it.id == layer.id }; rows.add(layer); sync()
        }
        override suspend fun setVisible(id: String, visible: Boolean) {
            val i = rows.indexOfFirst { it.id == id }
            if (i >= 0) { rows[i] = rows[i].copy(visible = visible); sync() }
        }
        override suspend fun deleteById(id: String): Int {
            val n = rows.count { it.id == id }; rows.removeAll { it.id == id }; sync(); return n
        }
        override suspend fun deleteAll(): Int { val n = rows.size; rows.clear(); sync(); return n }
        override suspend fun deleteForThread(threadId: String): Int {
            val n = rows.count { it.ownerThreadId == threadId }
            rows.removeAll { it.ownerThreadId == threadId }; sync(); return n
        }
        override suspend fun deleteForThreadOrigin(threadId: String, origin: String): Int {
            val n = rows.count { it.ownerThreadId == threadId && it.origin == origin }
            rows.removeAll { it.ownerThreadId == threadId && it.origin == origin }; sync(); return n
        }
        override suspend fun deleteByOrigin(origin: String): Int {
            val n = rows.count { it.origin == origin }
            rows.removeAll { it.origin == origin }; sync(); return n
        }
        override suspend fun deleteExpired(nowMs: Long): Int {
            val n = rows.count { it.expiresAt != null && it.expiresAt!! <= nowMs }
            rows.removeAll { it.expiresAt != null && it.expiresAt!! <= nowMs }; sync(); return n
        }
    }

    private fun spec(
        title: String = "шар",
        origin: LayerOrigin = LayerOrigin.PHANTOM,
        thread: String? = "t1",
        ttlMs: Long? = null,
    ) = SpatialLayerSpec(
        title = title,
        origin = origin,
        kind = "poi",
        ownerThreadId = thread,
        markers = listOf(
            SpatialMarker(50.45, 30.52, label = "A", detail = "деталь"),
            SpatialMarker(50.46, 30.53, label = "B"),
        ),
        lines = listOf(SpatialLine(listOf(GeoPoint(50.45, 30.52), GeoPoint(50.46, 30.53)))),
        regions = listOf(SpatialRegion(50.45, 30.52, 300.0)),
        color = "#FF8A65",
        ttlMs = ttlMs,
    )

    @Test
    fun `push then read round-trips geometry`() = runTest {
        val dao = FakeDao()
        val repo = SpatialLayerRepository(dao) { 1_000L }
        val id = repo.push(spec())
        val layers = repo.list("t1")
        assertEquals(1, layers.size)
        val l = layers.first()
        assertEquals(id, l.id)
        assertEquals(2, l.spec.markers.size)
        assertEquals("деталь", l.spec.markers.first().detail)
        assertEquals(1, l.spec.lines.size)
        assertEquals(2, l.spec.lines.first().points.size)
        assertEquals(1, l.spec.regions.size)
        assertEquals(300.0, l.spec.regions.first().radiusM, 1e-9)
        assertEquals("#FF8A65", l.spec.color)
    }

    @Test
    fun `empty spec is rejected (Law 1)`() = runTest {
        val repo = SpatialLayerRepository(FakeDao())
        try {
            repo.push(SpatialLayerSpec("порожньо", LayerOrigin.PHANTOM, "x"))
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) { /* expected */ }
    }

    @Test
    fun `ttl expiry filters the layer out after its window`() = runTest {
        var now = 1_000L
        val dao = FakeDao()
        val repo = SpatialLayerRepository(dao) { now }
        repo.push(spec(ttlMs = 60_000L))
        assertEquals(1, repo.list("t1").size)
        now = 1_000L + 60_001L
        assertTrue(repo.list("t1").isEmpty())
        // the live flow also filters it
        assertTrue(repo.layers.first().isEmpty())
    }

    @Test
    fun `clearThread scopes by thread and origin`() = runTest {
        val dao = FakeDao()
        val repo = SpatialLayerRepository(dao)
        repo.push(spec(title = "a", origin = LayerOrigin.PHANTOM, thread = "t1"))
        repo.push(spec(title = "b", origin = LayerOrigin.WORLD, thread = "t1"))
        repo.push(spec(title = "c", origin = LayerOrigin.PHANTOM, thread = "t2"))
        val removed = repo.clearThread("t1", LayerOrigin.PHANTOM)
        assertEquals(1, removed)
        val remaining = repo.list(null).map { it.spec.title }.toSet()
        assertEquals(setOf("b", "c"), remaining)
    }

    @Test
    fun `setVisible toggles and clear removes`() = runTest {
        val dao = FakeDao()
        val repo = SpatialLayerRepository(dao)
        val id = repo.push(spec())
        repo.setVisible(id, false)
        assertFalse(repo.list("t1").first().visible)
        assertTrue(repo.clear(id))
        assertTrue(repo.list("t1").isEmpty())
    }

    @Test
    fun `requestFocus replays to a subscribed collector`() = runTest {
        val repo = SpatialLayerRepository(FakeDao())
        val target = FocusTarget(50.0, 30.0, 14.0)
        val collected = mutableListOf<FocusTarget>()
        val job = backgroundScope.launch(kotlinx.coroutines.Dispatchers.Unconfined) {
            repo.focusRequests.collect { collected.add(it) }
        }
        repo.requestFocus(target)
        assertEquals(target, collected.single())
        job.cancel()
    }
}
