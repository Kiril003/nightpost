package local.phantom.companion.core.tool.impl

import android.app.Application
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolEvent
import local.phantom.companion.core.tool.ToolPermission
import local.phantom.companion.core.tool.ToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellToolTest {

    private val tool = ShellTool(termuxAvailable = false)
    // Application() is a valid stub under isReturnDefaultValues=true; ShellTool never calls appContext.
    private val stubContext = Application()

    private fun makeContext(vararg perms: ToolPermission) = ToolContext(
        appContext = stubContext,
        operatorId = "test-operator",
        requestId = "test-request",
        agentDepth = 0,
        approvedPermissions = setOf(*perms),
    )

    @Test
    fun `missing cmd arg returns bad_args error`() = runTest {
        val ctx = makeContext(ToolPermission.SHELL_EXEC)
        val result = tool.execute(buildJsonObject { }, ctx) { }
        assertTrue(result is ToolResult.Err)
        assertEquals("bad_args", (result as ToolResult.Err).code)
    }

    @Test
    fun `echo command returns ok with output containing the echoed string`() = runTest {
        val ctx = makeContext(ToolPermission.SHELL_EXEC)
        val chunks = mutableListOf<String>()

        val result = tool.execute(
            args = buildJsonObject { put("cmd", "echo hi") },
            context = ctx,
            emit = { event ->
                if (event is ToolEvent.Chunk) chunks += event.text
            },
        )

        assertTrue("expected Ok, got $result", result is ToolResult.Ok)
        val ok = result as ToolResult.Ok
        assertTrue("output should contain 'hi'", ok.output.contains("hi"))
        assertTrue("at least one Chunk should be emitted", chunks.isNotEmpty())
        assertTrue("chunk should contain 'hi'", chunks.any { it.contains("hi") })
    }

    @Test
    fun `command with timeout returns timeout error`() = runBlocking {
        // runBlocking (not runTest) so real wall-clock time applies to Dispatchers.IO
        val ctx = makeContext(ToolPermission.SHELL_EXEC)
        val result = tool.execute(
            args = buildJsonObject {
                put("cmd", "sleep 5")
                put("timeoutMs", 500)
            },
            context = ctx,
        ) { }

        assertTrue("expected Err, got $result", result is ToolResult.Err)
        val err = result as ToolResult.Err
        assertEquals("timeout", err.code)
        assertTrue("повідомлення має назвати сам ліміт: ${err.message}", err.message.contains("500"))
    }

    @Test
    fun `termuxAvailable flag changes description`() {
        val withTermux = ShellTool(termuxAvailable = true)
        val withoutTermux = ShellTool(termuxAvailable = false)
        assertTrue(withTermux.description.contains("Termux"))
        assertTrue(!withoutTermux.description.contains("Termux"))
    }

    @Test
    fun `manifest has correct id and sunset`() {
        assertEquals("tool.shell", tool.manifest.id)
        assertEquals(90, tool.manifest.sunsetAfterDaysIdle)
    }
}
