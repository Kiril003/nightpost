package local.phantom.companion.core.data.peers

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Закони віку і строку на фальшивому годиннику. Жоден із них не перевіряється
 * на екрані: «точка зі скриньки народилась живою» виглядає рівно як правда, і
 * саме тому тримається тут.
 */
class PeerPointsRepositoryTest {

    private var clock = 1_700_000_000_000L
    private val repo = PeerPointsRepository { clock }

    private fun point(
        nodeId: String = "n1",
        atMs: Long = clock,
        seq: Int? = null,
        verified: Boolean = true,
        expiresAtMs: Long? = null,
        mode: ShareMode = ShareMode.LIVE,
        lat: Double = 50.4501,
        lon: Double = 30.5234,
    ) = PeerPoint(
        nodeId = nodeId,
        threadId = "t1",
        name = "Олег",
        lat = lat,
        lon = lon,
        atMs = atMs,
        seq = seq,
        verified = verified,
        share = PeerShare(mode, expiresAtMs),
    )

    @Test
    fun `a batch is one emission, whatever its size`() = runTest {
        val seen = mutableListOf<Int>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            repo.points.collect { seen += it.size }
        }

        repo.replaceAll((1..32).map { point(nodeId = "n$it") })
        job.cancel()

        assertEquals("32 точки мусять доїхати однією емісією", listOf(0, 32), seen)
    }

    @Test
    fun `a point out of the mailbox is born stale, and never recovers`() = runTest {
        repo.replaceAll(listOf(point(atMs = clock - PEER_LIVE_FRESH_WINDOW_MS - 1_000L)))
        assertTrue(repo.points.value.single().stale)

        // Той самий тік ще раз, уже «свіжим» часом виміру, не воскрешає його:
        // вирок їде з точкою, а не перераховується читачем.
        repo.upsertBatch(listOf(repo.points.value.single().copy(atMs = clock)))
        assertTrue(repo.points.value.single().stale)
    }

    @Test
    fun `a point measured within the window is live`() = runTest {
        repo.replaceAll(listOf(point(atMs = clock - PEER_LIVE_FRESH_WINDOW_MS + 1_000L)))
        assertFalse(repo.points.value.single().stale)
        assertTrue(repo.points.value.single().freshAt(clock))
    }

    @Test
    fun `a one-shot place is judged by the narrower window, a live tick by its own`() = runTest {
        // Вік разового місця з розмови й вік тіка живого сеансу — різні
        // питання: живий сеанс домовився про свій крок мовчання, разове місце
        // не обіцяло оновитись узагалі.
        val age = PEER_FRESH_WINDOW_MS + 1_000L
        repo.replaceAll(
            listOf(
                point(nodeId = "live", atMs = clock - age),
                point(nodeId = "once", atMs = clock - age, mode = ShareMode.ONE_SHOT),
            ),
        )
        val held = repo.points.value.associateBy { it.nodeId }
        assertFalse("тік живого сеансу народився застарілим", held.getValue("live").stale)
        assertTrue("разове місце вдало живе", held.getValue("once").stale)
    }

    @Test
    fun `a hole in seq breaks the trail even when the measurement is fresh`() = runTest {
        repo.upsertBatch(listOf(point(seq = 7)))
        assertFalse(repo.points.value.single().stale)

        repo.upsertBatch(listOf(point(seq = 9)))
        assertTrue("тіки загубились — це розрив, не рідший ритм", repo.points.value.single().stale)
    }

    @Test
    fun `consecutive ticks keep the trail whole, and a new session may restart the count`() =
        runTest {
            repo.upsertBatch(listOf(point(seq = 7)))
            repo.upsertBatch(listOf(point(seq = 8)))
            assertFalse(repo.points.value.single().stale)

            // Новий шерінг починає лічильник спочатку — це не діра.
            repo.upsertBatch(listOf(point(seq = 1)))
            assertFalse(repo.points.value.single().stale)
        }

    @Test
    fun `an expired share leaves the list on write and on the sweep`() = runTest {
        repo.replaceAll(listOf(point(expiresAtMs = clock + 60_000L)))
        assertEquals(1, repo.points.value.size)

        clock += 61_000L
        repo.sweep()
        assertTrue("строк минув — точка мусить піти сама", repo.points.value.isEmpty())

        // І другий пояс: те саме прострочене не має права заїхати записом.
        repo.upsertBatch(listOf(point(expiresAtMs = clock - 1L)))
        assertTrue(repo.points.value.isEmpty())
    }

    @Test
    fun `a one-shot point has no deadline and outlives every sweep`() = runTest {
        repo.replaceAll(listOf(point(mode = ShareMode.ONE_SHOT)))
        clock += 10 * 60 * 60 * 1000L
        repo.sweep()
        assertEquals(1, repo.points.value.size)
    }

    @Test
    fun `an out-of-order mailbox point never drags a person backwards`() = runTest {
        repo.upsertBatch(listOf(point(atMs = clock, lat = 50.45, lon = 30.52)))
        repo.upsertBatch(listOf(point(atMs = clock - 30_000L, lat = 49.0, lon = 24.0)))

        val held = repo.points.value.single()
        assertEquals(50.45, held.lat, 1e-9)
        assertEquals(clock, held.atMs)
    }

    @Test
    fun `upsert keeps everybody else standing, replaceAll does not`() = runTest {
        repo.replaceAll(listOf(point(nodeId = "a"), point(nodeId = "b")))
        repo.upsertBatch(listOf(point(nodeId = "a", atMs = clock + 1)))
        assertEquals(setOf("a", "b"), repo.points.value.mapTo(HashSet()) { it.nodeId })

        repo.replaceAll(listOf(point(nodeId = "a", atMs = clock + 2)))
        assertEquals(listOf("a"), repo.points.value.map { it.nodeId })
    }

    @Test
    fun `one person is one point, whatever the batch says twice`() = runTest {
        repo.replaceAll(listOf(point(atMs = clock - 5), point(atMs = clock)))
        assertEquals(1, repo.points.value.size)
        assertEquals(clock, repo.points.value.single().atMs)
    }

    @Test
    fun `the flow starts empty — nothing is invented before the messenger writes`() {
        assertTrue(PeerPointsRepository().points.value.isEmpty())
    }
}
