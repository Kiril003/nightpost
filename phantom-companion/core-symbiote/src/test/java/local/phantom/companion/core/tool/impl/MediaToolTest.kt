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
 * Unit tests for [MediaTool].
 *
 * Under `isReturnDefaultValues = true`:
 * - Context.getSystemService(MEDIA_SESSION_SERVICE) → null (not a real MediaSessionManager).
 *   → execute returns Err("not_available") for valid action args.
 *
 * This confirms the graceful fallback path. Real device behaviour (sessions
 * present / SecurityException from missing NotificationListener) is covered
 * by integration tests in Phase 1.E.
 */
class MediaToolTest {

    private val tool = MediaTool(Application())

    private fun ctx(vararg perms: ToolPermission) = ToolContext(
        appContext = Application(),
        operatorId = "tester",
        requestId = "req-1",
        approvedPermissions = setOf(*perms),
    )

    // -------------------------------------------------------------------------
    // Permission gate
    // -------------------------------------------------------------------------

    @Test
    fun `missing MEDIA_CONTROL returns PermissionDenied`() = runTest {
        val result = tool.execute(
            buildJsonObject { put("action", "play") },
            ctx(),
        ) {}
        assertTrue(result is ToolResult.PermissionDenied)
        assertEquals(ToolPermission.MEDIA_CONTROL, (result as ToolResult.PermissionDenied).permission)
    }

    // -------------------------------------------------------------------------
    // Bad args
    // -------------------------------------------------------------------------

    @Test
    fun `missing action returns bad_args`() = runTest {
        val result = tool.execute(buildJsonObject { }, ctx(ToolPermission.MEDIA_CONTROL)) {}
        assertTrue(result is ToolResult.Err)
        assertEquals("bad_args", (result as ToolResult.Err).code)
    }

    @Test
    fun `unknown action returns bad_args`() = runTest {
        val result = tool.execute(
            buildJsonObject { put("action", "rewind_30s") },
            ctx(ToolPermission.MEDIA_CONTROL),
        ) {}
        assertTrue(result is ToolResult.Err)
        assertEquals("bad_args", (result as ToolResult.Err).code)
    }

    // -------------------------------------------------------------------------
    // Happy path (JVM stub — MediaSessionManager null → not_available)
    // -------------------------------------------------------------------------

    @Test
    fun `play action with null MediaSessionManager returns not_available`() = runTest {
        // getSystemService returns null under isReturnDefaultValues stub
        val result = tool.execute(
            buildJsonObject { put("action", "play") },
            ctx(ToolPermission.MEDIA_CONTROL),
        ) {}
        assertTrue("expected Err, got $result", result is ToolResult.Err)
        assertEquals("not_available", (result as ToolResult.Err).code)
    }

    @Test
    fun `all supported actions are accepted (bad_args not fired)`() = runTest {
        val supported = listOf("play", "pause", "play_pause", "next", "previous", "stop")
        for (action in supported) {
            val result = tool.execute(
                buildJsonObject { put("action", action) },
                ctx(ToolPermission.MEDIA_CONTROL),
            ) {}
            assertTrue(
                "action '$action' should not return bad_args, got $result",
                result !is ToolResult.Err || (result as ToolResult.Err).code != "bad_args",
            )
        }
    }

    @Test
    fun `manifest id and sunset are correct`() {
        assertEquals("tool.media", tool.manifest.id)
        assertEquals(180, tool.manifest.sunsetAfterDaysIdle)
    }
}
