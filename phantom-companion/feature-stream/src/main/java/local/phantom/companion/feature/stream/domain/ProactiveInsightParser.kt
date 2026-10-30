package local.phantom.companion.feature.stream.domain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/**
 * Phase 1-C — turns a raw `agent.stream` event into a [StreamItem.Insight].
 *
 * Lives in the feature-stream domain layer so `:feature-stream` does NOT need
 * a reverse-dep on `:app` (where the wire-side `AgentEvent` is defined). The
 * parser operates on primitive fields (`kind`, `text`, raw JsonObject) so
 * the call site at [local.phantom.companion.ui.StreamNav] can map without
 * leaking AgentEvent into this module.
 *
 * Recognised event kinds:
 *  - `proactive.cycle` → [StreamItem.Insight.Kind.CYCLE]
 *  - `proactive.pending_action` → [StreamItem.Insight.Kind.PENDING_ACTION]
 *
 * Anything else returns null — the caller drops it.
 *
 * Payload fields (best-effort, all optional):
 *  - `id` (String) — stable insight id; falls back to a UUID
 *  - `title` (String) — short header; falls back to a kind-specific default
 *  - `body` (String) — body copy; falls back to the event-level `text`
 *  - `actions` (Array&lt;{label, action_id, primary?}&gt;) — payload-driven
 *  - `session_id` (String) — routes the insight to a specific thread via
 *    [resolveThreadId]; defaults to [ThreadId.NOW] when absent / unknown
 */
object ProactiveInsightParser {

    fun tryParse(
        kind: String,
        text: String?,
        raw: JsonObject?,
    ): StreamItem.Insight? {
        val insightKind = when (kind) {
            "proactive.cycle" -> StreamItem.Insight.Kind.CYCLE
            "proactive.pending_action" -> StreamItem.Insight.Kind.PENDING_ACTION
            else -> return null
        }

        val payload = raw ?: JsonObject(emptyMap())

        val id = payload.stringOrNull("id")
            ?: "insight-${UUID.randomUUID()}"

        val title = payload.stringOrNull("title")
            ?: when (insightKind) {
                StreamItem.Insight.Kind.CYCLE -> "інсайт"
                StreamItem.Insight.Kind.PENDING_ACTION -> "запит дії"
            }

        val body = (text?.takeIf { it.isNotBlank() })
            ?: payload.stringOrNull("body")
            ?: payload.stringOrNull("text")
            ?: ""

        // A card with nothing in it is not an insight. The backend emits
        // `proactive.cycle` on a timer whether or not it has anything to say,
        // and each empty one rendered as a full-width «ІНСАЙТ» with a dismiss
        // button — nine of them stacked, none of which could do anything.
        // Silence is the honest rendering of nothing.
        val hasTitle = payload.stringOrNull("title")?.isNotBlank() == true
        val hasActions = (payload["actions"] as? JsonArray)?.isNotEmpty() == true
        if (body.isBlank() && !hasTitle && !hasActions) return null

        val actions = (payload["actions"] as? JsonArray)
            ?.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val label = obj.stringOrNull("label") ?: return@mapNotNull null
                val actionId = obj.stringOrNull("action_id")
                    ?: obj.stringOrNull("id")
                    ?: label
                val primary = (obj["primary"] as? JsonPrimitive)?.booleanOrNull ?: false
                InsightAction(label = label, actionId = actionId, primary = primary)
            }
            .orEmpty()

        val threadId = resolveThreadId(payload.stringOrNull("session_id"))

        return StreamItem.Insight(
            id = id,
            title = title,
            body = body,
            kind = insightKind,
            actions = actions,
            why = payload.stringOrNull("why") ?: payload.stringOrNull("reason"),
            confidence = (payload["confidence"] as? JsonPrimitive)
                ?.content?.toFloatOrNull()?.coerceIn(0f, 1f),
            threadId = threadId,
        )
    }
}

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
