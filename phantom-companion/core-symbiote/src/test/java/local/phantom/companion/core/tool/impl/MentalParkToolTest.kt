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
 * Unit tests for [MentalParkTool].
 *
 * No permission gate exists on this tool (cognitive aid). All tests run with
 * empty approved permissions to confirm the tool always executes.
 *
 * [parkAdd] is injected as a fake lambda — no `:core-offload` dependency.
 */
class MentalParkToolTest {

    private val stubContext = Application()

    private fun ctx() = ToolContext(
        appContext = stubContext,
        operatorId = "tester",
        requestId = "req-1",
        approvedPermissions = emptySet(), // no permissions required
    )

    private val fakeId = "park-fake-001"
    private val fakeParkAdd: (String, String?, String?) -> String = { _, _, _ -> fakeId }

    private val tool = MentalParkTool(parkAdd = fakeParkAdd)

    // -------------------------------------------------------------------------
    // Happy path
    // -------------------------------------------------------------------------

    @Test
    fun `valid text returns Ok with parked=true and correct id`() = runTest {
        val result = tool.execute(
            buildJsonObject { put("text", "remember to check battery drain graph") },
            ctx(),
        ) {}
        assertTrue("expected Ok, got $result", result is ToolResult.Ok)
        val ok = result as ToolResult.Ok
        val parked = ok.structured!!["parked"]?.toString()?.trim('"')
        val id = ok.structured!!["id"]?.toString()?.trim('"')
        assertEquals("true", parked)
        assertEquals(fakeId, id)
    }

    @Test
    fun `anchor and category are reflected in structured output`() = runTest {
        val result = tool.execute(
            buildJsonObject {
                put("text", "design the new onboarding flow")
                put("anchor", "project:onboarding")
                put("category", "design")
            },
            ctx(),
        ) {}
        assertTrue(result is ToolResult.Ok)
        val ok = result as ToolResult.Ok
        assertEquals("project:onboarding", ok.structured!!["anchor"]?.toString()?.trim('"'))
        assertEquals("design", ok.structured!!["category"]?.toString()?.trim('"'))
    }

    // -------------------------------------------------------------------------
    // Bad args
    // -------------------------------------------------------------------------

    @Test
    fun `missing text returns bad_args`() = runTest {
        val result = tool.execute(buildJsonObject { }, ctx()) {}
        assertTrue(result is ToolResult.Err)
        assertEquals("bad_args", (result as ToolResult.Err).code)
    }

    // -------------------------------------------------------------------------
    // No permission gate — tool runs with empty permissions
    // -------------------------------------------------------------------------

    @Test
    fun `tool executes with no permissions in context`() = runTest {
        val result = tool.execute(
            buildJsonObject { put("text", "this should park even without perms") },
            ctx(), // empty permissions
        ) {}
        assertTrue("MentalParkTool needs no permission, got $result", result is ToolResult.Ok)
    }

    @Test
    fun `parkAdd exception is wrapped in park_error`() = runTest {
        val failingTool = MentalParkTool(parkAdd = { _, _, _ ->
            throw RuntimeException("storage full")
        })
        val result = failingTool.execute(
            buildJsonObject { put("text", "idea") },
            ctx(),
        ) {}
        assertTrue(result is ToolResult.Err)
        assertEquals("park_error", (result as ToolResult.Err).code)
        assertTrue(result.message.contains("storage full"))
    }

    @Test
    fun `manifest id tier and sunset are correct`() {
        assertEquals("tool.mental_park", tool.manifest.id)
        assertEquals(23, tool.manifest.tier)
        assertEquals(365, tool.manifest.sunsetAfterDaysIdle)
    }
}
