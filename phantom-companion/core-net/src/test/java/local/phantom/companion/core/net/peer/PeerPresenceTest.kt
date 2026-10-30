package local.phantom.companion.core.net.peer

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Поруч» мусить згасати само.
 *
 * Це не про красу списку: фільтр «хто в мережі» і очікування від групової
 * розсилки стоять на цьому числі. Годинник, що не цокає, робить із них не
 * порожній екран, а впевнену неправду — тож він пінується часом, а не оком.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PeerPresenceTest {

    private fun peer(seen: Long) = PeerIdentity(
        id = "a1b2c3d4",
        name = "Марта",
        kind = PeerIdentity.Kind.PERSON,
        pubKey = "KEY-1",
        lastSeenMs = seen,
    )

    @Test
    fun `the clock fires before the first wait, not after it`() = runTest {
        val seen = mutableListOf<Long>()
        val job = launch { PeerPresence.tick(periodMs = 5_000L, now = { 1_000L }) { seen += it } }
        runCurrent()
        assertEquals(
            "екран, відкритий після годинної паузи, брехав би ще пʼять секунд",
            listOf(1_000L),
            seen,
        )
        job.cancelAndJoin()
    }

    @Test
    fun `it keeps firing, one tick per period`() = runTest {
        val seen = mutableListOf<Long>()
        var clock = 0L
        val job = launch { PeerPresence.tick(periodMs = 5_000L, now = { clock }) { seen += it } }
        runCurrent()
        repeat(4) {
            clock += 5_000L
            advanceTimeBy(5_000L)
            runCurrent()
        }
        assertEquals(listOf(0L, 5_000L, 10_000L, 15_000L, 20_000L), seen)
        job.cancelAndJoin()
    }

    @Test
    fun `a peer heard from once really stops being nearby`() = runTest {
        // Те, заради чого годинник існує: той самий незмінний рядок, жодної
        // нової події в мережі — «поруч» зникає лише тому, що минув час.
        val heardAt = 1_000L
        val marta = peer(seen = heardAt)
        var clock = heardAt
        val live = mutableListOf<Boolean>()
        val job = launch {
            PeerPresence.tick(periodMs = PeerPresence.TICK_MS, now = { clock }) {
                live += marta.isLive(it)
            }
        }
        runCurrent()
        while (clock < heardAt + PeerRoster.LIVE_WINDOW_MS) {
            clock += PeerPresence.TICK_MS
            advanceTimeBy(PeerPresence.TICK_MS)
            runCurrent()
        }
        assertTrue("перший погляд мав бачити «поруч»", live.first())
        assertFalse("«поруч» пережило власне вікно", live.last())
        job.cancelAndJoin()
    }

    @Test
    fun `the tick is far shorter than the window it has to expire`() {
        // Інакше фраза відстає від факту більше, ніж на один погляд.
        assertTrue(PeerPresence.TICK_MS * 4 < PeerRoster.LIVE_WINDOW_MS)
    }
}
