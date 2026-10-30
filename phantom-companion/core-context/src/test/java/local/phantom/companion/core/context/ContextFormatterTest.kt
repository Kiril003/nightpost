package local.phantom.companion.core.context

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [ContextFormatter]. The output is fed into the AI
 * system prompt — every field needs to render in the expected order so
 * the model receives a stable schema.
 *
 * The time field uses [java.text.SimpleDateFormat] with
 * `Locale.getDefault()`, so we don't pin an exact string; we assert
 * each labelled section appears.
 */
class ContextFormatterTest {

    private fun input(
        battery: Int? = null,
        charging: Boolean = false,
        activeApp: String? = null,
    ) = ContextInput(
        nowMs = 1_700_000_000_000L,
        localHour = 12,
        localDayOfWeek = 3,
        batteryLevel = battery,
        isCharging = charging,
        activeAppPackage = activeApp,
    )

    @Test fun `format includes battery level when present`() {
        val out = ContextFormatter.formatForAi(input(battery = 73), SystemState.FOCUS)
        assertTrue("battery 73 must appear — got:\n$out", "73%" in out)
    }

    @Test fun `missing battery reads as unknown, never as flat`() {
        // Two separate guarantees. The line must render at all (a null level
        // once crashed the non-nullable %d format), and it must not claim 0% —
        // «not yet read» and «about to die» are different facts, and a
        // companion told the second one starts saying so out loud.
        val out = ContextFormatter.formatForAi(input(battery = null), SystemState.FOCUS)
        assertTrue("must still render battery line — got:\n$out", "Заряд:" in out)
        assertTrue("missing battery reads невідомо — got:\n$out", "невідомо" in out)
        assertFalse("must NOT claim a flat battery — got:\n$out", "0%" in out)
    }

    @Test fun `charging flag toggles Ukrainian charging or discharging`() {
        val charging = ContextFormatter.formatForAi(input(charging = true), SystemState.FOCUS)
        assertTrue("заряджається — got:\n$charging", "заряджається" in charging)
        assertFalse("must NOT contain розряджається — got:\n$charging", "розряджається" in charging)

        val discharging = ContextFormatter.formatForAi(input(charging = false), SystemState.FOCUS)
        assertTrue("розряджається — got:\n$discharging", "розряджається" in discharging)
    }

    @Test fun `missing activeAppPackage renders the desktop fallback`() {
        val out = ContextFormatter.formatForAi(input(activeApp = null), SystemState.FOCUS)
        assertTrue(
            "must include desktop / unknown fallback — got:\n$out",
            "робочий стіл" in out,
        )
    }

    @Test fun `present activeAppPackage is rendered verbatim`() {
        val out = ContextFormatter.formatForAi(
            input(activeApp = "com.instagram.android"),
            SystemState.FOCUS,
        )
        assertTrue("must include package — got:\n$out", "com.instagram.android" in out)
    }

    @Test fun `state name renders verbatim from the SystemState enum`() {
        // Lock the state-name surface — any rename of the enum values
        // ripples into the AI system prompt and silently changes
        // model behaviour.
        SystemState.entries.forEach { st ->
            val out = ContextFormatter.formatForAi(input(), st)
            assertTrue("state ${st.name} must appear — got:\n$out", st.name in out)
        }
    }

    @Test fun `template carries the four labelled sections in order`() {
        val out = ContextFormatter.formatForAi(input(), SystemState.FOCUS)
        val labels = listOf("Час:", "Заряд:", "Активний додаток:", "Стан:")
        var cursor = 0
        labels.forEach { label ->
            val idx = out.indexOf(label, startIndex = cursor)
            assertTrue("$label must appear after position $cursor — got:\n$out", idx >= cursor)
            cursor = idx + label.length
        }
    }
}
