package local.phantom.companion.feature.godmode.engine

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.data.patterns.PatternDao
import local.phantom.companion.core.data.patterns.PatternEntity
import local.phantom.companion.core.data.patterns.PatternRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PatternDetectorTest {

    private class FakeDao : PatternDao {
        val rows = mutableListOf<PatternEntity>()

        override suspend fun insert(entity: PatternEntity): Long {
            rows.removeAll { it.triggerKind == entity.triggerKind && it.triggerValue == entity.triggerValue }
            rows.add(entity)
            return rows.size.toLong()
        }
        override suspend fun findByTrigger(kind: String, value: String): PatternEntity? =
            rows.firstOrNull { it.triggerKind == kind && it.triggerValue == value }
        override suspend fun bumpOccurrence(id: String, nowMs: Long) {
            rows.replaceAll { row ->
                if (row.id == id) {
                    val newCount = row.occurrenceCount + 1
                    row.copy(
                        occurrenceCount = newCount,
                        lastObservedAt = nowMs,
                        confidenceScore = (newCount / 5.0).coerceAtMost(1.0),
                    )
                } else row
            }
        }
        override suspend fun suppress(id: String) {
            rows.replaceAll { if (it.id == id) it.copy(suppressed = true) else it }
        }
        override suspend fun pickActionable(threshold: Int, sinceMs: Long): List<PatternEntity> =
            rows.filter {
                it.occurrenceCount >= threshold && !it.suppressed && it.lastObservedAt >= sinceMs
            }
        override suspend fun cleanupStale(olderThanMs: Long): Int {
            val before = rows.size
            rows.removeAll { it.lastObservedAt < olderThanMs }
            return before - rows.size
        }
        override suspend fun totalCount(): Int = rows.size
        override suspend fun recent(limit: Int): List<PatternEntity> =
            rows.sortedByDescending { it.lastObservedAt }.take(limit)
    }

    private fun newDetector(now: () -> Long = { 0L }): Pair<FakeDao, PatternDetector> {
        val dao = FakeDao()
        return dao to PatternDetector(
            repository = PatternRepository(dao),
            nowMs = now,
        )
    }

    @Test
    fun `surface fires on third occurrence within window`() = runTest {
        var clock = 1_000L
        val (_, detector) = newDetector(now = { clock })
        val event = PatternEvent(
            kind = PatternEntity.Kind.TIME,
            keyValue = "tue@09",
            suggestedAction = "нагадати про каву",
        )
        // First two observations: silent.
        assertNull(detector.observe(event))
        clock += 60_000
        assertNull(detector.observe(event))
        clock += 60_000
        // Third hit crosses the threshold.
        val surfaced = detector.observe(event)
        assertNotNull(surfaced)
        assertEquals("нагадати про каву", surfaced!!.suggestedAction)
        assertEquals(3, surfaced.occurrenceCount)
    }

    @Test
    fun `further observations after surface keep returning the row`() = runTest {
        val (_, detector) = newDetector()
        val event = PatternEvent(PatternEntity.Kind.APP, "com.spotify.music", "нагадати про Spotify")
        repeat(5) { detector.observe(event) }
        val out = detector.observe(event)
        assertNotNull(out)
        assertEquals(6, out!!.occurrenceCount)
    }

    @Test
    fun `suppression silences the surface`() = runTest {
        val (_, detector) = newDetector()
        val event = PatternEvent(PatternEntity.Kind.APP, "com.foo", "сповіщення")
        repeat(3) { detector.observe(event) }
        val firstSurfaced = detector.observe(event)
        assertNotNull(firstSurfaced)

        detector.suppress(firstSurfaced!!.id)

        // Even though the count keeps climbing, the row stays muted.
        repeat(5) {
            assertNull(
                "suppressed pattern must never re-surface",
                detector.observe(event),
            )
        }
    }

    @Test
    fun `independent buckets do not cross-contaminate`() = runTest {
        val (_, detector) = newDetector()
        repeat(2) {
            detector.observe(
                PatternEvent(PatternEntity.Kind.TIME, "tue@09", "кава"),
            )
        }
        repeat(2) {
            detector.observe(
                PatternEvent(PatternEntity.Kind.TIME, "wed@10", "обід"),
            )
        }
        // Neither bucket has 3 yet → both stay silent.
        assertNull(detector.observe(PatternEvent(PatternEntity.Kind.TIME, "thu@11", "перерва")))
    }

    @Test
    fun `stale rows roll off after rolling window`() = runTest {
        var clock = 0L
        val (dao, detector) = newDetector(now = { clock })
        // Seed a row at t = 0.
        detector.observe(PatternEvent(PatternEntity.Kind.TIME, "tue@09", "кава"))
        assertEquals(1, dao.rows.size)
        // Jump 8 days forward; cleanupStale on next observe drops the old row.
        clock += 8L * 24L * 60L * 60L * 1000L
        detector.observe(PatternEvent(PatternEntity.Kind.TIME, "thu@10", "обід"))
        // The old «tue@09» row was dropped by the stale cleanup pass; only the
        // freshly-observed «thu@10» remains.
        assertEquals(listOf("thu@10"), dao.rows.map { it.triggerValue })
    }

    @Test
    fun `timeKey produces stable dow-hour key`() {
        val tuesday9am = run {
            val cal = java.util.Calendar.getInstance()
            cal.set(2026, java.util.Calendar.MAY, 12, 9, 30)  // a Tuesday
            cal.timeInMillis
        }
        assertEquals("tue@09", PatternDetector.timeKey(tuesday9am))
    }

    @Test
    fun `confidence score scales with count up to 1`() = runTest {
        val (_, detector) = newDetector()
        val event = PatternEvent(PatternEntity.Kind.APP, "com.foo", "відкрити")
        repeat(7) { detector.observe(event) }
        val out = detector.observe(event)
        assertTrue("confidence reaches 1 at count >= 5: ${out?.confidenceScore}", (out?.confidenceScore ?: 0.0) >= 1.0)
    }
}
