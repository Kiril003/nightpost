package local.phantom.companion.feature.stream.domain

/**
 * Phase 1-B — Stream thread identifiers.
 *
 * The Stream surface is split into named threads. Each [StreamItem] carries a
 * [threadId][StreamItem]; the renderer shows only the items belonging to
 * [StreamUiState.activeThreadId]. Server-pushed chat messages route by their
 * `session_id` (see DESIGN_BRIEF V.6 / PRESENCE_AND_CHAT §multi-thread):
 *  - if `session_id` matches a known thread → that thread
 *  - otherwise → [NOW] ("Зараз")
 *
 * Reference: /tmp/phantom-design-4/phantom-app2/project/phase2.jsx#ThreadRail
 */
object ThreadId {
    const val NOW = "now"
    const val MAIN = NOW
    const val OLYA = "olya"
    const val FAMILY = "family"
    const val WHISPERS = "whispers"
    const val VAULT = "vault"
}

/** R2.5 — space type behind a thread bucket (North Star Slice 2). */
enum class SpaceKind(val raw: String, val labelUa: String) {
    TASK("task", "задачі"),
    PERSON("person", "люди"),
    DEVICE("device", "пристрої"),
    // R2.5+05.09 — the Projects screen's own kind, so its list can tell its
    // spaces apart from a bare "задачі" space made from the header dialog.
    PROJECT("project", "проєкти"),
    SYSTEM("system", "");

    companion object {
        fun parse(raw: String?): SpaceKind =
            entries.firstOrNull { it.raw == raw } ?: TASK
    }
}

/**
 * Display metadata for a thread pill in the rail.
 *
 * A system thread names itself with [labelRes]; only an operator-created
 * space carries a literal [label], because that one really is data. The two
 * system threads used to hold literals too, so the menu offered «Vault» in
 * Latin next to «Зараз» while the island called the same place «СХОВИЩЕ» —
 * invisible to the i18n firewalls, which read composables, not domain types.
 */
data class ThreadDescriptor(
    val id: String,
    val label: String,
    val locked: Boolean = false,
    val kind: SpaceKind = SpaceKind.SYSTEM,
    @androidx.annotation.StringRes val labelRes: Int? = null,
)

/**
 * Default rail order. Only real, working threads ship here: «Зараз» (the
 * live timeline) and the encrypted «Vault». The mock `Olya/Family/Whispers`
 * pills from the phase2.jsx prototype were removed — they had no real
 * People/Companions feature behind them (fake-working theater). The
 * [ThreadId] constants stay for legacy routing/tags until People lands.
 */
val DefaultThreadRail: List<ThreadDescriptor> = listOf(
    ThreadDescriptor(
        id = ThreadId.NOW,
        label = "",
        labelRes = local.phantom.companion.feature.stream.R.string.thread_now,
    ),
    ThreadDescriptor(
        id = ThreadId.VAULT,
        label = "",
        locked = true,
        labelRes = local.phantom.companion.feature.stream.R.string.stratum_vault,
    ),
)

/**
 * P4-3-6 — legacy thread id → tag string for the upcoming single-
 * timeline view. Items written to the OLYA / FAMILY / WHISPERS
 * threads carry these tags so a future filter can recover origin
 * without preserving the bucketed UI.
 */
fun legacyThreadTagFor(threadId: String): String? = when (threadId) {
    ThreadId.OLYA -> ThreadId.OLYA
    ThreadId.FAMILY -> ThreadId.FAMILY
    ThreadId.WHISPERS -> ThreadId.WHISPERS
    else -> null
}

/**
 * Resolve an inbound `session_id` (or any free-form thread hint) to a known
 * thread identifier. Falls back to [ThreadId.NOW] when blank or unknown so
 * server pushes never disappear into a thread the operator can't see.
 */
fun resolveThreadId(sessionId: String?): String {
    val trimmed = sessionId?.trim().orEmpty()
    if (trimmed.isEmpty()) return ThreadId.NOW
    val known = setOf(
        ThreadId.NOW, ThreadId.OLYA, ThreadId.FAMILY,
        ThreadId.WHISPERS, ThreadId.VAULT,
    )
    return if (trimmed in known) trimmed else ThreadId.NOW
}
