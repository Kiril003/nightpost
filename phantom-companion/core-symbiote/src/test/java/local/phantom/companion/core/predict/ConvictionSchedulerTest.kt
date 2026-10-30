package local.phantom.companion.core.predict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConvictionSchedulerTest {

    private val msPerDay = 24L * 60L * 60L * 1000L

    @Test
    fun `inside first window nothing due`() {
        var now = 0L
        val s = ConvictionScheduler(clock = { now })
        val score = ConvictionScore("d1", 8, recordedAtMs = 0L)
        now = 89L * msPerDay
        val due = s.due(listOf(score), emptySet())
        assertTrue(due.isEmpty())
    }

    @Test
    fun `90 days hits the milestone`() {
        var now = 0L
        val s = ConvictionScheduler(clock = { now })
        val score = ConvictionScore("d1", 8, recordedAtMs = 0L)
        now = 90L * msPerDay
        val due = s.due(listOf(score), emptySet())
        assertEquals(1, due.size)
        assertEquals(ConvictionMilestone.DAYS_90, due.single().milestone)
    }

    @Test
    fun `already-fired milestone is skipped`() {
        var now = 0L
        val s = ConvictionScheduler(clock = { now })
        val score = ConvictionScore("d1", 8, recordedAtMs = 0L)
        now = 91L * msPerDay
        val fired = setOf("d1" to ConvictionMilestone.DAYS_90)
        val due = s.due(listOf(score), fired)
        assertTrue(due.isEmpty())
    }

    @Test
    fun `365 day milestone fires`() {
        var now = 0L
        val s = ConvictionScheduler(clock = { now })
        val score = ConvictionScore("d1", 8, recordedAtMs = 0L)
        now = 366L * msPerDay
        val due = s.due(listOf(score), emptySet())
        // 90, 180, 365 all due.
        assertEquals(3, due.size)
    }
}
