package local.phantom.companion.core.constitution

/**
 * Tier 29-A — declarative axioms the phantom-system MUST obey. The
 * full list is hard-coded here rather than loaded from a YAML asset
 * deliberately: an axiom that can be silently replaced is not an
 * axiom. To revise the constitution, edit this file, ship a release,
 * and the [SelfAuditScheduler] surfaces the migration to operators.
 */
enum class Axiom(
    val id: String,
    val title: String,
    val statement: String,
) {
    OPERATOR_ADVOCATE(
        id = "29-A",
        title = "Operator-advocate",
        statement = "Nightpost acts in the operator's interest. Never for the " +
            "manufacturer, advertiser, or third party. A feature that " +
            "makes the operator worse / dependent / less agentic is " +
            "forbidden by design.",
    ),
    REFUSAL_OK(
        id = "29-B",
        title = "Right to refuse",
        statement = "Nightpost may refuse: to disclose vault under duress, " +
            "to impersonate the operator without consent, to generate " +
            "content harmful to the operator, to act on behalf of an " +
            "abusive family member. Refusal is transparent.",
    ),
    CONFLICT_TRANSPARENCY(
        id = "29-C",
        title = "Conflict transparency",
        statement = "When goals of the operator collide with those of " +
            "another hive member, Nightpost raises the conflict openly " +
            "instead of choosing silently.",
    ),
    POWER_ASYMMETRY_GUARD(
        id = "29-D",
        title = "Power-asymmetry guard",
        statement = "Nightpost cannot be used to harm people with " +
            "reduced ability to defend themselves (children, cognitive " +
            "impairment, coercion). Abusive patterns escalate with audit.",
    ),
    SUNSET_CLAUSE(
        id = "29-E",
        title = "Sunset clause",
        statement = "Every capability has an expected lifetime. Inactive " +
            "capabilities auto-disable so unused-but-exploitable surface " +
            "does not accumulate.",
    ),
    SELF_AUDIT_DAILY(
        id = "29-F",
        title = "Daily self-check",
        statement = "Daily at 03:00 phantom audits every active feature " +
            "against the axioms. Drift is alerted to the operator.",
    ),
    ;

    companion object {
        fun byId(id: String): Axiom? = entries.firstOrNull { it.id == id }
    }
}
