package local.phantom.companion.core.data.stream

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.data.PhantomDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A pin is the only manual override the operator has over decay — the tap that
 * says «this one matters». Retention used to outrank it: the row they marked
 * was deleted on schedule like everything else, and the pin was a promise the
 * database did not keep.
 *
 * Robolectric, so the assertions run against Room's generated SQL rather than a
 * fake DAO: remove `AND pinned = 0` from [StreamItemDao.cleanupBefore] and all
 * three tests below fail.
 */
@RunWith(RobolectricTestRunner::class)
class StreamItemDaoRetentionTest {

    private lateinit var db: PhantomDatabase
    private lateinit var dao: StreamItemDao

    @Before
    fun open() {
        db = PhantomDatabase.openInMemory(ApplicationProvider.getApplicationContext())
        dao = db.streamItemDao()
    }

    @After
    fun close() {
        db.close()
    }

    @Test
    fun `a pinned row outlives the retention sweep, an unpinned one of the same age does not`() =
        runTest {
            dao.insertOrUpdate(
                item(
                    id = "pinned-old",
                    seqNo = 1,
                    createdAt = LONG_AGO,
                    pinned = true,
                    text = "Школа №5, 400 м на північ.",
                ),
            )
            dao.insertOrUpdate(item(id = "plain-old", seqNo = 2, createdAt = LONG_AGO))
            dao.insertOrUpdate(item(id = "plain-fresh", seqNo = 3, createdAt = NOW))

            val deleted = dao.cleanupBefore(CUTOFF)

            assertEquals("only the unpinned old row may go", 1, deleted)
            val left = dao.byThread(THREAD, limit = 100)
            assertEquals(listOf("pinned-old", "plain-fresh"), left.map { it.id })
            val kept = left.first { it.id == "pinned-old" }
            assertTrue("the pin itself must survive the sweep", kept.pinned)
            assertEquals(
                "the pinned row must read back whole, not as an empty husk",
                "Школа №5, 400 м на північ.",
                kept.textContent,
            )
        }

    @Test
    fun `pinning a row that is already past the cutoff rescues it from the next sweep`() = runTest {
        dao.insertOrUpdate(item(id = "rescued", seqNo = 1, createdAt = LONG_AGO, text = "координати"))

        dao.updatePinned("rescued", pinned = true)
        val deleted = dao.cleanupBefore(CUTOFF)

        assertEquals(0, deleted)
        assertEquals("координати", dao.byThread(THREAD, limit = 100).single().textContent)
    }

    /** Nothing here should turn on the sweep counting rows it did not touch. */
    @Test
    fun `a sweep over nothing but pinned rows deletes nothing and says so`() = runTest {
        repeat(4) { i ->
            dao.insertOrUpdate(
                item(id = "p$i", seqNo = i.toLong(), createdAt = LONG_AGO, pinned = true),
            )
        }

        assertEquals(0, dao.cleanupBefore(CUTOFF))
        assertEquals(4, dao.count())
    }

    /**
     * The retention screen promises a delete count before the sweep runs. That
     * promise is only honest if [countOlderThan] and [cleanupBefore] agree to the
     * row — same predicate, same clock, both blind to pins.
     */
    @Test
    fun `countOlderThan equals what the sweep deletes, and both spare the pin`() = runTest {
        dao.insertOrUpdate(item(id = "pinned-old", seqNo = 1, createdAt = LONG_AGO, pinned = true))
        dao.insertOrUpdate(item(id = "plain-old-a", seqNo = 2, createdAt = LONG_AGO))
        dao.insertOrUpdate(item(id = "plain-old-b", seqNo = 3, createdAt = LONG_AGO))
        dao.insertOrUpdate(item(id = "plain-fresh", seqNo = 4, createdAt = NOW))

        val promised = dao.countOlderThan(CUTOFF)
        assertEquals("two unpinned old rows; the pin is not in the promise", 2, promised)

        val deleted = dao.cleanupBefore(CUTOFF)
        assertEquals("the number promised is the number the sweep removes", promised, deleted)
        assertTrue(
            "the pin outlived the sweep it was never counted for",
            dao.byThread(THREAD, limit = 100).any { it.id == "pinned-old" },
        )
    }

    @Test
    fun `minCreatedAt and pinnedCount read the table, not a guess`() = runTest {
        assertEquals("empty table reads null, never 0", null, dao.minCreatedAt())
        assertEquals(0, dao.pinnedCount())

        dao.insertOrUpdate(item(id = "fresh-pin", seqNo = 1, createdAt = NOW, pinned = true))
        dao.insertOrUpdate(item(id = "old-plain", seqNo = 2, createdAt = LONG_AGO))
        dao.insertOrUpdate(item(id = "old-pin", seqNo = 3, createdAt = LONG_AGO, pinned = true))

        assertEquals("the oldest created_at wins", LONG_AGO, dao.minCreatedAt())
        assertEquals("both pins are counted, plain is not", 2, dao.pinnedCount())
    }

    /**
     * Прибирання рядків зі сповіщень при старті. Пін тут не рятує: це не
     * зношування за віком, а вилучення того, що взагалі не мало лежати.
     */
    @Test
    fun `deleteByIdPrefix removes the notification family and nothing else`() = runTest {
        dao.insertOrUpdate(
            item(id = "notif-1700-org.telegram.messenger", seqNo = 1, createdAt = NOW,
                text = "Telegram: Оля — буду о шостій"),
        )
        dao.insertOrUpdate(
            item(id = "notif-1701-com.viber.voip", seqNo = 2, createdAt = NOW, pinned = true),
        )
        dao.insertOrUpdate(item(id = "err-42", seqNo = 3, createdAt = NOW))
        dao.insertOrUpdate(item(id = "u1", seqNo = 4, createdAt = NOW))

        val removed = dao.deleteByIdPrefix("notif-")

        assertEquals(2, removed)
        assertEquals(listOf("err-42", "u1"), dao.byThread(THREAD, limit = 100).map { it.id })
        assertTrue(dao.searchText("буду о шостій", limit = 10).isEmpty())
    }

    private fun item(
        id: String,
        seqNo: Long,
        createdAt: Long,
        pinned: Boolean = false,
        text: String = "рядок $id",
    ) = StreamItemEntity(
        id = id,
        threadId = THREAD,
        seqNo = seqNo,
        createdAt = createdAt,
        kind = StreamItemEntity.Kind.TEXT,
        role = StreamItemEntity.Role.ASSISTANT,
        textContent = text,
        pinned = pinned,
    )

    private companion object {
        const val THREAD = "thread-now"
        const val NOW = 1_752_000_000_000L
        const val CUTOFF = NOW - 90L * 86_400_000L
        const val LONG_AGO = CUTOFF - 86_400_000L
    }
}
