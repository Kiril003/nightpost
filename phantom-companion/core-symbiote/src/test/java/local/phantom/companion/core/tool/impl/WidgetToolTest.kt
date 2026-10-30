package local.phantom.companion.core.tool.impl

import android.app.Application
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolPermission
import local.phantom.companion.core.tool.ToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [WidgetTool].
 *
 * No permission gate. [emitWidget] is injected as a fake lambda that records
 * the last call and returns a canned widget ID.
 */
class WidgetToolTest {

    private val stubContext = Application()

    private fun ctx() = ToolContext(
        appContext = stubContext,
        operatorId = "tester",
        requestId = "req-1",
        approvedPermissions = emptySet(),
    )

    private val fakeWidgetId = "widget-fake-42"
    private var lastEmittedType: String? = null
    private var lastEmittedPayload: String? = null

    private val fakeEmit: (String, String) -> String = { type, payload ->
        lastEmittedType = type
        lastEmittedPayload = payload
        fakeWidgetId
    }

    private val tool = WidgetTool(emitWidget = fakeEmit)

    // -------------------------------------------------------------------------
    // Happy path
    // -------------------------------------------------------------------------

    @Test
    fun `valid type and payload returns Ok with widgetId`() = runTest {
        val result = tool.execute(
            buildJsonObject {
                put("type", "chart")
                put("payload", buildJsonObject {
                    put("label", "Battery")
                    put("values", "[]")
                })
            },
            ctx(),
        ) {}
        assertTrue("expected Ok, got $result", result is ToolResult.Ok)
        val ok = result as ToolResult.Ok
        val widgetId = ok.structured!!["widgetId"]?.toString()?.trim('"')
        assertEquals(fakeWidgetId, widgetId)
        assertEquals("chart", ok.structured!!["type"]?.toString()?.trim('"'))
    }

    @Test
    fun `emitWidget lambda receives correct type`() = runTest {
        tool.execute(
            buildJsonObject {
                put("type", "timer")
                put("payload", buildJsonObject { put("durationMs", 60000) })
            },
            ctx(),
        ) {}
        assertEquals("timer", lastEmittedType)
    }

    @Test
    fun `emitWidget payload is valid JSON string`() = runTest {
        tool.execute(
            buildJsonObject {
                put("type", "map")
                put("payload", buildJsonObject { put("lat", 50.45) })
            },
            ctx(),
        ) {}
        assertTrue(
            "payload should be non-empty JSON",
            lastEmittedPayload?.isNotBlank() == true,
        )
        assertTrue(
            "payload should start with {",
            lastEmittedPayload?.trimStart()?.startsWith("{") == true,
        )
    }

    // -------------------------------------------------------------------------
    // Bad args
    // -------------------------------------------------------------------------

    @Test
    fun `missing type returns bad_args`() = runTest {
        val result = tool.execute(
            buildJsonObject { put("payload", buildJsonObject { put("x", 1) }) },
            ctx(),
        ) {}
        assertTrue(result is ToolResult.Err)
        assertEquals("bad_args", (result as ToolResult.Err).code)
    }

    @Test
    fun `missing payload returns bad_args`() = runTest {
        val result = tool.execute(
            buildJsonObject { put("type", "chart") },
            ctx(),
        ) {}
        assertTrue(result is ToolResult.Err)
        assertEquals("bad_args", (result as ToolResult.Err).code)
    }

    @Test
    fun `non-object payload returns bad_args`() = runTest {
        val result = tool.execute(
            buildJsonObject {
                put("type", "chart")
                put("payload", "this is a string not an object")
            },
            ctx(),
        ) {}
        assertTrue(result is ToolResult.Err)
        assertEquals("bad_args", (result as ToolResult.Err).code)
    }

    // -------------------------------------------------------------------------
    // No permission gate
    // -------------------------------------------------------------------------

    @Test
    fun `tool executes with no permissions`() = runTest {
        val result = tool.execute(
            buildJsonObject {
                put("type", "progress")
                put("payload", buildJsonObject { put("ratio", 0.5) })
            },
            ctx(),
        ) {}
        assertTrue(result is ToolResult.Ok)
    }

    @Test
    fun `emitWidget exception is wrapped in widget_error`() = runTest {
        val failTool = WidgetTool(emitWidget = { _, _ -> throw RuntimeException("bus full") })
        val result = failTool.execute(
            buildJsonObject {
                put("type", "chart")
                put("payload", buildJsonObject { put("x", 1) })
            },
            ctx(),
        ) {}
        assertTrue(result is ToolResult.Err)
        assertEquals("widget_error", (result as ToolResult.Err).code)
        assertTrue((result as ToolResult.Err).message.contains("bus full"))
    }

    @Test
    fun `manifest id and sunset are correct`() {
        assertEquals("tool.widget", tool.manifest.id)
        assertEquals(-1, tool.manifest.sunsetAfterDaysIdle)
    }
}
