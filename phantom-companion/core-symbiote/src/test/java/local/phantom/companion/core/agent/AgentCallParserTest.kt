package local.phantom.companion.core.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentCallParserTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun parse(raw: String): ParsedAgentCall? {
        val obj = json.parseToJsonElement(raw) as JsonObject
        return AgentCallParser.tryParse(obj)
    }

    // ── Single agent ─────────────────────────────────────────────────────────

    @Test
    fun `valid single agent JSON parses to Single with all fields`() {
        val result = parse(
            """{"type":"agent","say":"launching","payload":{
                "goal":"fetch weather",
                "plan":["step1","step2"],
                "tools":["http","file"],
                "maxSteps":6,
                "budgetMs":45000,
                "preferLocal":false,
                "agentId":"my-id-123"
            }}"""
        )
        assertNotNull(result)
        assertTrue(result is ParsedAgentCall.Single)
        result as ParsedAgentCall.Single
        assertEquals("launching", result.say)
        assertEquals("fetch weather", result.spec.goal)
        assertEquals(listOf("step1", "step2"), result.spec.plan)
        assertEquals(setOf("http", "file"), result.spec.allowedTools)
        assertEquals(6, result.spec.maxSteps)
        assertEquals(45_000L, result.spec.budgetMs)
        assertEquals(false, result.spec.preferLocal)
        assertEquals("my-id-123", result.spec.agentId)
    }

    @Test
    fun `wrong type returns null`() {
        val result = parse("""{"type":"tool-call","tool":"shell","args":{}}""")
        assertNull(result)
    }

    @Test
    fun `missing goal returns null`() {
        val result = parse("""{"type":"agent","payload":{"tools":["http"]}}""")
        assertNull(result)
    }

    @Test
    fun `blank goal returns null`() {
        val result = parse("""{"type":"agent","payload":{"goal":"   "}}""")
        assertNull(result)
    }

    @Test
    fun `maxSteps over the hard cap is clamped`() {
        val result = parse("""{"type":"agent","payload":{"goal":"g","maxSteps":99}}""")
        assertNotNull(result)
        result as ParsedAgentCall.Single
        assertEquals(AgentCallParser.MAX_STEPS_HARD_CAP, result.spec.maxSteps)
    }

    @Test
    fun `maxSteps of 0 is clamped to 1`() {
        val result = parse("""{"type":"agent","payload":{"goal":"g","maxSteps":0}}""")
        assertNotNull(result)
        result as ParsedAgentCall.Single
        assertEquals(1, result.spec.maxSteps)
    }

    @Test
    fun `budgetMs over the hard cap is clamped`() {
        // The ceiling is an hour now that a long mission is hosted by a
        // foreground service rather than the chat's own coroutine.
        val result = parse("""{"type":"agent","payload":{"goal":"g","budgetMs":9999999}}""")
        assertNotNull(result)
        result as ParsedAgentCall.Single
        assertEquals(AgentCallParser.MAX_BUDGET_HARD_CAP_MS, result.spec.budgetMs)
    }

    @Test
    fun `budgetMs below 1000 is clamped to 1000`() {
        val result = parse("""{"type":"agent","payload":{"goal":"g","budgetMs":500}}""")
        assertNotNull(result)
        result as ParsedAgentCall.Single
        assertEquals(1_000L, result.spec.budgetMs)
    }

    @Test
    fun `agentId is preserved when supplied`() {
        val result = parse("""{"type":"agent","payload":{"goal":"g","agentId":"supplied-id"}}""")
        assertNotNull(result)
        result as ParsedAgentCall.Single
        assertEquals("supplied-id", result.spec.agentId)
    }

    @Test
    fun `agentId is generated when not supplied`() {
        val result = parse("""{"type":"agent","payload":{"goal":"g"}}""")
        assertNotNull(result)
        result as ParsedAgentCall.Single
        assertTrue("agentId should be non-blank", result.spec.agentId.isNotBlank())
    }

    // ── Parallel agents ──────────────────────────────────────────────────────

    @Test
    fun `parallel agents JSON parses to Parallel with N specs`() {
        val result = parse(
            """{"type":"agent-parallel","say":"running 2","agents":[
                {"agentId":"a1","goal":"fetch news","tools":["http"]},
                {"agentId":"a2","goal":"check calendar","tools":["calendar"]}
            ]}"""
        )
        assertNotNull(result)
        assertTrue(result is ParsedAgentCall.Parallel)
        result as ParsedAgentCall.Parallel
        assertEquals("running 2", result.say)
        assertEquals(2, result.specs.size)
        assertEquals("a1", result.specs[0].agentId)
        assertEquals("fetch news", result.specs[0].goal)
        assertEquals(setOf("http"), result.specs[0].allowedTools)
        assertEquals("a2", result.specs[1].agentId)
    }

    @Test
    fun `parallel empty agents array returns null`() {
        val result = parse("""{"type":"agent-parallel","agents":[]}""")
        assertNull(result)
    }

    @Test
    fun `parallel array of 5 specs keeps only first 3`() {
        val agents = (1..5).joinToString(",") { """{"goal":"goal $it"}""" }
        val result = parse("""{"type":"agent-parallel","agents":[$agents]}""")
        assertNotNull(result)
        result as ParsedAgentCall.Parallel
        assertEquals(AgentCallParser.MAX_PARALLEL_HARD_CAP, result.specs.size)
    }

    @Test
    fun `parallel missing agents field returns null`() {
        val result = parse("""{"type":"agent-parallel","say":"oops"}""")
        assertNull(result)
    }

    @Test
    fun `parallel agent without goal is skipped`() {
        val result = parse(
            """{"type":"agent-parallel","agents":[
                {"goal":"good"},
                {"tools":["http"]}
            ]}"""
        )
        // Only 1 valid spec; empty result → null
        assertNotNull(result)
        result as ParsedAgentCall.Parallel
        assertEquals(1, result.specs.size)
        assertEquals("good", result.specs[0].goal)
    }
}
