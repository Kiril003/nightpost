package local.phantom.companion.core.predict.proactive

import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProactiveFrameSourceTest {

    // combine's initial gather order is unspecified, so we drive one step at a time and
    // assert the settled frame after advanceUntilIdle() — fully deterministic.
    private fun TestScope.collectFrames(src: ProactiveFrameSource): Pair<MutableList<ProactiveFrame>, Job> {
        val frames = mutableListOf<ProactiveFrame>()
        val job = launch { src.frames().collect { frames.add(it) } }
        advanceUntilIdle()
        return frames to job
    }

    @Test
    fun firstFrame_containsSeededNonNullSignals_omitsNullAndBlank() = runTest {
        val gnss = MutableStateFlow<String?>("OK")
        val route = MutableStateFlow<String?>("12 min")
        val blank = MutableStateFlow<String?>("   ")
        val nul = MutableStateFlow<String?>(null)
        val src = ProactiveFrameSource(
            mapOf("gnss_trust" to gnss, "route" to route, "blank" to blank, "nul" to nul),
            clock = { 0L },
        )
        val (frames, job) = collectFrames(src)

        val f = frames.last()
        assertEquals("OK", f.signals["gnss_trust"])
        assertEquals("12 min", f.signals["route"])
        assertFalse(f.signals.containsKey("blank"))
        assertFalse(f.signals.containsKey("nul"))
        assertEquals(2, f.signals.size)
        job.cancel()
    }

    @Test
    fun minorChangeInNonCriticalKey_yieldsLowSalience() = runTest {
        val route = MutableStateFlow<String?>("clear")
        val src = ProactiveFrameSource(
            mapOf("route" to route),
            clock = { 0L },
            criticalKeys = emptySet(),
            perSignalWeight = 20,
        )
        val (frames, job) = collectFrames(src)

        route.value = "jam ahead" // one non-critical value change
        advanceUntilIdle()

        assertEquals(20, frames.last().salienceHint)
        job.cancel()
    }

    @Test
    fun changeInCriticalKey_yieldsSalienceAtLeastCriticalBoost() = runTest {
        val gnss = MutableStateFlow<String?>("OK")
        val src = ProactiveFrameSource(
            mapOf("gnss_trust" to gnss),
            clock = { 0L },
            criticalKeys = setOf("gnss_trust"),
            perSignalWeight = 20,
            criticalBoost = 80,
        )
        val (frames, job) = collectFrames(src)

        gnss.value = "SPOOFED" // critical flip
        advanceUntilIdle()

        assertEquals("SPOOFED", frames.last().signals["gnss_trust"])
        assertTrue(frames.last().salienceHint >= 80)
        job.cancel()
    }

    @Test
    fun blankOrNullValue_removesKeyFromSignals() = runTest {
        val gnss = MutableStateFlow<String?>("OK")
        val route = MutableStateFlow<String?>("clear")
        val src = ProactiveFrameSource(
            mapOf("gnss_trust" to gnss, "route" to route),
            clock = { 0L },
        )
        val (frames, job) = collectFrames(src)
        assertTrue(frames.last().signals.containsKey("route"))

        route.value = "   " // blank removes it
        advanceUntilIdle()
        assertFalse(frames.last().signals.containsKey("route"))
        assertEquals("OK", frames.last().signals["gnss_trust"])

        gnss.value = null // null removes it too
        advanceUntilIdle()
        assertFalse(frames.last().signals.containsKey("gnss_trust"))
        job.cancel()
    }

    @Test
    fun clockIsUsedForEpochMs() = runTest {
        val gnss = MutableStateFlow<String?>("OK")
        val src = ProactiveFrameSource(mapOf("gnss_trust" to gnss), clock = { 424242L })
        val (frames, job) = collectFrames(src)

        assertEquals(424242L, frames.last().epochMs)
        job.cancel()
    }
}
