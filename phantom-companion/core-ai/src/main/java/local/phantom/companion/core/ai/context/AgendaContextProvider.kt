package local.phantom.companion.core.ai.context

/**
 * Today's schedule, as the operator's own calendar holds it.
 *
 * ДЕНЬ can show the schedule since the calendar bridge landed, but the model
 * answering «сплануй день» could not see it — so it planned around nothing and
 * sounded confident doing it.
 */
fun interface AgendaContextProvider {
    /** Entries overlapping today, soonest first; empty when refused or free. */
    fun current(): List<AgendaEntry>
}

/** One calendar entry, already reduced to what a prompt can use. */
data class AgendaEntry(
    val title: String,
    /** «14:05», or null for an all-day entry, which has no hour. */
    val clock: String?,
    val location: String?,
)

/**
 * `[AGENDA:...]` for the prompt preamble.
 *
 * The three states are deliberately distinct. A refused calendar and an empty
 * day are not the same fact, and a model told «none» for both will confidently
 * plan over a meeting it was never shown.
 */
object AgendaContextFormatter {

    fun format(entries: List<AgendaEntry>?): String = when {
        entries == null -> "[AGENDA:unavailable]"
        entries.isEmpty() -> "[AGENDA:empty]"
        else -> entries.joinToString(
            separator = "; ",
            prefix = "[AGENDA:",
            postfix = "]",
        ) { e ->
            buildString {
                append(e.clock ?: "весь день")
                append(' ')
                append(e.title)
                e.location?.let { append(" @").append(it) }
            }
        }
    }
}
