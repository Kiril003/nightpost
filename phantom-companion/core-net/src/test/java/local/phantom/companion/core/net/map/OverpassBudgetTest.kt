package local.phantom.companion.core.net.map

import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.net.geosrc.GeoClock
import local.phantom.companion.core.net.geosrc.SourceUnavailable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverpassBudgetTest {

    private class FakeClock(var now: Long = 0L) : GeoClock {
        override fun nowMs(): Long = now
    }

    private fun budget(clock: GeoClock) = OverpassBudget(
        familyIntervalMs = 2_000L,
        familyBurst = 2,
        globalIntervalMs = 1_000L,
        globalBurst = 3,
        clock = clock,
    )

    @Test
    fun `mirrors of one operator share a family`() {
        val family = OverpassMirrors.familyOf("https://overpass-api.de/api/interpreter")
        assertEquals("overpass-api.de", family)
        assertEquals(family, OverpassMirrors.familyOf("https://lz4.overpass-api.de/api/interpreter"))
        assertEquals(family, OverpassMirrors.familyOf("https://z.overpass-api.de/api/interpreter"))
    }

    @Test
    fun `independent operators get independent budgets`() {
        assertEquals("private.coffee", OverpassMirrors.familyOf("https://overpass.private.coffee/api/interpreter"))
        assertEquals("kumi.systems", OverpassMirrors.familyOf("https://overpass.kumi.systems/api/interpreter"))
    }

    @Test
    fun `the mirror registry carries no VK or Mail_ru operated host`() {
        val banned = listOf("mail.ru", "yandex", "vk.com", "vk.ru")
        OverpassMirrors.ENDPOINTS.forEach { url ->
            val host = OverpassMirrors.hostOf(url)
            banned.forEach { assertTrue("дзеркало $host заборонене", !host.contains(it)) }
        }
    }

    @Test
    fun `burst passes, then the family interval bites`() = runTest {
        val clock = FakeClock()
        val budget = budget(clock)
        val url = OverpassMirrors.ENDPOINTS.first()

        assertNull(budget.acquire(url, maxWaitMs = 0L))
        assertNull(budget.acquire(url, maxWaitMs = 0L))

        val third = budget.acquire(url, maxWaitMs = 0L)
        assertNotNull("третій запит поспіль мусив упертись у бюджет", third)
        assertTrue(third is SourceUnavailable.RateLimited)
    }

    @Test
    fun `refusal is typed, never an empty result`() = runTest {
        val clock = FakeClock()
        val budget = budget(clock)
        val url = OverpassMirrors.ENDPOINTS.first()
        repeat(2) { budget.acquire(url, maxWaitMs = 0L) }

        val refusal = budget.acquire(url, maxWaitMs = 0L) as SourceUnavailable.RateLimited
        assertTrue("час повтору має бути додатнім", refusal.retryAfterMs > 0L)
    }

    @Test
    fun `waiting out the interval restores the budget`() = runTest {
        val clock = FakeClock()
        val budget = budget(clock)
        val url = OverpassMirrors.ENDPOINTS.first()
        repeat(2) { budget.acquire(url, maxWaitMs = 0L) }
        assertNotNull(budget.acquire(url, maxWaitMs = 0L))

        clock.now += 5_000L
        assertNull("після паузи бюджет мусить відновитись", budget.acquire(url, maxWaitMs = 0L))
    }

    @Test
    fun `a busy family does not block a different operator`() = runTest {
        val clock = FakeClock()
        val budget = budget(clock)
        val primary = "https://overpass-api.de/api/interpreter"
        repeat(2) { budget.acquire(primary, maxWaitMs = 0L) }
        assertNotNull(budget.acquire(primary, maxWaitMs = 0L))

        assertNull(budget.acquire("https://overpass.private.coffee/api/interpreter", maxWaitMs = 0L))
    }
}
