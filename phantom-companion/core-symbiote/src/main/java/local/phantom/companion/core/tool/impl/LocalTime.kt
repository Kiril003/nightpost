package local.phantom.companion.core.tool.impl

/**
 * Wall-clock text → epoch, resolved on the device.
 *
 * The model is unreliable at timezone arithmetic: asked for «18:00 today»
 * it sent an epoch meaning 21:00 Kyiv, and after being handed the UTC
 * offset explicitly, 20:00. The phone owns the clock and the zone, so it
 * does the conversion and the model only repeats what the operator said.
 *
 * Accepts `HH:mm` (relative to [nowMs] — the next occurrence of that
 * wall-clock time) and `yyyy-MM-dd HH:mm`. Null when the text is neither.
 */
internal fun resolveLocalTime(raw: String, nowMs: Long = System.currentTimeMillis()): Long? {
    val s = raw.trim()
    val cal = java.util.Calendar.getInstance()
    Regex("""^(\d{1,2})[:.](\d{2})$""").find(s)?.let { m ->
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        if (h > 23 || min > 59) return null
        cal.timeInMillis = nowMs
        cal.set(java.util.Calendar.HOUR_OF_DAY, h)
        cal.set(java.util.Calendar.MINUTE, min)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        if (cal.timeInMillis <= nowMs) cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }
    val full = Regex("""^(\d{4})-(\d{1,2})-(\d{1,2})[ T](\d{1,2})[:.](\d{2})$""").find(s) ?: return null
    val (y, mo, d, h, min) = full.destructured
    if (h.toInt() > 23 || min.toInt() > 59 || mo.toInt() !in 1..12 || d.toInt() !in 1..31) return null
    cal.clear()
    cal.set(y.toInt(), mo.toInt() - 1, d.toInt(), h.toInt(), min.toInt())
    return cal.timeInMillis
}

/**
 * Rows the operator could plausibly mean by [needle]. Ids are UUIDs the
 * operator never sees, so every removal verb also takes the words on the
 * card; an exact label beats a substring so «хліб» does not drag
 * «хліб чорний» into an ambiguity question.
 */
internal fun <T> matchByText(rows: List<T>, needle: String, label: (T) -> String): List<T> {
    val n = needle.trim().lowercase()
    if (n.isEmpty()) return emptyList()
    val exact = rows.filter { label(it).trim().lowercase() == n }
    return exact.ifEmpty { rows.filter { label(it).lowercase().contains(n) } }
}
