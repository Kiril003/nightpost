package local.phantom.companion.feature.stream.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import local.phantom.companion.core.agent.AgentProgress
import local.phantom.companion.core.agent.AgentSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentProgressPayloadTest {

    private fun field(json: String, key: String): String? =
        Json.parseToJsonElement(json).jsonObject[key]?.jsonPrimitive?.content

    private val spec = AgentSpec(
        agentId = "a-1",
        goal = "знайти каву",
        allowedTools = setOf("http", "map.search"),
    )

    @Test
    fun `initial carries the spec and a zeroed step counter`() {
        val json = AgentProgressPayload.initial(spec, status = "running")
        assertEquals("a-1", field(json, "agentId"))
        assertEquals("знайти каву", field(json, "goal"))
        assertEquals("running", field(json, "status"))
        assertEquals("0", field(json, "stepsRun"))
        assertEquals("", field(json, "summary"))
    }

    @Test
    fun `initial omits tools when the spec allows none`() {
        val json = AgentProgressPayload.initial(spec.copy(allowedTools = emptySet()), "running")
        assertNull(Json.parseToJsonElement(json).jsonObject["tools"])
    }

    @Test
    fun `merge advances the step without losing the goal`() {
        val start = AgentProgressPayload.initial(spec, status = "running")
        val merged = AgentProgressPayload.merge(
            start,
            AgentProgress("a-1", stepIndex = 3, action = "http", status = "ok", detail = "200"),
        )
        assertEquals("знайти каву", field(merged, "goal"))
        assertEquals("3", field(merged, "stepsRun"))
        assertEquals("http", field(merged, "currentAction"))
        assertEquals("ok", field(merged, "currentStatus"))
        assertEquals("200", field(merged, "currentDetail"))
        assertEquals("running", field(merged, "status"))
    }

    @Test
    fun `merge writes an empty detail rather than null`() {
        val merged = AgentProgressPayload.merge(
            AgentProgressPayload.initial(spec, "running"),
            AgentProgress("a-1", 1, "thinking", "running", detail = null),
        )
        assertEquals("", field(merged, "currentDetail"))
    }

    @Test
    fun `terminal seals the widget and keeps the launch fields`() {
        val running = AgentProgressPayload.merge(
            AgentProgressPayload.initial(spec, "running"),
            AgentProgress("a-1", 2, "map.search", "ok"),
        )
        val done = AgentProgressPayload.terminal(
            payloadJson = running,
            status = "done",
            summary = "три кафе поруч",
            stepsRun = 2,
            durationMs = 1234L,
        )
        assertEquals("done", field(done, "status"))
        assertEquals("три кафе поруч", field(done, "summary"))
        assertEquals("2", field(done, "stepsRun"))
        assertEquals("1234", field(done, "durationMs"))
        assertEquals("знайти каву", field(done, "goal"))
        assertTrue(Json.parseToJsonElement(done).jsonObject.containsKey("tools"))
    }

    @Test
    fun `a malformed prior payload degrades to the new fields instead of throwing`() {
        val merged = AgentProgressPayload.merge(
            "not json at all",
            AgentProgress("a-1", 1, "http", "ok"),
        )
        assertEquals("1", field(merged, "stepsRun"))
        assertEquals("running", field(merged, "status"))
        assertNull(field(merged, "goal"))
    }

    @Test
    fun `a card restored still running is sealed as interrupted`() {
        val running = AgentProgressPayload.initial(
            AgentSpec(agentId = "a-9", goal = "порахувати всі кафе", allowedTools = setOf("http")),
            status = "running",
        )
        val sealed = AgentProgressPayload.sealInterrupted(running)
        assertEquals("interrupted", field(sealed!!, "status"))
        assertEquals("порахувати всі кафе", field(sealed, "goal"))
        assertEquals("a-9", field(sealed, "agentId"))
    }

    @Test
    fun `a card that already finished is left alone`() {
        val done = AgentProgressPayload.initial(
            AgentSpec(agentId = "a-9", goal = "готово", allowedTools = emptySet()),
            status = "done",
        )
        assertNull(AgentProgressPayload.sealInterrupted(done))
        assertNull(AgentProgressPayload.sealInterrupted("not json at all"))
    }
}
