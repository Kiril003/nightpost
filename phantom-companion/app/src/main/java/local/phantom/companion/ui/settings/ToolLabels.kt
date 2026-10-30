package local.phantom.companion.ui.settings

import androidx.annotation.StringRes
import local.phantom.companion.R
import local.phantom.companion.core.tool.Tool

internal object ToolLabels {

    private val BY_ID: Map<String, Int> = mapOf(
        "accessibility" to R.string.tool_label_accessibility,
        "calendar.add" to R.string.tool_label_calendar_add,
        "calendar.remove" to R.string.tool_label_calendar_remove,
        "fence.set" to R.string.tool_label_fence_set,
        "file" to R.string.tool_label_file,
        "hazard.report" to R.string.tool_label_world_report,
        "http" to R.string.tool_label_http,
        "launch" to R.string.tool_label_launch,
        "map.patrol" to R.string.tool_label_map_patrol,
        "map.prefetch" to R.string.tool_label_map_prefetch,
        "medevac.handover" to R.string.tool_label_medevac_handover,
        "media" to R.string.tool_label_media,
        "memory.forget" to R.string.tool_label_memory_forget,
        "nav.add_stop" to R.string.tool_label_nav_add_stop,
        "nav.exfil" to R.string.tool_label_nav_exfil,
        "nav.go" to R.string.tool_label_nav_start,
        "nav.retrace" to R.string.tool_label_nav_retrace,
        "nav.start" to R.string.tool_label_nav_start,
        "nav.stop" to R.string.tool_label_nav_stop,
        "notification" to R.string.tool_label_notification,
        "people.draft" to R.string.tool_label_people_draft,
        "phone.alarm" to R.string.tool_label_phone_alarm,
        "phone.calendar" to R.string.tool_label_phone_calendar,
        "phone.call" to R.string.tool_label_phone_dial,
        "phone.dial" to R.string.tool_label_phone_dial,
        "phone.dnd" to R.string.tool_label_phone_dnd,
        "phone.panel" to R.string.tool_label_phone_panel,
        "phone.sms" to R.string.tool_label_phone_sms,
        "phone.timer" to R.string.tool_label_phone_timer,
        "phone.torch" to R.string.tool_label_phone_torch,
        "place.remove" to R.string.tool_label_place_remove,
        "reminder.remove" to R.string.tool_label_reminder_remove,
        "settings" to R.string.tool_label_settings,
        "shell" to R.string.tool_label_shell,
        "task.remove" to R.string.tool_label_task_remove,
        "track.export" to R.string.tool_label_track_export,
        "track.share" to R.string.tool_label_track_export,
        "ui.act" to R.string.tool_label_accessibility,
        "world.report" to R.string.tool_label_world_report,
    )

    val mappedIds: Set<String> get() = BY_ID.keys

    @StringRes
    fun resId(toolId: String): Int? = BY_ID[toolId]

    fun fallbackLabel(tool: Tool): String? =
        tool.manifest.displayName.trim().takeIf { it.isNotEmpty() && it != tool.id }

    fun hasLabel(tool: Tool): Boolean =
        resId(tool.id) != null || fallbackLabel(tool) != null

    fun labelOrNull(tool: Tool, getString: (Int) -> String): String? =
        resId(tool.id)?.let(getString)?.trim()?.takeIf { it.isNotEmpty() } ?: fallbackLabel(tool)

    fun decidable(tools: List<Tool>): List<Tool> =
        tools.filter { it.risk.needsConsent && !it.askEveryCall && hasLabel(it) }
            .sortedBy { it.id }
}
