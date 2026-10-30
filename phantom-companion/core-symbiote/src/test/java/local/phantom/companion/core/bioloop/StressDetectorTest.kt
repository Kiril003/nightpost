package local.phantom.companion.core.bioloop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StressDetectorTest {

    @Test
    fun `all signals zero stays calm`() {
        val d = StressDetector()
        assertEquals(StressLevel.CALM, d.level.value)
    }

    @Test
    fun `single high signal elevates`() {
        val d = StressDetector()
        d.ingest(StressDetector.Signal.HRV_CRASH, 1.0)
        // hrv weight 0.4 → 0.4 score → ELEVATED.
        assertEquals(StressLevel.ELEVATED, d.level.value)
    }

    @Test
    fun `multiple high signals reach HIGH`() {
        val d = StressDetector()
        d.ingest(StressDetector.Signal.HRV_CRASH, 1.0)
        d.ingest(StressDetector.Signal.VOICE_PITCH, 1.0)
        d.ingest(StressDetector.Signal.TYPO_CLUSTER, 1.0)
        d.ingest(StressDetector.Signal.SCROLL_VELOCITY, 1.0)
        assertEquals(StressLevel.HIGH, d.level.value)
        assertEquals(1.0, d.score.value, 0.001)
    }

    @Test
    fun `out of bound values are clamped`() {
        val d = StressDetector()
        d.ingest(StressDetector.Signal.HRV_CRASH, 5.0)
        assertTrue(d.score.value <= 0.4 + 1e-9)
    }

    @Test
    fun `reset zeroes everything`() {
        val d = StressDetector()
        d.ingest(StressDetector.Signal.HRV_CRASH, 1.0)
        d.ingest(StressDetector.Signal.VOICE_PITCH, 1.0)
        d.reset()
        assertEquals(StressLevel.CALM, d.level.value)
        assertEquals(0.0, d.score.value, 0.001)
    }
}
