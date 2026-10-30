package local.phantom.companion.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.data.notifications.NotificationDigestDao
import local.phantom.companion.core.data.notifications.NotificationDigestEntity
import local.phantom.companion.core.data.notifications.NotificationDigestRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3 W4-5 — daily-digest scheduler unit tests using an
 * in-memory fake DAO. No Room, no Robolectric — the scheduler is
 * pure-Kotlin once the repository is mocked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationDigestSchedulerTest {

    /** In-memory fake — exercises the same async surface as the real DAO. */
    private class FakeDao : NotificationDigestDao {
        val rows = mutableListOf<NotificationDigestEntity>()

        override suspend fun insert(entity: NotificationDigestEntity): Long {
            rows += entity
            return rows.size.toLong()
        }
        override suspend fun pickPendingSince(cutoffMs: Long): List<NotificationDigestEntity> =
            rows.filter { !it.delivered && it.deliveredAt >= cutoffMs }
                .sortedBy { it.deliveredAt }
        override suspend fun markDelivered(ids: List<String>) {
            rows.replaceAll { row -> if (row.id in ids) row.copy(delivered = true) else row }
        }
        override suspend fun purgeOlderThan(olderThanMs: Long): Int {
            val before = rows.size
            rows.removeAll { it.deliveredAt < olderThanMs }
            return before - rows.size
        }
        override suspend fun pendingCount(): Int = rows.count { !it.delivered }
    }

    private fun row(id: String, pkg: String, ts: Long, delivered: Boolean = false) =
        NotificationDigestEntity(
            id = id,
            packageName = pkg,
            channelId = "ch",
            title = "T",
            redactedText = "x",
            deliveredAt = ts,
            delivered = delivered,
        )

    private fun atLocalHour(hour: Int): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, hour)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    @Test
    fun `runOnceIfDue at 9am emits and marks delivered`() = runTest {
        val dao = FakeDao()
        val repo = NotificationDigestRepository(dao)
        val emitted = mutableListOf<String>()
        val now = atLocalHour(9) + 5 * 60_000L
        var lastEmitMs = 0L

        dao.rows += row("a", "org.telegram.messenger", now - 2 * 3_600_000L)
        dao.rows += row("b", "org.telegram.messenger", now - 1 * 3_600_000L)
        dao.rows += row("c", "com.google.android.gm", now - 3 * 3_600_000L)

        val scheduler = NotificationDigestScheduler(
            repository = repo,
            emitter = { emitted += it },
            nowMs = { now },
            lastEmitMsGetter = { lastEmitMs },
            lastEmitMsSetter = { lastEmitMs = it },
        )

        val count = scheduler.runOnceIfDue()
        assertEquals(3, count)
        assertEquals(1, emitted.size)
        assertTrue("digest mentions Telegram: ${emitted[0]}", emitted[0].contains("Telegram"))
        assertTrue("digest mentions email: ${emitted[0]}", emitted[0].contains("пошти"))
        assertTrue(
            "all rows marked delivered after emit",
            dao.rows.all { it.delivered },
        )
        assertTrue("last-emit ms recorded", lastEmitMs > 0L)
    }

    @Test
    fun `runOnceIfDue before 9am does not emit`() = runTest {
        val dao = FakeDao()
        dao.rows += row("a", "org.telegram.messenger", 0L)
        val emitted = mutableListOf<String>()
        val now = atLocalHour(7)

        val scheduler = NotificationDigestScheduler(
            repository = NotificationDigestRepository(dao),
            emitter = { emitted += it },
            nowMs = { now },
            lastEmitMsGetter = { 0L },
            lastEmitMsSetter = {},
        )
        val count = scheduler.runOnceIfDue()
        assertEquals("nothing emitted before 9am", 0, count)
        assertTrue(emitted.isEmpty())
    }

    @Test
    fun `runOnceIfDue twice on the same day emits only once`() = runTest {
        val dao = FakeDao()
        dao.rows += row("a", "org.telegram.messenger", atLocalHour(8))
        val emitted = mutableListOf<String>()
        val now = atLocalHour(9) + 5 * 60_000L
        var lastEmitMs = 0L

        val scheduler = NotificationDigestScheduler(
            repository = NotificationDigestRepository(dao),
            emitter = { emitted += it },
            nowMs = { now },
            lastEmitMsGetter = { lastEmitMs },
            lastEmitMsSetter = { lastEmitMs = it },
        )
        scheduler.runOnceIfDue()
        scheduler.runOnceIfDue()
        assertEquals("single emit per day", 1, emitted.size)
    }

    @Test
    fun `force emit bypasses the once-per-day guard`() = runTest {
        val dao = FakeDao()
        dao.rows += row("a", "org.telegram.messenger", atLocalHour(8))
        val emitted = mutableListOf<String>()
        val now = atLocalHour(11)
        var lastEmitMs = now

        val scheduler = NotificationDigestScheduler(
            repository = NotificationDigestRepository(dao),
            emitter = { emitted += it },
            nowMs = { now },
            lastEmitMsGetter = { lastEmitMs },
            lastEmitMsSetter = { lastEmitMs = it },
        )
        scheduler.runOnceIfDue(force = true)
        assertEquals("force re-emits even after lastEmit on the same day", 1, emitted.size)
    }

    @Test
    fun `empty pending still updates lastEmit so we don't burn picks`() = runTest {
        val dao = FakeDao()
        val emitted = mutableListOf<String>()
        val now = atLocalHour(9) + 5 * 60_000L
        var lastEmitMs = 0L

        val scheduler = NotificationDigestScheduler(
            repository = NotificationDigestRepository(dao),
            emitter = { emitted += it },
            nowMs = { now },
            lastEmitMsGetter = { lastEmitMs },
            lastEmitMsSetter = { lastEmitMs = it },
        )
        scheduler.runOnceIfDue()
        assertEquals("no rows → no emit", 0, emitted.size)
        assertEquals("but lastEmit recorded so next 30-min tick is a no-op", now, lastEmitMs)
    }

    @Test
    fun `no row outlives the retention window, delivered or not`() = runTest {
        val dao = FakeDao()
        // 07:00 — дайджест ще не на часі. Саме тут рядки й лишалися назавжди:
        // розряджений телефон або застосунок, що не працював о девʼятій,
        // виносили їх за 24-годинне вікно вибірки, а прибирання чекало на
        // доставку, якої вже не могло статись.
        // Скільки живе рядок у РЕАЛЬНОМУ SQL — доводить NotificationDigestDaoRetentionTest
        // на Robolectric; [FakeDao] тут показав би лише те, що ми в нього поклали.
        val now = atLocalHour(7)
        val past = now - NotificationDigestScheduler.PRUNE_AFTER_MS - 3_600_000L
        dao.rows += row("stale-undelivered", "org.telegram.messenger", past)
        dao.rows += row("stale-delivered", "com.viber.voip", past, delivered = true)
        dao.rows += row("fresh", "com.google.android.gm", now - 3_600_000L)

        val scheduler = NotificationDigestScheduler(
            repository = NotificationDigestRepository(dao),
            emitter = {},
            nowMs = { now },
            lastEmitMsGetter = { 0L },
            lastEmitMsSetter = {},
        )
        scheduler.runOnceIfDue()

        assertEquals(
            "rows past the window survived a pass: ${dao.rows.map { it.id }}",
            listOf("fresh"),
            dao.rows.map { it.id },
        )
    }

    @Test
    fun `digest formatter handles single sender`() {
        val text = DigestFormatter.format(
            listOf(row("x", "org.telegram.messenger", 0L)),
        )
        assertTrue("Telegram surfaces: $text", text.contains("Telegram"))
        assertTrue("count 1: $text", text.contains("1 від"))
    }

    @Test
    fun `digest formatter on empty input returns soft message`() {
        val text = DigestFormatter.format(emptyList())
        assertEquals("ранок · нічого нового за добу", text)
    }
}
