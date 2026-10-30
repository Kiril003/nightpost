package local.phantom.companion.feature.stream.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolTracePayloadTest {

    private fun field(payload: String, key: String): String? =
        ((Json.parseToJsonElement(payload) as JsonObject)[key] as? JsonPrimitive)?.content

    @Test
    fun `a start payload carries the instrument, its state and its arguments`() {
        val p = ToolTracePayload.start("geo.route", "running", """{"to":"дім"}""", "прокладаю")
        assertEquals("geo.route", field(p, "toolId"))
        assertEquals("running", field(p, "status"))
        assertEquals("""{"to":"дім"}""", field(p, "args"))
        assertEquals("прокладаю", field(p, "say"))
    }

    @Test
    fun `no say means no key, not an empty one`() {
        val p = ToolTracePayload.start("weather.now", "running", "{}", null)
        assertEquals(null, field(p, "say"))
    }

    @Test
    fun `a chunk lands without losing what the card already showed`() {
        val start = ToolTracePayload.start("web.search", "running", "{}", "шукаю")
        val after = ToolTracePayload.append(start, "перший результат")
        assertEquals("web.search", field(after, "toolId"))
        assertEquals("шукаю", field(after, "say"))
        assertEquals("перший результат", field(after, "output"))
    }

    @Test
    fun `chunks accumulate in the order they arrived`() {
        var p = ToolTracePayload.start("shell", "running", "{}", null)
        p = ToolTracePayload.append(p, "один ")
        p = ToolTracePayload.append(p, "два ")
        p = ToolTracePayload.append(p, "три")
        assertEquals("один два три", field(p, "output"))
    }

    @Test
    fun `a talkative tool cannot grow the row without bound`() {
        var p = ToolTracePayload.start("shell", "running", "{}", null)
        repeat(40) { p = ToolTracePayload.append(p, "х".repeat(100)) }
        assertEquals(ToolTracePayload.MAX_OUTPUT, field(p, "output")!!.length)
    }

    @Test
    fun `the tail is what survives truncation, because it is the newest`() {
        var p = ToolTracePayload.start("shell", "running", "{}", null)
        p = ToolTracePayload.append(p, "а".repeat(ToolTracePayload.MAX_OUTPUT))
        p = ToolTracePayload.append(p, "кінець")
        assertTrue(field(p, "output")!!.endsWith("кінець"))
    }

    @Test
    fun `a malformed payload still shows what the tool is saying`() {
        val p = ToolTracePayload.append("не json", "щось сказав")
        assertEquals("щось сказав", field(p, "output"))
    }
}
