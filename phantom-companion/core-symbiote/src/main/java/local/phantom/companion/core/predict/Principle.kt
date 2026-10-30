package local.phantom.companion.core.predict

/**
 * Tier 15-E — a declared principle the operator has committed to,
 * with the action verbs it forbids and a free-form rationale that
 * gets fed back into the doubt prompt verbatim ("ти вирішив у січні
 * не залишатися на роботі довше 19…").
 */
data class Principle(
    val id: String,
    val declaredAtMs: Long,
    val statement: String,
    val forbiddenVerbs: Set<String> = emptySet(),
    val contextTags: Set<String> = emptySet(),
)

/**
 * A pending action the doubt amplifier evaluates against the
 * operator's principles. Verb is the canonical action token from
 * the action dispatcher; contextTags carries the call-site context.
 */
data class PendingAction(
    val verb: String,
    val timestampMs: Long,
    val contextTags: Set<String> = emptySet(),
)
