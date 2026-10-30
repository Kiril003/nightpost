package local.phantom.companion.core.predict

/**
 * Tier 15-E — *amplifies the operator's own doubt*, never blocks the
 * action. Given a pending action and the operator's prior principles,
 * the amplifier returns a list of [DoubtPrompt]s. Each carries the
 * principle it derives from so the Stream UI can quote the exact
 * earlier commitment back to the operator.
 *
 * The amplifier is intentionally pure: no LLM call, no IO. The
 * runtime wires it to Decision Diary in `:app`.
 */
class DoubtAmplifier {

    fun evaluate(action: PendingAction, principles: Collection<Principle>): List<DoubtPrompt> {
        val out = mutableListOf<DoubtPrompt>()
        for (p in principles) {
            if (p.forbiddenVerbs.contains(action.verb)) {
                out += DoubtPrompt(
                    principleId = p.id,
                    reason = "current verb ${action.verb} is forbidden by principle",
                    quote = p.statement,
                )
                continue
            }
            if (action.contextTags.intersect(p.contextTags).isNotEmpty() &&
                p.contextTags.isNotEmpty()) {
                out += DoubtPrompt(
                    principleId = p.id,
                    reason = "action context overlaps a watched principle context",
                    quote = p.statement,
                )
            }
        }
        return out
    }
}

data class DoubtPrompt(
    val principleId: String,
    val reason: String,
    val quote: String,
)
