package local.phantom.companion.core.data.stream

/*
 * The retention setting is the only clock that removes anything the operator
 * can see.
 *
 * This file used to derive two more clocks from that setting: plumbing left at
 * retention/4 and every widget payload over 2 KB was replaced by a
 * `{"decayed":true}` stub at retention/2. On the default 90 days that emptied
 * canvases, charts, artifacts and code-runner output on day 45 — work the
 * operator made, destroyed a month and a half before the number they were
 * given, with nothing on screen admitting it. Do not reintroduce a second
 * clock over anything that renders.
 *
 * What may still age faster is the app's own plumbing: rows minted to show HOW
 * an answer was produced, never the answer itself.
 */
object ChatDecay {

    // SHRINK is never returned any more. StreamRepository still branches on it,
    // and rows shrunk by earlier builds are still on disk.
    enum class Fate { KEEP, SHRINK, DROP }

    const val PLUMBING_DAYS = 21

    data class Policy(val plumbingDays: Int = PLUMBING_DAYS) {
        // StreamRepository.decay selects its candidates by this cutoff. Nothing
        // younger than the plumbing window can change fate, so it is the same
        // number — one clock, not two that can drift apart.
        val verbatimDays: Int get() = plumbingDays
    }

    private const val DAY_MS = 86_400_000L

    // Traces of how an answer was produced. Both are already withheld from AI
    // history (WidgetHistorySummary.NOT_HISTORY) and collapsed in the timeline.
    // Adding a type here deletes operator data — it needs the same scrutiny as
    // a DELETE statement.
    private val PLUMBING_WIDGETS = setOf("tool-trace", "agent-progress")

    fun fateOf(item: StreamItemEntity, nowMs: Long, policy: Policy = Policy()): Fate {
        if (item.pinned) return Fate.KEEP
        if (!isPlumbing(item)) return Fate.KEEP
        val ageDays = ((nowMs - item.createdAt).coerceAtLeast(0L) / DAY_MS).toInt()
        return if (ageDays >= policy.plumbingDays) Fate.DROP else Fate.KEEP
    }

    fun isPlumbing(item: StreamItemEntity): Boolean =
        item.kind == StreamItemEntity.Kind.WIDGET &&
            item.widgetType.orEmpty().lowercase() in PLUMBING_WIDGETS

    /* Rows shrunk by earlier builds still render through these two. */

    fun digestOf(widgetType: String?, payload: String?): String {
        val title = titleIn(payload)
        return buildString {
            append("{\"decayed\":true")
            append(",\"type\":\"").append(escape(widgetType.orEmpty())).append('"')
            if (!title.isNullOrBlank()) {
                append(",\"title\":\"").append(escape(title)).append('"')
            }
            append(",\"bytes\":").append(payload?.length ?: 0)
            append('}')
        }
    }

    /** Cheap scan for a title/label — no JSON parse for a throwaway digest. */
    fun titleIn(payload: String?): String? {
        if (payload.isNullOrEmpty()) return null
        for (key in arrayOf("\"title\"", "\"label\"", "\"name\"")) {
            val at = payload.indexOf(key)
            if (at < 0) continue
            val colon = payload.indexOf(':', at + key.length)
            if (colon < 0) continue
            val open = payload.indexOf('"', colon + 1)
            if (open < 0) continue
            val close = payload.indexOf('"', open + 1)
            if (close < 0) continue
            val value = payload.substring(open + 1, close)
            if (value.isNotBlank()) return value.take(80)
        }
        return null
    }

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")
}
