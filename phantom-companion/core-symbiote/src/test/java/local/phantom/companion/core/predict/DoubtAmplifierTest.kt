package local.phantom.companion.core.predict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DoubtAmplifierTest {

    private val amp = DoubtAmplifier()

    @Test
    fun `no principles means no prompts`() {
        val prompts = amp.evaluate(
            PendingAction(verb = "open_app", timestampMs = 0L),
            principles = emptyList(),
        )
        assertTrue(prompts.isEmpty())
    }

    @Test
    fun `forbidden verb fires`() {
        val p = Principle(
            id = "no-late-work",
            declaredAtMs = 0L,
            statement = "не залишатися на роботі довше 19",
            forbiddenVerbs = setOf("open_work_app"),
        )
        val prompts = amp.evaluate(
            PendingAction(verb = "open_work_app", timestampMs = 0L),
            principles = listOf(p),
        )
        assertEquals(1, prompts.size)
        assertEquals("no-late-work", prompts.single().principleId)
    }

    @Test
    fun `context tag overlap triggers`() {
        val p = Principle(
            id = "no-evening-slack",
            declaredAtMs = 0L,
            statement = "no evening Slack",
            contextTags = setOf("evening"),
        )
        val prompts = amp.evaluate(
            PendingAction("send_msg", 0L, contextTags = setOf("evening", "slack")),
            principles = listOf(p),
        )
        assertEquals(1, prompts.size)
    }

    @Test
    fun `unrelated verb does not trigger`() {
        val p = Principle("x", 0L, "stay off twitter", forbiddenVerbs = setOf("open_twitter"))
        val prompts = amp.evaluate(PendingAction("open_calendar", 0L), listOf(p))
        assertTrue(prompts.isEmpty())
    }
}
