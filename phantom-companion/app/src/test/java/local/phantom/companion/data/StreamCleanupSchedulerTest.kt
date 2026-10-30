package local.phantom.companion.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.data.stream.StreamItemDao
import local.phantom.companion.core.data.stream.StreamItemEntity
import local.phantom.companion.core.data.stream.StreamRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W2-5 — pin the cleanup scheduler:
 *  - retentionDays = 0 short-circuits to no-op
 *  - within 24h of last run, runOnceIfDue is a no-op
 *  - past 24h since last run, cleanup fires with the correct cutoff
 *  - force=true bypasses the throttle (Settings → Діагностика button)
 *  - lastRunMs is updated even when nothing was deleted
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StreamCleanupSchedulerTest {

    private val ONE_DAY = StreamCleanupScheduler.ONE_DAY_MS
    private val HEAVY = "{\"title\":\"бюджет липня\",\"points\":[" + "1,".repeat(1500) + "1]}"

    /**
     * «Назавжди» (0) used to short-circuit the whole pass, which also froze the
     * fixed 21-day plumbing decay — so the copy naming that one exception lied
     * the moment anyone chose forever. The two clocks are now separate: content
     * is untouchable, the trace still ages out.
     */
    @Test
    fun `forever keeps every content row but still decays the 21-day plumbing`() = runTest {
        val capture = CapturingDao()
        val now = 1_000_000_000_000L
        capture.insertOrUpdate(widgetAt("ancient-canvas", now - 400L * ONE_DAY, "canvas", HEAVY))
        capture.insertOrUpdate(rowAt("ancient-text", now - 400L * ONE_DAY))
        capture.insertOrUpdate(widgetAt("old-trace", now - 30L * ONE_DAY, "tool-trace", HEAVY))
        var lastRun = 0L
        val scheduler = StreamCleanupScheduler(
            repository = StreamRepository(capture),
            retentionDaysProvider = { 0 },
            lastRunMsGetter = { lastRun },
            lastRunMsSetter = { lastRun = it },
            nowMs = { now },
        )

        val removed = scheduler.runOnceIfDue()

        assertEquals("forever must never call the content sweep", 0, capture.cleanupCalls.size)
        assertTrue("a 400-day-old canvas stays under forever", capture.has("ancient-canvas"))
        assertTrue("a 400-day-old message stays under forever", capture.has("ancient-text"))
        assertTrue("the 30-day trace is gone on the fixed 21-day clock", !capture.has("old-trace"))
        assertEquals("only the plumbing trace was shed", 1, removed)
        assertEquals("the plumbing pass advances lastRun even under forever", now, lastRun)
    }

    /**
     * The load-bearing invariant of the whole screen: the number the preview
     * promises for a shortening is exactly the number the sweep removes, and a
     * pin is never in it.
     */
    @Test
    fun `the number promised for a shortening is the number deleted, pins excluded`() = runTest {
        val capture = CapturingDao()
        val now = 1_000_000_000_000L
        val target = 7
        val cutoff = now - target.toLong() * ONE_DAY
        // 12 unpinned rows past the 7-day line — these must go.
        repeat(12) { i -> capture.insertOrUpdate(rowAt("old$i", now - (8L + i) * ONE_DAY)) }
        // 4 fresh rows inside the line — these stay.
        repeat(4) { i -> capture.insertOrUpdate(rowAt("fresh$i", now - i.toLong() * ONE_DAY)) }
        // 2 pinned rows, both older than the line — protected, never counted or cut.
        capture.insertOrUpdate(pinnedRowAt("pin0", now - 40L * ONE_DAY))
        capture.insertOrUpdate(pinnedRowAt("pin1", now - 99L * ONE_DAY))

        // Exactly the number RetentionBody puts on the pill and in the preview.
        val promised = capture.countOlderThan(cutoff)

        var days = 90
        val scheduler = StreamCleanupScheduler(
            repository = StreamRepository(capture),
            retentionDaysProvider = { days },
            lastRunMsGetter = { 0L },
            lastRunMsSetter = { },
            nowMs = { now },
        )
        days = target                                      // the operator picks 7 days
        val deleted = scheduler.runOnceIfDue(force = true) // the confirm pill fires the sweep

        assertEquals("the preview counts only unpinned content past the line", 12, promised)
        assertEquals("promised number == swept number", promised, deleted)
        assertTrue("the pin survives", capture.has("pin0"))
        assertTrue("the pin survives", capture.has("pin1"))
        assertEquals("4 fresh + 2 pinned remain", 6, capture.count())
    }

    @Test
    fun `within 24h of last run is a no-op`() = runTest {
        val capture = CapturingDao()
        val repo = StreamRepository(capture)
        val now = 1_000_000_000L
        var lastRun = now - 12L * 60 * 60 * 1000  // 12h ago
        val scheduler = StreamCleanupScheduler(
            repository = repo,
            retentionDaysProvider = { 90 },
            lastRunMsGetter = { lastRun },
            lastRunMsSetter = { lastRun = it },
            nowMs = { now },
        )
        scheduler.runOnceIfDue()
        assertEquals("under 24h — no cleanup call", 0, capture.cleanupCalls.size)
    }

    @Test
    fun `past 24h triggers cleanup with correct retention cutoff`() = runTest {
        val capture = CapturingDao()
        // Pre-populate three rows: way old, just-old, fresh.
        val now = 1_000_000_000_000L
        capture.insertOrUpdate(rowAt("old", now - 100L * StreamCleanupScheduler.ONE_DAY_MS))
        capture.insertOrUpdate(rowAt("border", now - 91L * StreamCleanupScheduler.ONE_DAY_MS))
        capture.insertOrUpdate(rowAt("fresh", now - 5L * StreamCleanupScheduler.ONE_DAY_MS))

        val repo = StreamRepository(capture)
        var lastRun = 0L  // never ran
        val scheduler = StreamCleanupScheduler(
            repository = repo,
            retentionDaysProvider = { 90 },
            lastRunMsGetter = { lastRun },
            lastRunMsSetter = { lastRun = it },
            nowMs = { now },
        )
        val deleted = scheduler.runOnceIfDue()
        assertEquals("two rows older than 90d should be cleaned", 2, deleted)
        assertEquals(now, lastRun)
        // Fresh row survives.
        assertEquals(1, capture.count())
    }

    @Test
    fun `force=true bypasses the 24h throttle`() = runTest {
        val capture = CapturingDao()
        val repo = StreamRepository(capture)
        val now = 1_000_000_000L
        var lastRun = now - 1_000L  // basically just ran
        val scheduler = StreamCleanupScheduler(
            repository = repo,
            retentionDaysProvider = { 90 },
            lastRunMsGetter = { lastRun },
            lastRunMsSetter = { lastRun = it },
            nowMs = { now },
        )
        scheduler.runOnceIfDue() // throttled, no-op
        assertEquals(0, capture.cleanupCalls.size)
        scheduler.runOnceIfDue(force = true)
        assertEquals("force ignored the throttle", 1, capture.cleanupCalls.size)
    }

    @Test
    fun `cleanup updates lastRunMs even when zero rows deleted`() = runTest {
        val capture = CapturingDao() // empty table — 0 deletions
        val repo = StreamRepository(capture)
        val now = 1_000_000_000L
        var lastRun = 0L
        val scheduler = StreamCleanupScheduler(
            repository = repo,
            retentionDaysProvider = { 30 },
            lastRunMsGetter = { lastRun },
            lastRunMsSetter = { lastRun = it },
            nowMs = { now },
        )
        scheduler.runOnceIfDue()
        assertEquals("lastRun must be marked even on empty cleanup", now, lastRun)
    }

    /**
     * The defect this file exists for: «keep 90 days» used to empty every
     * widget payload over 2 KB on day 45. The operator opened a two-month-old
     * canvas and found a stub.
     */
    @Test
    fun `a 90-day retention leaves a day-45 canvas whole`() = runTest {
        val capture = CapturingDao()
        val now = 1_000_000_000_000L
        capture.insertOrUpdate(widgetAt("canvas", now - 45L * ONE_DAY, "canvas", HEAVY))

        val scheduler = schedulerOver(capture, retentionDays = 90, now = now)
        scheduler.runOnceIfDue()

        assertEquals("the row must still be there", 1, capture.count())
        assertEquals("the payload must be untouched", HEAVY, capture.payloadOf("canvas"))
    }

    @Test
    fun `every kind of made thing survives to the day the operator chose`() = runTest {
        val capture = CapturingDao()
        val now = 1_000_000_000_000L
        listOf("canvas", "chart", "artifact", "code-runner", "app-action").forEach { type ->
            capture.insertOrUpdate(widgetAt(type, now - 60L * ONE_DAY, type, HEAVY))
        }

        schedulerOver(capture, retentionDays = 90, now = now).runOnceIfDue()

        assertEquals(5, capture.count())
        listOf("canvas", "chart", "artifact", "code-runner", "app-action").forEach { type ->
            assertEquals("$type lost its payload", HEAVY, capture.payloadOf(type))
        }
    }

    /** The shorter clock is allowed to touch plumbing, and nothing else. */
    @Test
    fun `at day 45 the tool trace is gone and the canvas is not`() = runTest {
        val capture = CapturingDao()
        val now = 1_000_000_000_000L
        capture.insertOrUpdate(widgetAt("canvas", now - 45L * ONE_DAY, "canvas", HEAVY))
        capture.insertOrUpdate(widgetAt("trace", now - 45L * ONE_DAY, "tool-trace", HEAVY))

        schedulerOver(capture, retentionDays = 90, now = now).runOnceIfDue()

        assertEquals("only the trace should have gone", 1, capture.count())
        assertEquals(HEAVY, capture.payloadOf("canvas"))
    }

    @Test
    fun `retention is the clock that deletes content — nothing sooner`() = runTest {
        val capture = CapturingDao()
        val now = 1_000_000_000_000L
        capture.insertOrUpdate(widgetAt("day89", now - 89L * ONE_DAY, "canvas", HEAVY))
        capture.insertOrUpdate(widgetAt("day91", now - 91L * ONE_DAY, "canvas", HEAVY))

        schedulerOver(capture, retentionDays = 90, now = now).runOnceIfDue()

        assertEquals(1, capture.count())
        assertEquals("day 89 is inside the operator's number", HEAVY, capture.payloadOf("day89"))
    }

    @Test
    fun `the plumbing clock is fixed, not derived from retention`() {
        val scheduler = schedulerOver(CapturingDao(), retentionDays = 90, now = 0L)
        assertEquals(21, scheduler.decayPolicy(90).plumbingDays)
        assertEquals(21, scheduler.decayPolicy(365).plumbingDays)
    }

    @Test
    fun `the plumbing clock never outlives the retention the operator chose`() {
        val scheduler = schedulerOver(CapturingDao(), retentionDays = 7, now = 0L)
        listOf(1, 3, 7, 14, 20).forEach { retention ->
            assertTrue(
                "plumbing must not outlive a $retention-day retention",
                scheduler.decayPolicy(retention).plumbingDays <= retention,
            )
        }
    }

    /* helpers ------------------------------------------------------------------ */

    private fun schedulerOver(dao: CapturingDao, retentionDays: Int, now: Long) =
        StreamCleanupScheduler(
            repository = StreamRepository(dao),
            retentionDaysProvider = { retentionDays },
            lastRunMsGetter = { 0L },
            lastRunMsSetter = { },
            nowMs = { now },
        )

    private fun widgetAt(id: String, createdAt: Long, type: String, payload: String) =
        StreamItemEntity(
            id = id, threadId = "now", seqNo = 0L, createdAt = createdAt,
            kind = StreamItemEntity.Kind.WIDGET, role = null,
            textContent = null, widgetType = type, widgetPayload = payload, streaming = false,
        )

    private fun rowAt(id: String, createdAt: Long) = StreamItemEntity(
        id = id, threadId = "now", seqNo = 0L, createdAt = createdAt,
        kind = StreamItemEntity.Kind.TEXT, role = StreamItemEntity.Role.USER,
        textContent = "x", widgetType = null, widgetPayload = null, streaming = false,
    )

    private fun pinnedRowAt(id: String, createdAt: Long) = StreamItemEntity(
        id = id, threadId = "now", seqNo = 0L, createdAt = createdAt,
        kind = StreamItemEntity.Kind.TEXT, role = StreamItemEntity.Role.ASSISTANT,
        textContent = "pinned $id", widgetType = null, widgetPayload = null,
        streaming = false, pinned = true,
    )

    private class CapturingDao : StreamItemDao {
        private val rows = mutableMapOf<String, StreamItemEntity>()
        val cleanupCalls = mutableListOf<Long>()

        fun payloadOf(id: String): String? = rows[id]?.widgetPayload
        fun has(id: String): Boolean = rows.containsKey(id)

        override suspend fun insertOrUpdate(items: List<StreamItemEntity>) {
            items.forEach { rows[it.id] = it }
        }
        override suspend fun insertOrUpdate(item: StreamItemEntity) { rows[item.id] = item }
        override suspend fun delete(id: String) { rows.remove(id) }
        override suspend fun deleteByThreadId(threadId: String) {
            rows.entries.removeIf { it.value.threadId == threadId }
        }
        override suspend fun deleteByIdPrefix(prefix: String): Int {
            val before = rows.size
            rows.entries.removeIf { it.key.startsWith(prefix) }
            return before - rows.size
        }
        override suspend fun byThread(threadId: String, limit: Int): List<StreamItemEntity> =
            rows.values.filter { it.threadId == threadId }.take(limit)
        override fun byThreadFlow(threadId: String, limit: Int): Flow<List<StreamItemEntity>> =
            MutableStateFlow(emptyList())
        override suspend fun lastN(limit: Int): List<StreamItemEntity> = rows.values.take(limit)
        override suspend fun lastNInThread(threadId: String, limit: Int): List<StreamItemEntity> =
            rows.values.filter { it.threadId == threadId }.take(limit)
        override suspend fun searchText(query: String, limit: Int): List<StreamItemEntity> =
            rows.values.filter { it.textContent?.contains(query, true) == true }
        override suspend fun cleanupBefore(cutoffMs: Long): Int {
            cleanupCalls += cutoffMs
            val before = rows.size
            // Mirrors the real SQL's `AND pinned = 0` — a fake that deletes pins
            // would let a pin-exemption regression pass green here.
            rows.entries.removeIf { it.value.createdAt < cutoffMs && !it.value.pinned }
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
            rows.values.filter { it.threadId == threadId }.maxOfOrNull { it.seqNo } ?: -1L
        override suspend fun updatePinned(id: String, pinned: Boolean) {
            rows[id]?.let { rows[id] = it.copy(pinned = pinned) }
        }
        override suspend fun pinnedTexts(limit: Int): List<StreamItemEntity> =
            rows.values.filter { it.pinned }.take(limit)
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
                .take(limit)
    }
}
