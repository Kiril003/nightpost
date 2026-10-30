package local.phantom.companion.core.tool

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallParserTest {

    private fun json(raw: String): JsonObject =
        Json.parseToJsonElement(raw).jsonObject

    // ---------------------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------------------

    @Test
    fun `valid full tool-call parses all fields`() {
        val obj = json(
            """{"type":"tool-call","tool":"shell","args":{"cmd":"ls"},"stream":true,"say":"checking","agentId":"agent-1"}"""
        )

        val result = ToolCallParser.tryParse(obj)

        assertNotNull(result)
        assertEquals("shell", result!!.toolId)
        assertEquals("ls", result.args["cmd"]?.toString()?.trim('"'))
        assertTrue(result.stream)
        assertEquals("checking", result.say)
        assertEquals("agent-1", result.agentId)
    }

    @Test
    fun `missing type field returns null`() {
        val obj = json("""{"tool":"shell","args":{"cmd":"ls"}}""")
        assertNull(ToolCallParser.tryParse(obj))
    }

    @Test
    fun `wrong type value returns null`() {
        val obj = json("""{"type":"widget","tool":"shell","args":{"cmd":"ls"}}""")
        assertNull(ToolCallParser.tryParse(obj))
    }

    @Test
    fun `missing tool field returns null`() {
        val obj = json("""{"type":"tool-call","args":{"cmd":"ls"}}""")
        assertNull(ToolCallParser.tryParse(obj))
    }

    @Test
    fun `missing optional fields default correctly`() {
        val obj = json("""{"type":"tool-call","tool":"shell","args":{"cmd":"pwd"}}""")

        val result = ToolCallParser.tryParse(obj)

        assertNotNull(result)
        assertEquals("shell", result!!.toolId)
        assertFalse(result.stream)
        assertNull(result.say)
        assertNull(result.agentId)
    }

    @Test
    fun `missing args field defaults to empty JsonObject`() {
        val obj = json("""{"type":"tool-call","tool":"shell"}""")

        val result = ToolCallParser.tryParse(obj)

        assertNotNull(result)
        assertTrue(result!!.args.isEmpty())
    }

    @Test
    fun `a padded tool id still resolves`() {
        // Cost a whole turn on device: «unknown tool:  reminder.list».
        val result = ToolCallParser.tryParse(json("""{"type":"tool-call","tool":" reminder.list "}"""))

        assertNotNull(result)
        assertEquals("reminder.list", result!!.toolId)
    }

    @Test
    fun `a blank tool id is not a tool-call`() {
        assertNull(ToolCallParser.tryParse(json("""{"type":"tool-call","tool":"   "}""")))
    }
}
