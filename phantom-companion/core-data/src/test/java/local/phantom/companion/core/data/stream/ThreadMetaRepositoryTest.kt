package local.phantom.companion.core.data.stream

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ThreadMetaRepositoryTest {

    @Test
    fun `ensureDefault inserts Зараз on first call and is idempotent across reruns`() = runTest {
        var clock = 1_000L
        val dao = FakeThreadMetaDao()
        val repo = ThreadMetaRepository(dao, nowMs = { clock })

        repo.ensureDefault("Зараз")
        clock = 9_999L
        repo.ensureDefault("Зараз")  // second call must not duplicate

        val all = repo.listAll()
        assertEquals(1, all.size)
        assertEquals("now", all[0].id)
        assertEquals("Зараз", all[0].label)
        // last_active_at preserved from the first insert (insertIfAbsent
        // semantics — second call is a no-op, not a refresh).
        assertEquals(1_000L, all[0].lastActiveAt)
    }

    @Test
    fun `touch refreshes last_active_at and reorders observe`() = runTest {
        var clock = 1_000L
        val dao = FakeThreadMetaDao()
        val repo = ThreadMetaRepository(dao, nowMs = { clock })

        repo.upsert(id = "now", label = "Зараз")
        clock = 2_000L
        repo.upsert(id = "olya", label = "Оля")
        clock = 3_000L
        repo.upsert(id = "family", label = "Сімʼя")
        // Most-recent-first by last_active_at.
        assertEquals(listOf("family", "olya", "now"), repo.listAll().map { it.id })

        clock = 4_000L
        repo.touch("now") // bump «Зараз» to the top
        assertEquals(listOf("now", "family", "olya"), repo.listAll().map { it.id })
    }

    @Test
    fun `delete removes a thread`() = runTest {
        val dao = FakeThreadMetaDao()
        val repo = ThreadMetaRepository(dao, nowMs = { 1_000L })
        repo.upsert(id = "olya", label = "Оля")
        assertEquals(1, repo.count())
        repo.delete("olya")
        assertEquals(0, repo.count())
    }

    @Test
    fun `byId returns the entity or null`() = runTest {
        val dao = FakeThreadMetaDao()
        val repo = ThreadMetaRepository(dao, nowMs = { 1_000L })
        repo.upsert(id = "now", label = "Зараз")
        assertNotNull(repo.byId("now"))
        assertEquals(null, repo.byId("missing"))
    }

    /* helpers ---------------------------------------------------------------- */

    private class FakeThreadMetaDao : ThreadMetaDao {
        private val rows = mutableMapOf<String, ThreadMetaEntity>()
        private val flow = MutableStateFlow<List<ThreadMetaEntity>>(emptyList())

        override suspend fun upsert(meta: ThreadMetaEntity) {
            rows[meta.id] = meta
            bump()
        }

        override suspend fun insertIfAbsent(meta: ThreadMetaEntity): Long {
            if (rows.containsKey(meta.id)) return -1L
            rows[meta.id] = meta
            bump()
            return 1L
        }

        override suspend fun delete(id: String) {
            rows.remove(id)
            bump()
        }

        override fun observeAll(): Flow<List<ThreadMetaEntity>> = flow

        override suspend fun listAll(): List<ThreadMetaEntity> =
            rows.values.sortedByDescending { it.lastActiveAt }

        override suspend fun byId(id: String): ThreadMetaEntity? = rows[id]

        override suspend fun touch(id: String, ts: Long) {
            val existing = rows[id] ?: return
            rows[id] = existing.copy(lastActiveAt = ts)
            bump()
        }

        override suspend fun count(): Int = rows.size

        private fun bump() {
            flow.value = rows.values.sortedByDescending { it.lastActiveAt }
        }
    }
}
