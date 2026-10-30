package local.phantom.companion.core.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeadlineWatchTest {

    private val now = 1_000_000_000_000L
    private fun watch(deadlineInMin: Long) = DeadlineWatch(now + deadlineInMin * 60_000L)

    @Test
    fun `first estimate seeds band silently`() {
        val w = watch(deadlineInMin = 60)
        val (status, cue) = w.onEstimate(remainingDurationS = 30.0 * 60, nowMs = now)
        assertEquals(DeadlineWatch.Band.COMFORTABLE, status.band)
        assertEquals(30, status.slackMinutes)
        assertNull(cue)
    }

    @Test
    fun `dropping into tight speaks once`() {
        val w = watch(60)
        w.onEstimate(30.0 * 60, now)                       // comfortable, silent
        val (s1, c1) = w.onEstimate(55.0 * 60, now)        // slack 5 min → tight
        assertEquals(DeadlineWatch.Band.TIGHT, s1.band)
        assertTrue(c1 is DeadlineWatch.Cue.Tight)
        // Staying tight — silent.
        val (_, c2) = w.onEstimate(56.0 * 60, now)
        assertNull(c2)
    }

    @Test
    fun `going late speaks the estimated delay`() {
        val w = watch(30)
        w.onEstimate(10.0 * 60, now)
        val (s, cue) = w.onEstimate(45.0 * 60, now) // ETA 15 min past
        assertEquals(DeadlineWatch.Band.LATE, s.band)
        assertTrue(s.slackMinutes < 0)
        assertTrue((cue as DeadlineWatch.Cue.Late).lateMin >= 14)
    }

    @Test
    fun `recovering announces once and re-arms tight`() {
        val w = watch(30)
        w.onEstimate(10.0 * 60, now)          // comfortable
        w.onEstimate(45.0 * 60, now)          // late (cue)
        val (_, rec) = w.onEstimate(10.0 * 60, now) // back to comfortable
        assertEquals(DeadlineWatch.Cue.Recovered, rec)
        // Tight can fire again after recovery.
        val (_, tightAgain) = w.onEstimate(25.0 * 60, now)
        assertTrue(tightAgain is DeadlineWatch.Cue.Tight)
    }

    @Test
    fun `late to tight is a recovery, not a warning`() {
        val w = watch(30)
        w.onEstimate(45.0 * 60, now)          // seeds LATE silently
        val (s, cue) = w.onEstimate(25.0 * 60, now) // slack 5 min → TIGHT
        assertEquals(DeadlineWatch.Band.TIGHT, s.band)
        assertEquals(DeadlineWatch.Cue.Recovered, cue)
    }

    @Test
    fun `leaveBy is deadline minus duration`() {
        val w = watch(60)
        assertEquals(now + 20 * 60_000L, w.leaveByEpochMs(40.0 * 60))
    }

    @Test
    fun `parseDeadline handles formats and rolls to tomorrow`() {
        val cal = java.util.Calendar.getInstance().apply {
            set(2026, 6, 12, 12, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val noon = cal.timeInMillis
        val parsed = DeadlineWatch.parseDeadline("18:00", noon)!!
        assertEquals(6 * 3_600_000L, parsed - noon)
        // Same time in other spellings.
        assertEquals(parsed, DeadlineWatch.parseDeadline("1800", noon))
        assertEquals(parsed, DeadlineWatch.parseDeadline("18.00", noon))
        // 09:00 already past noon → tomorrow morning.
        val tomorrow = DeadlineWatch.parseDeadline("09:00", noon)!!
        assertEquals(21 * 3_600_000L, tomorrow - noon)
        // Garbage stays null.
        assertNull(DeadlineWatch.parseDeadline("завтра", noon))
        assertNull(DeadlineWatch.parseDeadline("25:00", noon))
    }

    @Test
    fun `spoken minutes agree in Ukrainian`() {
        assertEquals("1 хвилина", spokenMinutesUk(1))
        assertEquals("2 хвилини", spokenMinutesUk(2))
        assertEquals("5 хвилин", spokenMinutesUk(5))
        assertEquals("11 хвилин", spokenMinutesUk(11))
        assertEquals("21 хвилина", spokenMinutesUk(21))
        assertEquals("1 хвилина", spokenMinutesUk(0)) // floor to one
    }
}
