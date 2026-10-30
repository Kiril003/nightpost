package local.phantom.companion.core.data.stream

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * W2-1 — pin the StreamRepository's per-thread sequencing + bulk
 * upsert + cleanup contracts.
 *
 * The DAO @Query SQL itself is verified at compile time by Room
 * (KSP cross-checks the columns), so a Fake DAO that mirrors the
 * filter/sort semantics is enough to pin the repository wrapper.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StreamRepositoryTest {

    private lateinit var dao: FakeStreamItemDao
    private lateinit var repo: StreamRepository

    @Before
    fun setup() {
        dao = FakeStreamItemDao()
        repo = StreamRepository(dao)
    }

    @Test
    fun `upsert assigns next seq_no per thread when caller passes -1`() = runTest {
        val a = repo.upsert(text("u1", thread = "now", seqNo = -1L, text = "a"))
        val b = repo.upsert(text("u2", thread = "now", seqNo = -1L, text = "b"))
        val c = repo.upsert(text("u3", thread = "lhasa", seqNo = -1L, text = "c"))
        // Per-thread sequence: now → 0,1; lhasa → 0
        assertEquals(0L, a.seqNo)
        assertEquals(1L, b.seqNo)
        assertEquals(0L, c.seqNo)
    }

    @Test
    fun `upsert honours caller-provided seq_no (cold-launch import)`() = runTest {
        val out = repo.upsert(text("u1", thread = "now", seqNo = 42L, text = "x"))
        assertEquals(42L, out.seqNo)
    }

    @Test
    fun `upsertAll bulk-inserts all items`() = runTest {
        repo.upsertAll(listOf(
            text("u1", "now", 0, "a"),
            text("u2", "now", 1, "b"),
            text("u3", "now", 2, "c"),
        ))
        assertEquals(3, repo.count())
    }

    @Test
    fun `byThread returns oldest to newest with limit clamp`() = runTest {
        repeat(150) { i ->
            repo.upsert(text("u$i", "now", -1L, "msg-$i"))
        }
        val result = repo.byThread("now", limit = 100)
        assertEquals(100, result.size)
        // Oldest → newest within the limit window.
        assertEquals("msg-0", result.first().textContent)
        assertEquals("msg-99", result.last().textContent)
    }

    @Test
    fun `lastN returns newest first across all threads`() = runTest {
        repo.upsert(text("u1", "now", 0L, "first"))
        repo.upsert(text("u2", "lhasa", 0L, "middle"))
        repo.upsert(text("u3", "now", 1L, "last"))
        val result = repo.lastN(limit = 5)
        assertEquals("newest seq_no first", "last", result.first().textContent)
    }

    @Test
    fun `searchText is case-insensitive LIKE on text_content`() = runTest {
        repo.upsert(text("u1", "now", 0L, "Кирил, привіт"))
        repo.upsert(text("u2", "now", 1L, "де АТБ?"))
        repo.upsert(text("u3", "now", 2L, "запусти hello world"))
        val hits = repo.searchText("hello")
        assertEquals(1, hits.size)
        assertEquals("u3", hits[0].id)

        // Case-insensitive — LOWER on both sides of LIKE.
        val mixed = repo.searchText("ПРИВІТ")
        assertEquals(1, mixed.size)
        assertEquals("u1", mixed[0].id)
    }

    @Test
    fun `cleanupBefore deletes only rows older than cutoff`() = runTest {
        repo.upsert(text("old", "now", 0L, "old", createdAt = 100L))
        repo.upsert(text("middle", "now", 1L, "middle", createdAt = 500L))
        repo.upsert(text("fresh", "now", 2L, "fresh", createdAt = 1_000L))
        val deleted = repo.cleanupBefore(600L)
        assertEquals(2, deleted)
        assertEquals(1, repo.count())
        val remaining = repo.byThread("now", limit = 10)
        assertEquals("fresh", remaining.single().id)
    }

    @Test
    fun `deleteByThreadId purges every row in that thread (GHOST cleanup)`() = runTest {
        repo.upsert(text("a", "ghost", 0L, "vault"))
        repo.upsert(text("b", "ghost", 1L, "secret"))
        repo.upsert(text("c", "now", 0L, "open"))
        repo.deleteByThreadId("ghost")
        assertEquals(1, repo.count())
        assertEquals("c", repo.byThread("now").single().id)
    }

    @Test
    fun `widget kind round-trips through upsert and byThread`() = runTest {
        repo.upsert(
            StreamItemEntity(
                id = "w1",
                threadId = "now",
                seqNo = 0L,
                createdAt = 1L,
                kind = StreamItemEntity.Kind.WIDGET,
                role = null,
                textContent = null,
                widgetType = "timer",
                widgetPayload = """{"duration_seconds":300}""",
                streaming = false,
            ),
        )
        val r = repo.byThread("now").single()
        assertEquals("timer", r.widgetType)
        assertEquals("""{"duration_seconds":300}""", r.widgetPayload)
        assertNull(r.textContent)
    }

    /**
     * A flush batch routinely mixes a re-written OLD row (a tool-trace widget
     * flipping running → done keeps its original low seq_no) with a brand-new
     * one. The old row's reused seq_no must not become the baseline for the
     * new row's assignment, or the new message is sequenced into the middle of
     * old history and renders days back in the scroll.
     */
    @Test
    fun `upsertAll does not let a reused low seq_no pull the next new item back`() = runTest {
        repeat(6) { i -> repo.upsert(text("old$i", "now", i.toLong(), "msg-$i")) }
        // Cold-launch style: hydrate the id → seq_no cache from disk.
        repo.lastNInThread("now", 10)

        // Batch: re-flush of old row seq_no=1, then a brand-new message.
        repo.upsertAll(
            listOf(
                text("old1", "now", -1L, "msg-1 edited"),
                text("fresh", "now", -1L, "newest"),
            ),
        )

        val rows = repo.byThread("now", limit = 20)
        assertEquals("re-flushed row keeps its original slot", 1L, rows.single { it.id == "old1" }.seqNo)
        assertEquals("new row goes after the on-disk max", 6L, rows.single { it.id == "fresh" }.seqNo)
        assertEquals("and therefore renders last", "fresh", rows.last().id)
    }

    @Test
    fun `lastNInThread is scoped to that thread only`() = runTest {
        repo.upsert(text("u1", "now", 0L, "a"))
        repo.upsert(text("u2", "lhasa", 0L, "b"))
        repo.upsert(text("u3", "now", 1L, "c"))
        val now = repo.lastNInThread("now", 10)
        assertEquals(2, now.size)
        assertTrue(now.all { it.threadId == "now" })
    }

    /* helpers -------------------------------------------------------------- */

    private fun text(
        id: String,
        thread: String,
        seqNo: Long,
        text: String,
        createdAt: Long = 0L,
    ) = StreamItemEntity(
        id = id,
        threadId = thread,
        seqNo = seqNo,
        createdAt = createdAt,
        kind = StreamItemEntity.Kind.TEXT,
        role = StreamItemEntity.Role.USER,
        textContent = text,
        widgetType = null,
        widgetPayload = null,
        streaming = false,
    )
}

/**
 * Test-only DAO impl — mirrors the @Query semantics in pure
 * Kotlin so StreamRepositoryTest can exercise the wrapper without
 * spinning up Room's KSP-generated machinery.
 */
private class FakeStreamItemDao : StreamItemDao {
    private val rows = mutableMapOf<String, StreamItemEntity>()
    private val byThreadFlow = mutableMapOf<String, MutableStateFlow<List<StreamItemEntity>>>()

    override suspend fun insertOrUpdate(items: List<StreamItemEntity>) {
        items.forEach { rows[it.id] = it }
        bumpFlows(items.map { it.threadId }.toSet())
    }

    override suspend fun insertOrUpdate(item: StreamItemEntity) {
        rows[item.id] = item
        bumpFlows(setOf(item.threadId))
    }

    override suspend fun delete(id: String) {
        val removed = rows.remove(id)
        if (removed != null) bumpFlows(setOf(removed.threadId))
    }

    override suspend fun deleteByThreadId(threadId: String) {
        rows.entries.removeIf { it.value.threadId == threadId }
        bumpFlows(setOf(threadId))
    }

    override suspend fun deleteByIdPrefix(prefix: String): Int {
        val hit = rows.values.filter { it.id.startsWith(prefix) }
        rows.entries.removeIf { it.key.startsWith(prefix) }
        bumpFlows(hit.map { it.threadId }.toSet())
        return hit.size
    }

    override suspend fun byThread(threadId: String, limit: Int): List<StreamItemEntity> =
        rows.values
            .filter { it.threadId == threadId }
            .sortedBy { it.seqNo }
            .take(limit)

    override fun byThreadFlow(threadId: String, limit: Int): Flow<List<StreamItemEntity>> =
        byThreadFlow.getOrPut(threadId) { MutableStateFlow(emptyList()) }
            .map { it.take(limit) }

    override suspend fun lastN(limit: Int): List<StreamItemEntity> =
        rows.values.sortedByDescending { it.seqNo }.take(limit)

    override suspend fun lastNInThread(threadId: String, limit: Int): List<StreamItemEntity> =
        rows.values
            .filter { it.threadId == threadId }
            .sortedByDescending { it.seqNo }
            .take(limit)

    override suspend fun searchText(query: String, limit: Int): List<StreamItemEntity> {
        val needle = query.lowercase()
        return rows.values
            .filter { it.textContent?.lowercase()?.contains(needle) == true }
            .sortedByDescending { it.seqNo }
            .take(limit)
    }

    override suspend fun cleanupBefore(cutoffMs: Long): Int {
        val before = rows.size
        val touchedThreads = rows.values.filter { it.createdAt < cutoffMs }.map { it.threadId }.toSet()
        rows.entries.removeIf { it.value.createdAt < cutoffMs }
        bumpFlows(touchedThreads)
        return before - rows.size
    }

    override suspend fun decayCandidates(cutoffMs: Long, limit: Int): List<StreamItemEntity> =
        rows.values.filter { it.createdAt < cutoffMs && !it.pinned }
            .sortedBy { it.createdAt }
            .take(limit)

    override suspend fun shrinkPayload(id: String, digest: String) {
        rows[id]?.let { rows[id] = it.copy(widgetPayload = digest) }
    }

    override suspend fun count(): Int = rows.size

    override suspend fun countOlderThan(cutoffMs: Long): Int =
        rows.values.count { it.createdAt < cutoffMs && !it.pinned }

    override suspend fun minCreatedAt(): Long? = rows.values.minOfOrNull { it.createdAt }

    override suspend fun pinnedCount(): Int = rows.values.count { it.pinned }

    override suspend fun maxSeqNoForThread(threadId: String): Long =
        rows.values
            .filter { it.threadId == threadId }
            .maxOfOrNull { it.seqNo } ?: -1L

    override suspend fun updatePinned(id: String, pinned: Boolean) {
        rows[id]?.let { entity ->
            rows[id] = entity.copy(pinned = pinned)
            bumpFlows(setOf(entity.threadId))
        }
    }

    override suspend fun pinnedTexts(limit: Int): List<StreamItemEntity> =
        rows.values
            .filter { it.pinned }
            .sortedByDescending { it.seqNo }
            .take(limit)

    override suspend fun distinctTagBlobs(): List<String> =
        rows.values.mapNotNull { it.tags }.distinct()

    override suspend fun updateEmbedding(id: String, embedding: FloatArray) {
        rows[id]?.let { rows[id] = it.copy(embedding = embedding) }
    }

    override suspend fun updateEmbeddingBlob(id: String, blob: ByteArray) {
        val vec = local.phantom.companion.core.data.lifelog.LifeLogConverters()
            .toFloatArray(blob) ?: return
        rows[id]?.let { rows[id] = it.copy(embedding = vec) }
    }

    override suspend fun embeddedTexts(limit: Int): List<StreamItemEntity> =
        rows.values
            .filter { it.kind == StreamItemEntity.Kind.TEXT && it.embedding != null && !it.streaming }
            .sortedByDescending { it.createdAt }
            .take(limit)

    private fun bumpFlows(threadIds: Set<String>) {
        threadIds.forEach { tid ->
            byThreadFlow[tid]?.value = rows.values
                .filter { it.threadId == tid }
                .sortedBy { it.seqNo }
        }
    }
}
