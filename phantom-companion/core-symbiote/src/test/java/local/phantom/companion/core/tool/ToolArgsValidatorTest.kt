package local.phantom.companion.core.tool

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolArgsValidatorTest {

    private val shellSchema = buildJsonObject {
        put("cmd", JsonPrimitive("string"))
        put("timeoutMs", JsonPrimitive("number?"))
    }

    @Test
    fun `a complete call passes`() {
        val args = buildJsonObject {
            put("cmd", JsonPrimitive("ls"))
            put("timeoutMs", JsonPrimitive(500))
        }
        assertEquals(emptyList<String>(), ToolArgsValidator.validate(shellSchema, args))
    }

    @Test
    fun `an omitted optional passes`() {
        val args = buildJsonObject { put("cmd", JsonPrimitive("ls")) }
        assertEquals(emptyList<String>(), ToolArgsValidator.validate(shellSchema, args))
    }

    @Test
    fun `a missing required field is named`() {
        val problems = ToolArgsValidator.validate(shellSchema, buildJsonObject { })
        assertEquals(1, problems.size)
        assertTrue(problems.first(), problems.first().contains("cmd"))
    }

    @Test
    fun `an explicit null counts as absent`() {
        val args = buildJsonObject { put("cmd", kotlinx.serialization.json.JsonNull) }
        assertEquals(1, ToolArgsValidator.validate(shellSchema, args).size)
    }

    @Test
    fun `a wrong type is named`() {
        val args = buildJsonObject {
            put("cmd", JsonPrimitive("ls"))
            put("timeoutMs", JsonPrimitive("soon"))
        }
        val problems = ToolArgsValidator.validate(shellSchema, args)
        assertEquals(1, problems.size)
        assertTrue(problems.first(), problems.first().contains("timeoutMs"))
    }

    @Test
    fun `a number sent as a string still passes`() {
        // Local models quote everything. The tool would have parsed it.
        val args = buildJsonObject {
            put("cmd", JsonPrimitive("ls"))
            put("timeoutMs", JsonPrimitive("500"))
        }
        assertEquals(emptyList<String>(), ToolArgsValidator.validate(shellSchema, args))
    }

    @Test
    fun `unknown keys are not violations`() {
        val args = buildJsonObject {
            put("cmd", JsonPrimitive("ls"))
            put("because", JsonPrimitive("the model felt chatty"))
        }
        assertEquals(emptyList<String>(), ToolArgsValidator.validate(shellSchema, args))
    }

    @Test
    fun `object and array types are distinguished`() {
        val schema = buildJsonObject {
            put("map", JsonPrimitive("object"))
            put("points", JsonPrimitive("array"))
        }
        val swapped = buildJsonObject {
            put("map", buildJsonArray { })
            put("points", buildJsonObject { })
        }
        assertEquals(2, ToolArgsValidator.validate(schema, swapped).size)

        val right = buildJsonObject {
            put("map", buildJsonObject { })
            put("points", buildJsonArray { })
        }
        assertEquals(emptyList<String>(), ToolArgsValidator.validate(schema, right))
    }

    @Test
    fun `prose after the type word is ignored`() {
        // Two tools in the tree write "string (e.g. game2048)" as their type.
        val schema = buildJsonObject { put("id", JsonPrimitive("string (e.g. game2048)")) }
        val args = buildJsonObject { put("id", JsonPrimitive("game2048")) }
        assertEquals(emptyList<String>(), ToolArgsValidator.validate(schema, args))
    }

    @Test
    fun `an any-typed field accepts anything`() {
        val schema = buildJsonObject { put("payload", JsonPrimitive("any")) }
        val args = buildJsonObject { put("payload", buildJsonArray { }) }
        assertEquals(emptyList<String>(), ToolArgsValidator.validate(schema, args))
    }
}
