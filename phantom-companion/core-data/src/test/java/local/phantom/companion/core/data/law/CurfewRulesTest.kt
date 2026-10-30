package local.phantom.companion.core.data.law

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Law 3 (D8) — the curfew warning is data with a source or it does not exist.
 * These are the ratchets: silence where no rule was verified, a named window
 * where one was, and no per-oblast hours anybody invented.
 */
class CurfewRulesTest {

    private fun kyivEpochMs(hour: Int, minute: Int): Long =
        Calendar.getInstance(TimeZone.getTimeZone("Europe/Kyiv")).apply {
            set(2026, Calendar.AUGUST, 22, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun `no region resolved means no warning`() {
        assertNull(CurfewRules.matchAt(null, kyivEpochMs(1, 30)))
    }

    @Test
    fun `a region with no verified rule stays silent`() {
        // Berlin at 01:30 — the exact driver the hardcoded literal used to shout at.
        assertNull(CurfewRules.matchAt("DE", kyivEpochMs(1, 30)))
        assertNull(CurfewRules.matchAt("PL", kyivEpochMs(3, 0)))
    }

    @Test
    fun `inside Ukraine at 0130 the national default matches`() {
        val match = CurfewRules.matchAt(CurfewRules.UKRAINE, kyivEpochMs(1, 30))
        assertNotNull(match)
        assertEquals("01:30", match!!.atHhMm)
        assertEquals("00:00–05:00", match.windowHhMm)
        assertEquals(CurfewScope.NATIONAL, match.rule.scope)
    }

    @Test
    fun `outside the window Ukraine is silent too`() {
        assertNull(CurfewRules.matchAt(CurfewRules.UKRAINE, kyivEpochMs(5, 0)))
        assertNull(CurfewRules.matchAt(CurfewRules.UKRAINE, kyivEpochMs(13, 15)))
        assertNull(CurfewRules.matchAt(CurfewRules.UKRAINE, kyivEpochMs(23, 59)))
    }

    @Test
    fun `the window is read in the region's clock, not the device's`() {
        // Device parked in Tokyo; 01:30 Kyiv is still 01:30 Kyiv.
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
            assertNotNull(CurfewRules.matchAt(CurfewRules.UKRAINE, kyivEpochMs(1, 30)))
            assertNull(CurfewRules.matchAt(CurfewRules.UKRAINE, kyivEpochMs(12, 0)))
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    @Test
    fun `every seeded rule carries a source and a verification date`() {
        assertTrue(CurfewRules.rules.isNotEmpty())
        for (rule in CurfewRules.rules) {
            assertTrue("source missing for ${rule.regionId}", rule.source.isNotBlank())
            assertTrue("verifiedAt missing for ${rule.regionId}", rule.verifiedAt.isNotBlank())
        }
    }

    @Test
    fun `no per-oblast hours are invented`() {
        // Until each oblast is verified by hand, the national default is all
        // we may claim. This test is the gate on that promise.
        assertEquals(listOf(CurfewRules.UKRAINE), CurfewRules.rules.map { it.regionId })
        assertTrue(CurfewRules.rules.all { it.scope == CurfewScope.NATIONAL })
    }

    @Test
    fun `a wrapping window covers both sides of midnight`() {
        val rule = CurfewRule(
            regionId = "TEST",
            scope = CurfewScope.REGIONAL,
            zoneId = "Europe/Kyiv",
            startMinuteOfDay = 23 * 60,
            endMinuteOfDay = 5 * 60,
            source = "фікстура",
            verifiedAt = "2026-08",
        )
        assertTrue(rule.covers(23 * 60 + 30))
        assertTrue(rule.covers(2 * 60))
        assertTrue(!rule.covers(22 * 60))
        assertTrue(!rule.covers(5 * 60))
    }
}
