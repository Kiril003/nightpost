package local.phantom.companion.feature.ambient.whisper

/**
 * Phase 3-E3 — translate a [WhisperEvent] into the verbatim ribbon
 * text the operator sees drift across the top of the screen, or null
 * if the event isn't worth surfacing on the whisper line.
 *
 * 8 supported types (per the prompt's «verbatim mapping for 8 event
 * types» test target):
 *
 *  1. proactive.cycle               → operator-supplied text or default
 *  2. proactive.pending_action      → operator-supplied text or default
 *  3. proactive.action_fired        → operator-supplied text or default
 *  4. emotion.updated               → «відчуття · {emotion}» when set
 *  5. task.report_acknowledged      → «звіт прийнято»
 *  6. inner_monologue.thought       → «думка · {text}» when set
 *  7. inner_monologue.observation   → «помітив · {text}» when set
 *  8. inner_monologue.reflection    → «роздум · {text}» when set
 *
 * Anything else returns null — the queue silently drops unknown kinds
 * so a future server event can't fill the ribbon with garbage.
 *
 * Strings are intentionally lowercase Ukrainian per the design brief:
 * «teplyy, intymnyy, ti-form» — the ribbon is a peripheral cue, never
 * a notification headline.
 */
object WhisperLineMessageMapper {

    /** Default copy when the server didn't include a `text` body. */
    const val DEFAULT_PROACTIVE_CYCLE: String = "Nightpost думає про тебе"
    const val DEFAULT_PROACTIVE_PENDING: String = "є справа що чекає твоєї уваги"
    const val DEFAULT_PROACTIVE_FIRED: String = "Nightpost щойно дещо зробив"
    const val DEFAULT_TASK_ACK: String = "звіт прийнято"

    /** Known event kinds the mapper handles. */
    val SUPPORTED_KINDS: Set<String> = setOf(
        "proactive.cycle",
        "proactive.pending_action",
        "proactive.action_fired",
        "emotion.updated",
        "task.report_acknowledged",
        "inner_monologue.thought",
        "inner_monologue.observation",
        "inner_monologue.reflection",
    )

    fun mapEvent(event: WhisperEvent): String? = when (event.kind) {
        "proactive.cycle"            -> event.text.useOr(DEFAULT_PROACTIVE_CYCLE)
        "proactive.pending_action"   -> event.text.useOr(DEFAULT_PROACTIVE_PENDING)
        "proactive.action_fired"     -> event.text.useOr(DEFAULT_PROACTIVE_FIRED)
        "emotion.updated"            -> event.emotion?.takeIf { it.isNotBlank() }?.let { "відчуття · $it" }
        "task.report_acknowledged"   -> DEFAULT_TASK_ACK
        "inner_monologue.thought"        -> event.text?.takeIf { it.isNotBlank() }?.let { "думка · $it" }
        "inner_monologue.observation"    -> event.text?.takeIf { it.isNotBlank() }?.let { "помітив · $it" }
        "inner_monologue.reflection"     -> event.text?.takeIf { it.isNotBlank() }?.let { "роздум · $it" }
        else -> null
    }

    /** Trim+nullify-blank, with a positive default — stops empty events from leaking through. */
    private fun String?.useOr(default: String): String =
        this?.trim()?.takeIf { it.isNotEmpty() } ?: default
}
