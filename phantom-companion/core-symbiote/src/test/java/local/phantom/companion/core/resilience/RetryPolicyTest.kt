package local.phantom.companion.core.resilience

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryPolicyTest {

    private val policy = RetryPolicy(maxAttempts = 3, baseDelayMs = 200L)

    // ── delayFor ──────────────────────────────────────────────────────────

    @Test
    fun `delayFor attempt 0 returns base delay`() {
        assertEquals(200L, policy.delayFor(0))
    }

    @Test
    fun `delayFor attempt 1 returns 2x base`() {
        assertEquals(400L, policy.delayFor(1))
    }

    @Test
    fun `delayFor attempt 2 returns 4x base`() {
        assertEquals(800L, policy.delayFor(2))
    }

    @Test
    fun `delayFor attempt 3 returns 8x base`() {
        assertEquals(1_600L, policy.delayFor(3))
    }

    @Test
    fun `delayFor attempt 4 returns 16x base`() {
        assertEquals(3_200L, policy.delayFor(4))
    }

    @Test
    fun `delayFor clamps at MAX_DELAY_MS for large attempt`() {
        // 200 * 2^5 = 6400 > 5000 — must clamp
        assertEquals(RetryPolicy.MAX_DELAY_MS, policy.delayFor(5))
    }

    @Test
    fun `delayFor clamps at MAX_DELAY_MS for very large attempt`() {
        assertEquals(RetryPolicy.MAX_DELAY_MS, policy.delayFor(100))
    }

    @Test
    fun `delayFor negative attempt treated as zero`() {
        // coerceAtLeast(0) ensures no negative shifts
        assertEquals(200L, policy.delayFor(-1))
    }

    // ── shouldRetry ───────────────────────────────────────────────────────

    @Test
    fun `shouldRetry returns true while attempt is less than maxAttempts`() {
        assertTrue(policy.shouldRetry(0))
        assertTrue(policy.shouldRetry(1))
        assertTrue(policy.shouldRetry(2))
    }

    @Test
    fun `shouldRetry returns false at maxAttempts`() {
        assertFalse(policy.shouldRetry(3))
    }

    @Test
    fun `shouldRetry returns false beyond maxAttempts`() {
        assertFalse(policy.shouldRetry(10))
    }

    // ── companion defaults ────────────────────────────────────────────────

    @Test
    fun `Default has maxAttempts 3 and baseDelayMs 200`() {
        assertEquals(3, RetryPolicy.Default.maxAttempts)
        assertEquals(200L, RetryPolicy.Default.baseDelayMs)
    }

    @Test
    fun `MAX_DELAY_MS is 5000`() {
        assertEquals(5_000L, RetryPolicy.MAX_DELAY_MS)
    }
}
