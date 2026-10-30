package local.phantom.companion.core.agent

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The board a long mission is pinned to. Its whole reason to exist is that
 * it outlives the screen — so what it must never do is lose a mission, or
 * keep claiming one is running after it ended.
 */
class AgentMissionHubTest {

    @After
    fun clear() {
        AgentMissionHub.missions.value.forEach { AgentMissionHub.cancel(it.id) }
        AgentMissionHub.clearFinished()
    }

    private fun spec(id: String, budget: Long = 600_000L) =
        AgentSpec(agentId = id, goal = "дослідь ринок дронів", maxSteps = 20, budgetMs = budget)

    @Test
    fun `a requested mission lands on the board as queued`() {
        AgentMissionHub.request(spec("m1"), nowMs = 1_000L)
        val m = AgentMissionHub.find("m1")!!
        assertEquals(AgentMissionHub.Phase.QUEUED, m.phase)
        assertEquals(20, m.maxSteps)
        assertTrue(m.active)
    }

    @Test
    fun `progress moves it to running and records the step`() {
        AgentMissionHub.request(spec("m2"))
        AgentMissionHub.onProgress(AgentProgress("m2", 3, "web.search", "running", "шукаю"))
        val m = AgentMissionHub.find("m2")!!
        assertEquals(AgentMissionHub.Phase.RUNNING, m.phase)
        assertEquals(3, m.step)
        assertEquals("web.search", m.action)
        assertEquals("шукаю", m.note)
    }

    @Test
    fun `a finished mission stops being active and keeps its summary`() = runTest {
        AgentMissionHub.request(spec("m3"))
        AgentMissionHub.onFinished(
            AgentResult.Done("m3", stepsRun = 7, durationMs = 400_000L, summary = "готово", artifacts = emptyList()),
        )
        val m = AgentMissionHub.find("m3")!!
        assertEquals(AgentMissionHub.Phase.DONE, m.phase)
        assertEquals("готово", m.summary)
        assertFalse("a finished mission must not keep the service alive", m.active)
    }

    @Test
    fun `a failure keeps the partial answer instead of throwing it away`() {
        AgentMissionHub.request(spec("m4"))
        AgentMissionHub.onFinished(
            AgentResult.Failed("m4", 5, 90_000L, reason = "budget_exhausted", partialSummary = "півдороги"),
        )
        val m = AgentMissionHub.find("m4")!!
        assertEquals(AgentMissionHub.Phase.FAILED, m.phase)
        assertEquals("півдороги", m.summary)
        assertEquals("budget_exhausted", m.note)
    }

    @Test
    fun `progress for an unknown id changes nothing`() {
        AgentMissionHub.onProgress(AgentProgress("ghost", 1, "x", "running"))
        assertNull(AgentMissionHub.find("ghost"))
    }

    @Test
    fun `clearFinished keeps the running one`() {
        AgentMissionHub.request(spec("keep"))
        AgentMissionHub.request(spec("drop"))
        AgentMissionHub.onFinished(AgentResult.Cancelled("drop", 0, 0L))
        AgentMissionHub.clearFinished()
        assertEquals(listOf("keep"), AgentMissionHub.missions.value.map { it.id })
    }

    @Test
    fun `re-requesting the same id replaces rather than duplicates`() {
        AgentMissionHub.request(spec("same"))
        AgentMissionHub.request(spec("same"))
        assertEquals(1, AgentMissionHub.missions.value.count { it.id == "same" })
    }

    @Test
    fun `the long-mission line sits above a normal turn and below the cap`() {
        assertTrue(AgentMissionHub.LONG_MISSION_MS > 60_000L)
        assertTrue(AgentMissionHub.LONG_MISSION_MS < AgentCallParser.MAX_BUDGET_HARD_CAP_MS)
    }

    @Test
    fun `elapsed never runs backwards on a clock jump`() {
        AgentMissionHub.request(spec("clock"), nowMs = 10_000L)
        assertEquals(0L, AgentMissionHub.find("clock")!!.elapsedMs(5_000L))
    }
}
