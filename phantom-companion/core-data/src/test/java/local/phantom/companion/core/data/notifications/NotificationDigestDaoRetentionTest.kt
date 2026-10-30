package local.phantom.companion.core.data.notifications

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.data.PhantomDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Чужі листи мають строк придатності — і він не залежить від того, чи їх колись
 * показали.
 *
 * Черга дайджесту вибирає лише останні 24 години, а прибирання питало про
 * `delivered`. Рядок, який пережив своє вікно невиданим — а це звичайний
 * розряджений телефон або застосунок, що не працював о девʼятій, — не міг уже
 * ані потрапити в дайджест, ані бути вилученим. Він лишався на диску назавжди,
 * разом із іменем відправника й тілом листа.
 *
 * Robolectric, а не фейковий DAO: фейк — це наше власне дзеркало, і воно
 * покаже саме те, що ми в нього поклали. Поверніть `delivered = 1 AND` у
 * [NotificationDigestDao.purgeOlderThan] — і перший тест падає.
 */
@RunWith(RobolectricTestRunner::class)
class NotificationDigestDaoRetentionTest {

    private lateinit var db: PhantomDatabase
    private lateinit var dao: NotificationDigestDao

    @Before
    fun open() {
        db = PhantomDatabase.openInMemory(ApplicationProvider.getApplicationContext())
        dao = db.notificationDigestDao()
    }

    @After
    fun close() {
        db.close()
    }

    @Test
    fun `an undelivered row past the window is deleted like any other`() = runTest {
        dao.insert(row("never-shown", "org.telegram.messenger", LONG_AGO))
        dao.insert(row("shown", "com.viber.voip", LONG_AGO, delivered = true))
        dao.insert(row("fresh", "com.google.android.gm", NOW))

        assertEquals("both old rows go, delivered or not", 2, dao.purgeOlderThan(CUTOFF))
        assertEquals(1, dao.pendingCount())
        assertEquals(
            "the fresh row is the only thing left",
            listOf("fresh"),
            dao.pickPendingSince(0L).map { it.id },
        )
    }

    /** Межа існує, щоб її пережило те, що всередині. Інакше це не строк, а стирання. */
    @Test
    fun `a row inside the window survives the purge`() = runTest {
        dao.insert(row("yesterday", "org.telegram.messenger", CUTOFF + 1L))

        assertEquals(0, dao.purgeOlderThan(CUTOFF))
        assertEquals("yesterday", dao.pickPendingSince(0L).single().id)
    }

    /**
     * Те, що робить із чергою реальний прохід планувальника: вибрати за добу,
     * позначити виданим, прибрати старе. Після нього в таблиці не сміє лишитись
     * нічого, старшого за межу.
     */
    @Test
    fun `after a full pass nothing older than the window is left in the table`() = runTest {
        dao.insert(row("stale", "com.spotify.music", LONG_AGO))
        dao.insert(row("today", "org.telegram.messenger", NOW - HOUR))

        val picked = dao.pickPendingSince(NOW - DAY)
        dao.markDelivered(picked.map { it.id })
        dao.purgeOlderThan(CUTOFF)

        assertEquals("only today's row was reachable", listOf("today"), picked.map { it.id })
        assertEquals(0, dao.pendingCount())
        assertEquals(0, dao.purgeOlderThan(CUTOFF))
    }

    private fun row(id: String, pkg: String, atMs: Long, delivered: Boolean = false) =
        NotificationDigestEntity(
            id = id,
            packageName = pkg,
            channelId = "ch",
            title = "Оля",
            redactedText = "текст",
            deliveredAt = atMs,
            delivered = delivered,
        )

    private companion object {
        const val HOUR = 3_600_000L
        const val DAY = 24L * HOUR
        const val NOW = 1_752_000_000_000L
        const val CUTOFF = NOW - 7L * DAY
        const val LONG_AGO = CUTOFF - DAY
    }
}
