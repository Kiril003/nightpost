package local.phantom.companion.ui.home

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** «до коли» for a muted chat: the clock if it ends today, day + clock otherwise; null = until unmuted. */
internal fun hushEndText(endMs: Long?, nowMs: Long, zone: ZoneId, locale: Locale): String? {
    if (endMs == null) return null
    val end = Instant.ofEpochMilli(endMs).atZone(zone)
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val pattern = if (end.toLocalDate() == today) "HH:mm" else "d MMM, HH:mm"
    return DateTimeFormatter.ofPattern(pattern, locale).format(end)
}
