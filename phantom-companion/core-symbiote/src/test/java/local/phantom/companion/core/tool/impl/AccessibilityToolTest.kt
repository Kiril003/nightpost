package local.phantom.companion.core.tool.impl

import android.app.Application
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolEvent
import local.phantom.companion.core.tool.ToolPermission
import local.phantom.companion.core.tool.ToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [AccessibilityTool] (R5.1 awaited/honest rewrite).
 *
 * The dispatch is injected as a lambda that returns a controllable
 * [AccessibilitySequenceOutcome], so we can assert the tool maps each
 * outcome (all-done / service-off / operator-intervened / step-failed)
 * to the right ToolResult — no Robolectric, no Android runtime.
 */
class AccessibilityToolTest {

    private val stubContext = Application()

    private fun ctx(vararg perms: ToolPermission) = ToolContext(
        appContext = stubContext,
        operatorId = "tester",
        requestId = "req-1",
        approvedPermissions = setOf(*perms),
    )

    /** Fake parser — returns one String token per JSON element in the array. */
    private val fakeParser: (JsonArray) -> List<Any> = { arr ->
        arr.mapIndexed { i, _ -> "action-$i" }
    }

    /** Build a tool whose dispatch returns [outcome], recording the actions it saw. */
    private fun toolReturning(
        outcome: (Int) -> AccessibilitySequenceOutcome,
    ): Pair<AccessibilityTool, MutableList<Int>> {
        val seen = mutableListOf<Int>()
        val tool = AccessibilityTool(
            parseActions = fakeParser,
            dispatchSequence = { actions, onStep ->
                seen += actions.size
                actions.forEachIndexed { i, _ -> onStep(i + 1, actions.size, "step") }
                outcome(actions.size)
            },
        )
        return tool to seen
    }

    private fun actions(n: Int) = buildJsonObject {
        put("actions", buildJsonArray { repeat(n) { add(buildJsonObject { put("action", "back") }) } })
    }

    @Test
    fun `missing ACCESSIBILITY permission returns PermissionDenied`() = runTest {
        val (tool, _) = toolReturning { AccessibilitySequenceOutcome(it, it, null) }
        val result = tool.execute(actions(1), ctx()) {}
        assertTrue(result is ToolResult.PermissionDenied)
        assertEquals(ToolPermission.ACCESSIBILITY, (result as ToolResult.PermissionDenied).permission)
    }

    @Test
    fun `missing actions key returns bad_args`() = runTest {
        val (tool, _) = toolReturning { AccessibilitySequenceOutcome(it, it, null) }
        val result = tool.execute(buildJsonObject { }, ctx(ToolPermission.ACCESSIBILITY)) {}
        assertTrue(result is ToolResult.Err)
        assertEquals("bad_args", (result as ToolResult.Err).code)
    }

    @Test
    fun `empty actions array returns bad_args`() = runTest {
        val (tool, _) = toolReturning { AccessibilitySequenceOutcome(it, it, null) }
        val result = tool.execute(actions(0), ctx(ToolPermission.ACCESSIBILITY)) {}
        assertTrue(result is ToolResult.Err)
        assertEquals("bad_args", (result as ToolResult.Err).code)
    }

    @Test
    fun `all steps complete returns Ok with succeeded=dispatched`() = runTest {
        val (tool, _) = toolReturning { AccessibilitySequenceOutcome(it, it, null) }
        val events = mutableListOf<ToolEvent>()
        val result = tool.execute(actions(3), ctx(ToolPermission.ACCESSIBILITY)) { events += it }
        assertTrue("expected Ok, got $result", result is ToolResult.Ok)
        val ok = result as ToolResult.Ok
        assertEquals(3, ok.structured!!["dispatched"]!!.toString().toInt())
        assertEquals(3, ok.structured!!["succeeded"]!!.toString().toInt())
        assertTrue(events.any { it is ToolEvent.Progress })
    }

    @Test
    fun `service unbound maps to accessibility_off error`() = runTest {
        val (tool, _) = toolReturning {
            AccessibilitySequenceOutcome(
                0, it,
                AccessibilitySequenceOutcome.Failure(
                    AccessibilitySequenceOutcome.KIND_SERVICE_UNBOUND, "off",
                ),
            )
        }
        val result = tool.execute(actions(2), ctx(ToolPermission.ACCESSIBILITY)) {}
        assertTrue(result is ToolResult.Err)
        assertEquals("accessibility_off", (result as ToolResult.Err).code)
    }

    @Test
    fun `operator intervention maps to operator_intervened error`() = runTest {
        val (tool, _) = toolReturning {
            AccessibilitySequenceOutcome(
                1, it,
                AccessibilitySequenceOutcome.Failure(
                    AccessibilitySequenceOutcome.KIND_OPERATOR_INTERVENED, "touched",
                ),
            )
        }
        val result = tool.execute(actions(3), ctx(ToolPermission.ACCESSIBILITY)) {}
        assertTrue(result is ToolResult.Err)
        assertEquals("operator_intervened", (result as ToolResult.Err).code)
    }

    @Test
    fun `step failure maps to step_failed error`() = runTest {
        val (tool, _) = toolReturning {
            AccessibilitySequenceOutcome(
                1, it,
                AccessibilitySequenceOutcome.Failure(
                    AccessibilitySequenceOutcome.KIND_STEP_FAILED, "node not found",
                ),
            )
        }
        val result = tool.execute(actions(2), ctx(ToolPermission.ACCESSIBILITY)) {}
        assertTrue(result is ToolResult.Err)
        assertEquals("step_failed", (result as ToolResult.Err).code)
    }

    @Test
    fun `cap at MAX_ACTIONS (8) steps`() = runTest {
        val (tool, seen) = toolReturning { AccessibilitySequenceOutcome(it, it, null) }
        val result = tool.execute(actions(12), ctx(ToolPermission.ACCESSIBILITY)) {}
        assertTrue(result is ToolResult.Ok)
        assertTrue(
            "dispatched should be capped at ${AccessibilityTool.MAX_ACTIONS}",
            seen.single() <= AccessibilityTool.MAX_ACTIONS,
        )
    }

    @Test
    fun `manifest id and sunset are correct`() {
        val (tool, _) = toolReturning { AccessibilitySequenceOutcome(it, it, null) }
        assertEquals("tool.accessibility", tool.manifest.id)
        assertEquals(90, tool.manifest.sunsetAfterDaysIdle)
    }
}
