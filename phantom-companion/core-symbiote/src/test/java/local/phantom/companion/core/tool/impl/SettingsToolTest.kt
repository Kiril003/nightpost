package local.phantom.companion.core.tool.impl

import android.app.Application
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import local.phantom.companion.core.tool.ToolArgsValidator
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolPermission
import local.phantom.companion.core.tool.ToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SettingsTool].
 *
 * System-service interactions (Settings.System, AudioManager, CameraManager,
 * NotificationManager) all return default/stub values under
 * `isReturnDefaultValues = true`:
 *  - Settings.System.canWrite → false (stubbed) → brightness returns permission_required Err.
 *  - NotificationManager.isNotificationPolicyAccessGranted → false → dnd returns permission_required.
 *  - AudioManager.getStreamMaxVolume → 0 → volume still returns Ok (raw=0).
 *  - CameraManager.cameraIdList → empty array → flashlight returns not_available.
 *
 * These stubs are load-bearing for the tests — they validate the tool's
 * fallback paths on a JVM without real Android framework support.
 */
class SettingsToolTest {

    private val tool = SettingsTool(Application())

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
    fun `missing SETTINGS_WRITE returns PermissionDenied`() = runTest {
        val result = tool.execute(
            buildJsonObject { put("action", "brightness"); put("value", "0.5") },
            ctx(),
        ) {}
        assertTrue(result is ToolResult.PermissionDenied)
        assertEquals(ToolPermission.SETTINGS_WRITE, (result as ToolResult.PermissionDenied).permission)
    }

    // -------------------------------------------------------------------------
    // Bad args
    // -------------------------------------------------------------------------

    @Test
    fun `missing action returns bad_args`() = runTest {
        val result = tool.execute(buildJsonObject { }, ctx(ToolPermission.SETTINGS_WRITE)) {}
        assertTrue(result is ToolResult.Err)
        assertEquals("bad_args", (result as ToolResult.Err).code)
    }

    @Test
    fun `unknown action returns bad_args`() = runTest {
        val result = tool.execute(
            buildJsonObject { put("action", "self_destruct") },
            ctx(ToolPermission.SETTINGS_WRITE),
        ) {}
        assertTrue(result is ToolResult.Err)
        assertEquals("bad_args", (result as ToolResult.Err).code)
    }

    @Test
    fun `brightness with non-float value returns bad_args`() = runTest {
        val result = tool.execute(
            buildJsonObject { put("action", "brightness"); put("value", "bright") },
            ctx(ToolPermission.SETTINGS_WRITE),
        ) {}
        assertTrue(result is ToolResult.Err)
        assertEquals("bad_args", (result as ToolResult.Err).code)
    }

    @Test
    fun `brightness out of range returns bad_args`() = runTest {
        val result = tool.execute(
            buildJsonObject { put("action", "brightness"); put("value", "1.5") },
            ctx(ToolPermission.SETTINGS_WRITE),
        ) {}
        assertTrue(result is ToolResult.Err)
        assertEquals("bad_args", (result as ToolResult.Err).code)
    }

    // -------------------------------------------------------------------------
    // Happy paths (as far as JVM stubs allow)
    // -------------------------------------------------------------------------

    @Test
    fun `brightness when canWrite is false returns permission_required err`() = runTest {
        // Under isReturnDefaultValues=true, Settings.System.canWrite → false
        val result = tool.execute(
            buildJsonObject { put("action", "brightness"); put("value", "0.5") },
            ctx(ToolPermission.SETTINGS_WRITE),
        ) {}
        assertTrue(result is ToolResult.Err)
        assertEquals("permission_required", (result as ToolResult.Err).code)
    }

    @Test
    fun `dnd when NotificationManager unavailable returns err`() = runTest {
        // Under isReturnDefaultValues=true, getSystemService returns null → as? returns null
        // → not_available Err (system service unavailable in JVM unit test context)
        val result = tool.execute(
            buildJsonObject { put("action", "dnd"); put("value", "none") },
            ctx(ToolPermission.SETTINGS_WRITE),
        ) {}
        assertTrue(result is ToolResult.Err)
        // Either not_available (null stub) or permission_required (real NotificationManager)
        val code = (result as ToolResult.Err).code
        assertTrue("expected not_available or permission_required, got $code",
            code == "not_available" || code == "permission_required")
    }

    @Test
    fun `dnd with invalid value returns err`() = runTest {
        val result = tool.execute(
            buildJsonObject { put("action", "dnd"); put("value", "chaos") },
            ctx(ToolPermission.SETTINGS_WRITE),
        ) {}
        // bad_args (value invalid) or not_available (null stub service) — both are Err
        assertTrue(result is ToolResult.Err)
    }

    @Test
    fun `volume with unknown stream returns bad_args`() = runTest {
        // bad_args fires before AudioManager lookup — stream validation is first
        val result = tool.execute(
            buildJsonObject {
                put("action", "volume")
                put("stream", "unknown_stream")
                put("value", "0.5")
            },
            ctx(ToolPermission.SETTINGS_WRITE),
        ) {}
        assertTrue(result is ToolResult.Err)
        assertEquals("bad_args", (result as ToolResult.Err).code)
    }

    @Test
    fun `volume with valid args returns err under stub (AudioManager null)`() = runTest {
        // Under isReturnDefaultValues=true getSystemService returns null → as? null → not_available
        val result = tool.execute(
            buildJsonObject {
                put("action", "volume")
                put("stream", "media")
                put("value", "0.5")
            },
            ctx(ToolPermission.SETTINGS_WRITE),
        ) {}
        // On a real device: Ok. On JVM stub: not_available. Both are valid outcomes per env.
        assertTrue(result is ToolResult.Ok || result is ToolResult.Err)
        if (result is ToolResult.Err) {
            assertEquals("not_available", (result as ToolResult.Err).code)
        }
    }

    @Test
    fun `flashlight returns not_available err under stub (CameraManager null or empty)`() = runTest {
        // Under isReturnDefaultValues=true: CameraManager is null → not_available
        val result = tool.execute(
            buildJsonObject { put("action", "flashlight"); put("value", "true") },
            ctx(ToolPermission.SETTINGS_WRITE),
        ) {}
        assertTrue(result is ToolResult.Err)
        val code = (result as ToolResult.Err).code
        assertTrue("expected not_available or camera_error, got $code",
            code == "not_available" || code == "camera_error")
    }

    @Test
    fun `airplane_mode always returns not_supported`() = runTest {
        val result = tool.execute(
            buildJsonObject { put("action", "airplane_mode") },
            ctx(ToolPermission.SETTINGS_WRITE),
        ) {}
        assertTrue(result is ToolResult.Err)
        assertEquals("not_supported", (result as ToolResult.Err).code)
    }

    @Test
    fun `manifest id and sunset are correct`() {
        assertEquals("tool.settings", tool.manifest.id)
        assertEquals(120, tool.manifest.sunsetAfterDaysIdle)
    }

    /**
     * Чипи Скрині «тихіше»/«гучніше» шлють {action: volume, delta: ±0.15} без
     * `value`, і [SettingsTool.handleVolume] саме це й приймає. Але схема
     * оголошувала `value` беззастережно обовʼязковим, тож [ToolArgsValidator]
     * відсікав виклик ДО обробника: інструмент «не запускався», 0 мс, а гучність
     * не рухалась жодного разу. Вада була не в дії, а в дверях перед нею.
     */
    @Test
    fun `гучність за самою лише дельтою проходить перевірку аргументів`() {
        val call = buildJsonObject {
            put("action", "volume")
            put("stream", "media")
            put("delta", -0.15)
        }
        assertEquals(
            "виклик без value відкинуто ще до обробника — чипи гучності мертві",
            emptyList<String>(),
            ToolArgsValidator.validate(tool.argsSchema, call),
        )
    }

    /**
     * Послаблення не має ставати діркою: решта дій вимагають `value`, і тепер
     * це вимога САМИХ обробників. Якщо вона зникне, інструмент почне мовчки
     * діяти на порожньому аргументі.
     */
    @Test
    fun `решта дій без value відмовляють з поясненням`() = runTest {
        listOf("brightness", "dnd", "flashlight").forEach { action ->
            val res = tool.execute(
                buildJsonObject { put("action", action) },
                ctx(ToolPermission.SETTINGS_WRITE),
            ) {}
            assertTrue(
                "$action без value мусить відмовити зрозуміло, а не діяти: $res",
                res is ToolResult.Err || res is ToolResult.PermissionDenied,
            )
        }
    }
}
