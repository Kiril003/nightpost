package local.phantom.companion.core.tool.impl

import android.app.Application
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolPermission
import local.phantom.companion.core.tool.ToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [NotificationTool].
 *
 * Under `isReturnDefaultValues = true`:
 * - NotificationManager.getNotificationChannel → null (channel always absent)
 *   → ensureDefaultChannelExists always calls createNotificationChannel (no-op stub).
 * - NotificationManagerCompat.notify → no-op stub.
 *
 * Happy path therefore succeeds and returns an Ok with a notificationId field.
 */
class NotificationToolTest {

    private val tool = NotificationTool(Application(), smallIconRes = 1, accentColor = 0xFFF4AF25.toInt())

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
    fun `missing NOTIFICATION_POST returns PermissionDenied`() = runTest {
        val result = tool.execute(
            buildJsonObject { put("title", "Hello") },
            ctx(),
        ) {}
        assertTrue(result is ToolResult.PermissionDenied)
        assertEquals(
            ToolPermission.NOTIFICATION_POST,
            (result as ToolResult.PermissionDenied).permission,
        )
    }

    // -------------------------------------------------------------------------
    // Bad args
    // -------------------------------------------------------------------------

    @Test
    fun `missing title returns bad_args`() = runTest {
        val result = tool.execute(buildJsonObject { }, ctx(ToolPermission.NOTIFICATION_POST)) {}
        assertTrue(result is ToolResult.Err)
        assertEquals("bad_args", (result as ToolResult.Err).code)
    }

    // -------------------------------------------------------------------------
    // Happy path
    // -------------------------------------------------------------------------

    @Test
    fun `valid title returns Ok with notificationId`() = runTest {
        val result = tool.execute(
            buildJsonObject {
                put("title", "Agent update")
                put("body", "Task completed successfully.")
                put("priority", "high")
            },
            ctx(ToolPermission.NOTIFICATION_POST),
        ) {}
        assertTrue("expected Ok, got $result", result is ToolResult.Ok)
        val ok = result as ToolResult.Ok
        assertNotNull("structured must be non-null", ok.structured)
        val notifId = ok.structured!!["notificationId"]
        assertNotNull("notificationId must be present in structured", notifId)
    }

    @Test
    fun `default channel id is used when channelId omitted`() = runTest {
        val result = tool.execute(
            buildJsonObject { put("title", "Test") },
            ctx(ToolPermission.NOTIFICATION_POST),
        ) {}
        assertTrue(result is ToolResult.Ok)
        val channelId = (result as ToolResult.Ok).structured!!["channelId"]?.toString()
            ?.trim('"')
        assertEquals(NotificationTool.DEFAULT_CHANNEL_ID, channelId)
    }

    @Test
    fun `custom channelId is reflected in structured output`() = runTest {
        val result = tool.execute(
            buildJsonObject {
                put("title", "Custom channel")
                put("channelId", "my_custom_channel")
            },
            ctx(ToolPermission.NOTIFICATION_POST),
        ) {}
        assertTrue(result is ToolResult.Ok)
        val channelId = (result as ToolResult.Ok).structured!!["channelId"]?.toString()
            ?.trim('"')
        assertEquals("my_custom_channel", channelId)
    }

    @Test
    fun `manifest id and sunset are correct`() {
        assertEquals("tool.notification", tool.manifest.id)
        assertEquals(180, tool.manifest.sunsetAfterDaysIdle)
    }
}
