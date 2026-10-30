package local.phantom.companion.core.constitution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SunsetSchedulerTest {

    private val now = 1_700_000_000_000L
    private val msPerDay = 24L * 60L * 60L * 1000L

    @Test
    fun `idle longer than window expires`() {
        val m = FeatureManifest(id = "f.idle", tier = 14, displayName = "x", sunsetAfterDaysIdle = 30)
        val s = SunsetScheduler(
            clock = { now },
            lastActivity = { id -> if (id == "f.idle") now - 31 * msPerDay else null },
        )
        assertEquals(setOf("f.idle"), s.reconcile(listOf(m)))
    }

    @Test
    fun `idle inside window does not expire`() {
        val m = FeatureManifest(id = "f.fresh", tier = 14, displayName = "x", sunsetAfterDaysIdle = 30)
        val s = SunsetScheduler(
            clock = { now },
            lastActivity = { id -> if (id == "f.fresh") now - 29 * msPerDay else null },
        )
        assertTrue(s.reconcile(listOf(m)).isEmpty())
    }

    @Test
    fun `never-sunset is exempt`() {
        val m = FeatureManifest(id = "f.never", tier = 14, displayName = "x", sunsetAfterDaysIdle = -1)
        val s = SunsetScheduler(
            clock = { now },
            lastActivity = { now - 365L * msPerDay },
        )
        assertTrue(s.reconcile(listOf(m)).isEmpty())
    }
}
