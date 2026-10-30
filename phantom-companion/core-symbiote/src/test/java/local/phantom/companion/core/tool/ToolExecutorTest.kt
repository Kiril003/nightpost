package local.phantom.companion.core.tool

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import local.phantom.companion.core.constitution.FeatureManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the hardened-shell contract: a hung tool times out instead of
 * freezing the chain, a flapping tool trips its breaker instead of
 * re-burning steps, thrown exceptions become typed Err, and
 * health-neutral outcomes (denied / cancelled) never poison the breaker.
 */
class ToolExecutorTest {

    private fun manifest(id: String) = FeatureManifest(
        id = id,
        tier = 14,
        displayName = "Test tool $id",
    )

    private fun tool(
        id: String = "t",
        streaming: Boolean = false,
        body: suspend () -> ToolResult,
    ): Tool = object : Tool {
        override val id = id
        override val description = "tool $id"
        override val argsSchema = JsonObject(emptyMap())
        override val permissions = emptySet<ToolPermission>()
        override val risk: ToolRisk = ToolRisk.READ
        override val streaming = streaming
        override val manifest = manifest(id)
        override suspend fun execute(
            args: JsonObject,
            context: ToolContext,
            emit: suspend (ToolEvent) -> Unit,
        ): ToolResult = body()
    }

    private val noArgs = JsonObject(emptyMap())
    private val ctx = ToolContext(
        appContext = android.app.Application(),
        operatorId = "operator",
        requestId = "req",
        agentDepth = 0,
        approvedPermissions = emptySet(),
    )

    @Test
    fun `ok result passes through untouched`() = runTest {
        val executor = ToolExecutor()
        val result = executor.run(
            tool { ToolResult.Ok("done", null, 5L) }, noArgs, ctx,
        ) {}
        assertEquals(ToolResult.Ok("done", null, 5L), result)
    }

    @Test
    fun `hung tool is cut by the watchdog, not awaited forever`() = runTest {
        val executor = ToolExecutor()
        val result = executor.run(
            tool {
                delay(ToolExecutor.DEFAULT_TIMEOUT_MS * 2)
                ToolResult.Ok("never", null, 0L)
            },
            noArgs, ctx,
        ) {}
        assertTrue(result is ToolResult.Err)
        assertEquals(ToolExecutor.CODE_TIMEOUT, (result as ToolResult.Err).code)
    }

    @Test
    fun `streaming tool gets the long budget`() = runTest {
        val executor = ToolExecutor()
        val result = executor.run(
            tool(streaming = true) {
                // Longer than the default budget, within the slow one —
                // a deep-research run must NOT be cut at the default mark.
                delay(ToolExecutor.DEFAULT_TIMEOUT_MS * 2)
                ToolResult.Ok("research done", null, 0L)
            },
            noArgs, ctx,
        ) {}
        assertTrue(result is ToolResult.Ok)
    }

    @Test
    fun `thrown exception becomes typed Err`() = runTest {
        val executor = ToolExecutor()
        val result = executor.run(
            tool { throw IllegalStateException("boom") }, noArgs, ctx,
        ) {}
        assertTrue(result is ToolResult.Err)
        assertEquals(ToolExecutor.CODE_EXCEPTION, (result as ToolResult.Err).code)
        assertEquals("boom", result.message)
    }

    @Test
    fun `breaker opens after consecutive failures and short-circuits`() = runTest {
        var now = 0L
        var executions = 0
        val executor = ToolExecutor(clock = { now })
        val flapping = tool {
            executions++
            ToolResult.Err("net", "down", 1L)
        }
        repeat(ToolExecutor.TRIP_THRESHOLD) { executor.run(flapping, noArgs, ctx) {} }
        assertTrue(executor.isOpen("t"))

        val shortCircuited = executor.run(flapping, noArgs, ctx) {}
        assertEquals(ToolExecutor.TRIP_THRESHOLD, executions)
        assertTrue(shortCircuited is ToolResult.Err)
        assertEquals(
            ToolExecutor.CODE_CIRCUIT_OPEN,
            (shortCircuited as ToolResult.Err).code,
        )

        // Cooldown elapses → one probe goes through; success closes it.
        now += ToolExecutor.COOLDOWN_MS
        val probe = executor.run(
            tool { ToolResult.Ok("recovered", null, 1L) }, noArgs, ctx,
        ) {}
        assertTrue(probe is ToolResult.Ok)
        assertFalse(executor.isOpen("t"))
    }

    @Test
    fun `failed probe re-opens the breaker for another cooldown`() = runTest {
        var now = 0L
        val executor = ToolExecutor(clock = { now })
        val broken = tool { ToolResult.Err("net", "down", 1L) }
        repeat(ToolExecutor.TRIP_THRESHOLD) { executor.run(broken, noArgs, ctx) {} }
        now += ToolExecutor.COOLDOWN_MS
        executor.run(broken, noArgs, ctx) {} // probe fails
        assertTrue(executor.isOpen("t"))
    }

    @Test
    fun `denied and cancelled outcomes never poison the breaker`() = runTest {
        val executor = ToolExecutor()
        val neutral = tool { ToolResult.Cancelled(1L) }
        repeat(ToolExecutor.TRIP_THRESHOLD * 2) { executor.run(neutral, noArgs, ctx) {} }
        assertFalse(executor.isOpen("t"))
    }

    @Test
    fun `breakers are per-tool, one broken tool does not dim the rest`() = runTest {
        val executor = ToolExecutor()
        val broken = tool(id = "broken") { ToolResult.Err("x", "y", 1L) }
        repeat(ToolExecutor.TRIP_THRESHOLD) { executor.run(broken, noArgs, ctx) {} }
        assertTrue(executor.isOpen("broken"))

        val healthy = tool(id = "healthy") { ToolResult.Ok("fine", null, 1L) }
        assertTrue(executor.run(healthy, noArgs, ctx) {} is ToolResult.Ok)
    }
}
