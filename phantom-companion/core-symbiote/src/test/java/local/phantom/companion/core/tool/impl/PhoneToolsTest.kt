package local.phantom.companion.core.tool.impl

import android.app.Application
import android.content.Intent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolPermission
import local.phantom.companion.core.tool.ToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Calendar

/**
 * R5 Device Hands — the pure `parse*` validation matrix plus the
 * execute-level permission/launch contract. Intent extras are invisible
 * under `isReturnDefaultValues = true`, so the framework half is asserted
 * only as "launcher invoked exactly once + honest Ok text" — the argument
 * semantics live entirely in the parse functions, which are fully covered.
 */
class PhoneToolsTest {

    private fun ctx(vararg perms: ToolPermission) = ToolContext(
        appContext = Application(),
        operatorId = "tester",
        requestId = "req-1",
        approvedPermissions = setOf(*perms),
    )

    // ── parseAlarmArgs ──────────────────────────────────────────────────

    @Test
    fun `alarm parse happy path`() {
        val p = parseAlarmArgs(
            buildJsonObject {
                put("hour", 7); put("minute", 30); put("message", "підйом")
            },
        )
        val spec = (p as Parsed.Ok).value
        assertEquals(7, spec.hour)
        assertEquals(30, spec.minute)
        assertEquals("підйом", spec.message)
        assertTrue(spec.days.isEmpty())
    }

    @Test
    fun `alarm minute defaults to 0`() {
        val spec = (parseAlarmArgs(buildJsonObject { put("hour", 7) }) as Parsed.Ok).value
        assertEquals(0, spec.minute)
        assertNull(spec.message)
    }

    @Test
    fun `alarm missing hour is bad`() {
        assertTrue(parseAlarmArgs(buildJsonObject { put("minute", 5) }) is Parsed.Bad)
    }

    @Test
    fun `alarm hour and minute bounds are enforced`() {
        assertTrue(parseAlarmArgs(buildJsonObject { put("hour", 24) }) is Parsed.Bad)
        assertTrue(parseAlarmArgs(buildJsonObject { put("hour", -1) }) is Parsed.Bad)
        assertTrue(
            parseAlarmArgs(buildJsonObject { put("hour", 7); put("minute", 60) }) is Parsed.Bad,
        )
    }

    @Test
    fun `alarm days accept english and ukrainian tokens`() {
        val p = parseAlarmArgs(
            buildJsonObject {
                put("hour", 7)
                put("days", buildJsonArray { add("mon"); add("пт"); add("нд") })
            },
        )
        val spec = (p as Parsed.Ok).value
        assertEquals(listOf(Calendar.MONDAY, Calendar.FRIDAY, Calendar.SUNDAY), spec.days)
    }

    @Test
    fun `alarm unknown day token is bad`() {
        val p = parseAlarmArgs(
            buildJsonObject {
                put("hour", 7)
                put("days", buildJsonArray { add("someday") })
            },
        )
        assertTrue(p is Parsed.Bad)
    }

    // ── parseTimerArgs ──────────────────────────────────────────────────

    @Test
    fun `timer minutes and seconds combine`() {
        val spec = (parseTimerArgs(buildJsonObject { put("minutes", 2); put("seconds", 30) }) as Parsed.Ok).value
        assertEquals(150, spec.lengthSec)
    }

    @Test
    fun `timer seconds only works`() {
        val spec = (parseTimerArgs(buildJsonObject { put("seconds", 45) }) as Parsed.Ok).value
        assertEquals(45, spec.lengthSec)
    }

    @Test
    fun `timer without length is bad`() {
        assertTrue(parseTimerArgs(buildJsonObject { put("message", "чай") }) is Parsed.Bad)
    }

    @Test
    fun `timer zero and over 24h are bad`() {
        assertTrue(parseTimerArgs(buildJsonObject { put("seconds", 0) }) is Parsed.Bad)
        assertTrue(parseTimerArgs(buildJsonObject { put("minutes", 24 * 60 + 1) }) is Parsed.Bad)
    }

    // ── parseDialArgs / parseSmsArgs ────────────────────────────────────

    @Test
    fun `dial strips formatting`() {
        val spec = (parseDialArgs(buildJsonObject { put("number", "+38 (067) 123-45-67") }) as Parsed.Ok).value
        assertEquals("+380671234567", spec.number)
    }

    @Test
    fun `dial keeps ussd symbols`() {
        val spec = (parseDialArgs(buildJsonObject { put("number", "*111#") }) as Parsed.Ok).value
        assertEquals("*111#", spec.number)
    }

    @Test
    fun `dial rejects non-numbers`() {
        assertTrue(parseDialArgs(buildJsonObject { put("number", "мама") }) is Parsed.Bad)
        assertTrue(parseDialArgs(buildJsonObject {}) is Parsed.Bad)
    }

    @Test
    fun `sms parse keeps body and cleans number`() {
        val spec = (
            parseSmsArgs(
                buildJsonObject { put("number", "067 123 45 67"); put("text", "буду о 8") },
            ) as Parsed.Ok
            ).value
        assertEquals("0671234567", spec.number)
        assertEquals("буду о 8", spec.body)
    }

    // ── parseCalendarArgs ───────────────────────────────────────────────

    private val zone = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 7, 4)

    @Test
    fun `calendar full datetime with default duration`() {
        val p = parseCalendarArgs(
            buildJsonObject { put("title", "зустріч"); put("start", "2026-07-05T15:00") },
            zone = zone, today = today,
        )
        val spec = (p as Parsed.Ok).value
        val begin = LocalDateTime.of(2026, 7, 5, 15, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(begin, spec.beginMs)
        assertEquals(begin + 60 * 60_000, spec.endMs)
        assertTrue(!spec.allDay)
    }

    @Test
    fun `calendar space separator and custom duration`() {
        val p = parseCalendarArgs(
            buildJsonObject {
                put("title", "дзвінок"); put("start", "2026-07-05 09:30"); put("duration_min", 15)
            },
            zone = zone, today = today,
        )
        val spec = (p as Parsed.Ok).value
        assertEquals(spec.beginMs!! + 15 * 60_000, spec.endMs)
    }

    @Test
    fun `calendar bare date becomes all-day`() {
        val p = parseCalendarArgs(
            buildJsonObject { put("title", "відпустка"); put("start", "2026-08-01") },
            zone = zone, today = today,
        )
        val spec = (p as Parsed.Ok).value
        assertTrue(spec.allDay)
        assertNull(spec.endMs)
    }

    @Test
    fun `calendar bare time lands on today`() {
        val p = parseCalendarArgs(
            buildJsonObject { put("title", "чай"); put("start", "18:00") },
            zone = zone, today = today,
        )
        val spec = (p as Parsed.Ok).value
        val begin = LocalDateTime.of(2026, 7, 4, 18, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(begin, spec.beginMs)
    }

    @Test
    fun `calendar without start is a valid open draft`() {
        val spec = (parseCalendarArgs(buildJsonObject { put("title", "щось") }, zone, today) as Parsed.Ok).value
        assertNull(spec.beginMs)
        assertNull(spec.endMs)
    }

    @Test
    fun `calendar bad start and missing title are bad`() {
        assertTrue(
            parseCalendarArgs(
                buildJsonObject { put("title", "x"); put("start", "завтра о 15") }, zone, today,
            ) is Parsed.Bad,
        )
        assertTrue(parseCalendarArgs(buildJsonObject { put("start", "18:00") }, zone, today) is Parsed.Bad)
    }

    // ── parsePanelArgs ──────────────────────────────────────────────────

    @Test
    fun `panel tokens map to kinds`() {
        assertEquals(PanelKind.WIFI, (parsePanelArgs(buildJsonObject { put("panel", "Wi-Fi") }) as Parsed.Ok).value)
        assertEquals(PanelKind.BLUETOOTH, (parsePanelArgs(buildJsonObject { put("panel", "bt") }) as Parsed.Ok).value)
        assertEquals(PanelKind.VOLUME, (parsePanelArgs(buildJsonObject { put("panel", "volume") }) as Parsed.Ok).value)
        assertTrue(parsePanelArgs(buildJsonObject { put("panel", "5g") }) is Parsed.Bad)
        assertTrue(parsePanelArgs(buildJsonObject {}) is Parsed.Bad)
    }

    // ── execute-level contract ──────────────────────────────────────────

    @Test
    fun `alarm without LAUNCHER permission is denied`() = runTest {
        val launched = mutableListOf<Intent>()
        val result = PhoneAlarmTool { launched += it }.execute(
            buildJsonObject { put("hour", 7) }, ctx(),
        ) {}
        assertTrue(result is ToolResult.PermissionDenied)
        assertTrue(launched.isEmpty())
    }

    @Test
    fun `alarm happy path launches once and reports time`() = runTest {
        val launched = mutableListOf<Intent>()
        val result = PhoneAlarmTool { launched += it }.execute(
            buildJsonObject { put("hour", 7); put("message", "підйом") },
            ctx(ToolPermission.LAUNCHER),
        ) {}
        assertTrue(result is ToolResult.Ok)
        assertEquals(1, launched.size)
        assertTrue((result as ToolResult.Ok).output.contains("07:00"))
        assertTrue(result.output.contains("підйом"))
    }

    @Test
    fun `alarm bad args never launches`() = runTest {
        val launched = mutableListOf<Intent>()
        val result = PhoneAlarmTool { launched += it }.execute(
            buildJsonObject { put("hour", 99) }, ctx(ToolPermission.LAUNCHER),
        ) {}
        assertEquals("bad_args", (result as ToolResult.Err).code)
        assertTrue(launched.isEmpty())
    }

    @Test
    fun `timer happy path reports pretty length and when it fires`() = runTest {
        val launched = mutableListOf<Intent>()
        val startedAt = 1_754_000_000_000L
        // launch is named: `nowMs` is the last parameter, so a trailing lambda
        // binds to the clock, not to the launcher.
        val result = PhoneTimerTool(launch = { launched += it }, nowMs = { startedAt }).execute(
            buildJsonObject { put("minutes", 20) }, ctx(ToolPermission.LAUNCHER),
        ) {}
        assertTrue((result as ToolResult.Ok).output.contains("20 хв"))
        assertEquals(1, launched.size)
        assertEquals(
            JsonPrimitive(startedAt + 20 * 60_000L),
            result.structured?.get("ends_at_ms"),
        )
    }

    @Test
    fun `launcher exceptions surface as honest errors`() = runTest {
        val result = PhoneDialTool { throw android.content.ActivityNotFoundException("no dialer") }
            .execute(buildJsonObject { put("number", "0671234567") }, ctx(ToolPermission.LAUNCHER)) {}
        assertEquals("no_dialer", (result as ToolResult.Err).code)
    }

    @Test
    fun `sms and calendar and panel launch once on happy path`() = runTest {
        val launched = mutableListOf<Intent>()
        val sms = PhoneSmsTool { launched += it }.execute(
            buildJsonObject { put("number", "0671234567"); put("text", "привіт") },
            ctx(ToolPermission.LAUNCHER),
        ) {}
        val cal = PhoneCalendarTool { launched += it }.execute(
            buildJsonObject { put("title", "зустріч"); put("start", "2026-07-05T15:00") },
            ctx(ToolPermission.LAUNCHER),
        ) {}
        val panel = PhonePanelTool { launched += it }.execute(
            buildJsonObject { put("panel", "wifi") }, ctx(ToolPermission.LAUNCHER),
        ) {}
        assertTrue(sms is ToolResult.Ok)
        assertTrue(cal is ToolResult.Ok)
        assertTrue(panel is ToolResult.Ok)
        assertEquals(3, launched.size)
    }

    @Test
    fun `manifests are tier 1 stream tools`() {
        val tools = listOf(
            PhoneAlarmTool {}, PhoneTimerTool(launch = {}), PhoneDialTool {},
            PhoneSmsTool {}, PhoneCalendarTool {}, PhonePanelTool {},
        )
        tools.forEach { tool ->
            assertEquals(1, tool.manifest.tier)
            assertTrue(tool.manifest.id.startsWith("tool.phone."))
            assertTrue(tool.permissions.contains(ToolPermission.LAUNCHER))
        }
    }
}
