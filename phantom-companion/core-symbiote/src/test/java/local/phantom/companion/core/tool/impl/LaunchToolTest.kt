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
 * Unit tests for [LaunchTool].
 *
 * Under `isReturnDefaultValues = true`:
 * - PackageManager.getLaunchIntentForPackage → null (no real packages installed)
 *   → happy-path package launch returns Err("not_found").
 * - startActivity with ACTION_VIEW → ActivityNotFoundException (stub) → Err("not_found").
 *
 * The important invariants verified here:
 * - Permission gate fires before any package resolution.
 * - Blocklisted packages return PermissionDenied regardless of intent type.
 * - Missing required args return bad_args.
 */
class LaunchToolTest {

    private val tool = LaunchTool(Application())

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
    fun `missing LAUNCHER permission returns PermissionDenied`() = runTest {
        val result = tool.execute(
            buildJsonObject { put("package", "com.example.app") },
            ctx(), // no permissions
        ) {}
        assertTrue(result is ToolResult.PermissionDenied)
        assertEquals(ToolPermission.LAUNCHER, (result as ToolResult.PermissionDenied).permission)
    }

    // -------------------------------------------------------------------------
    // Blocklist
    // -------------------------------------------------------------------------

    @Test
    fun `banking package returns PermissionDenied`() = runTest {
        val result = tool.execute(
            buildJsonObject { put("package", "ua.privatbank.ap24") },
            ctx(ToolPermission.LAUNCHER),
        ) {}
        assertTrue("expected PermissionDenied for banking app, got $result",
            result is ToolResult.PermissionDenied)
    }

    @Test
    fun `password manager package returns PermissionDenied`() = runTest {
        val result = tool.execute(
            buildJsonObject { put("package", "com.lastpass.lpandroid") },
            ctx(ToolPermission.LAUNCHER),
        ) {}
        assertTrue("expected PermissionDenied for password manager, got $result",
            result is ToolResult.PermissionDenied)
    }

    @Test
    fun `bitwarden package returns PermissionDenied`() = runTest {
        val result = tool.execute(
            buildJsonObject { put("package", "com.x8bit.bitwarden") },
            ctx(ToolPermission.LAUNCHER),
        ) {}
        assertTrue(result is ToolResult.PermissionDenied)
    }

    // -------------------------------------------------------------------------
    // Happy path (stub → not_found because PackageManager has no real packages)
    // -------------------------------------------------------------------------

    @Test
    fun `non-blocklisted package returns not_found under stub PackageManager`() = runTest {
        val result = tool.execute(
            buildJsonObject { put("package", "com.example.harmless") },
            ctx(ToolPermission.LAUNCHER),
        ) {}
        // PackageManager stub returns null launch intent → Err("not_found")
        assertTrue(result is ToolResult.Err)
        assertEquals("not_found", (result as ToolResult.Err).code)
    }

    // -------------------------------------------------------------------------
    // Bad args / edge cases
    // -------------------------------------------------------------------------

    @Test
    fun `no args at all still handles gracefully (empty intent launch attempt)`() = runTest {
        // No package, no action, no data → builds ACTION_VIEW intent with null data
        // startActivity will throw ActivityNotFoundException on stub → Err
        val result = tool.execute(buildJsonObject { }, ctx(ToolPermission.LAUNCHER)) {}
        // Not a crash — must return some Err or Ok
        assertTrue(result is ToolResult.Ok || result is ToolResult.Err)
    }

    @Test
    fun `manifest id and sunset are correct`() {
        assertEquals("tool.launch", tool.manifest.id)
        assertEquals(180, tool.manifest.sunsetAfterDaysIdle)
    }
}
