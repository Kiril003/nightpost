package local.phantom.companion.feature.stream.reflection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReflectionTriggerTest {

    private val now = 1_700_000_000_000L
    private val h = 60L * 60_000L

    @Test
    fun `cooldown blocks any emit even when other triggers fire`() {
        val r = ReflectionTrigger.evaluate(
            nowMs = now,
            lastUserSubmitMs = now - 10L * h,
            turnsSinceLastReflection = 100,
            lastReflectionMs = now - 30L * 60_000L,
        )
        assertEquals(ReflectionTrigger.Decision.Skip, r)
    }

    @Test
    fun `return after long absence emits with absence reason`() {
        val r = ReflectionTrigger.evaluate(
            nowMs = now,
            lastUserSubmitMs = now - 5L * h,
            turnsSinceLastReflection = 0,
            lastReflectionMs = null,
        )
        assertTrue(r is ReflectionTrigger.Decision.Emit)
        assertEquals(
            ReflectionTrigger.Reason.RETURN_AFTER_ABSENCE,
            (r as ReflectionTrigger.Decision.Emit).reason,
        )
    }

    @Test
    fun `25-turn milestone emits with milestone reason`() {
        val r = ReflectionTrigger.evaluate(
            nowMs = now,
            lastUserSubmitMs = now - 5L * 60_000L,
            turnsSinceLastReflection = 25,
            lastReflectionMs = now - 3L * h,
        )
        assertTrue(r is ReflectionTrigger.Decision.Emit)
        assertEquals(
            ReflectionTrigger.Reason.TURN_MILESTONE,
            (r as ReflectionTrigger.Decision.Emit).reason,
        )
    }

    @Test
    fun `no recent submit and no milestone returns skip`() {
        val r = ReflectionTrigger.evaluate(
            nowMs = now,
            lastUserSubmitMs = null,
            turnsSinceLastReflection = 0,
            lastReflectionMs = null,
        )
        assertEquals(ReflectionTrigger.Decision.Skip, r)
    }

    @Test
    fun `cold-start with no last submit does not crash and skips`() {
        val r = ReflectionTrigger.evaluate(
            nowMs = now,
            lastUserSubmitMs = null,
            turnsSinceLastReflection = 3,
            lastReflectionMs = null,
        )
        assertEquals(ReflectionTrigger.Decision.Skip, r)
    }

    @Test
    fun `absence trigger respects min gap between reflections`() {
        val r = ReflectionTrigger.evaluate(
            nowMs = now,
            lastUserSubmitMs = now - 5L * h,
            turnsSinceLastReflection = 0,
            lastReflectionMs = now - 1L * h,
        )
        assertEquals(ReflectionTrigger.Decision.Skip, r)
    }
}
